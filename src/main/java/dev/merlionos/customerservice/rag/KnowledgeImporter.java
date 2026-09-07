package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.KnowledgeImport;
import dev.merlionos.customerservice.rag.api.KnowledgeRuleException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.jsoup.JsoupDocumentReader;
import org.springframework.ai.reader.jsoup.config.JsoupDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A tenant's document into draft entries (ADR 002, step 4). A web page is fetched under
 * {@link SourceGuard}, a PDF arrives as bytes; both are read by Spring AI's readers, split
 * into chunks of about {@code chunkTokens}, and each chunk becomes a draft under an entry
 * whose id is the source's hash and the chunk's position. Importing the same source again
 * writes over those drafts and retires the entries beyond the new count, so a re-import
 * is a replacement, never a duplicate. Nothing is published: the drafts are what the admin
 * reviews and publishes, or discards.
 *
 * <p>The import runs off the seam's request thread and is polled, like a publication: a
 * fetch can take seconds and the chat process's client gives an internal call five.
 */
public class KnowledgeImporter {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeImporter.class);

    static final String IMPORTED_CATEGORY = "imported";
    static final int MAX_REDIRECTS = 5;
    private static final int MAX_TITLE = 160;

    private static final RowMapper<KnowledgeImport> IMPORT = (rs, i) -> new KnowledgeImport(rs.getLong("id"),
            rs.getString("tenant_id"), rs.getString("source_kind"), rs.getString("source"), rs.getString("state"),
            rs.getObject("entries", Integer.class), rs.getString("error"), rs.getString("requested_by"),
            rs.getTimestamp("requested_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());

    /** One chunk of a document, about to become a draft. */
    record Chunk(String title, String text) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final KnowledgeImportProperties properties;
    private final SourceGuard guard;
    private final HttpClient http;
    private final Counter done;
    private final Counter failed;

    public KnowledgeImporter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                             KnowledgeImportProperties properties, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.guard = new SourceGuard(properties.allowPrivateNetworksOrDefault());
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.fetchTimeoutOrDefault())
                .build();
        this.done = Counter.builder("knowledge.imports").description("Knowledge imports by outcome")
                .tag("outcome", "done").register(meterRegistry);
        this.failed = Counter.builder("knowledge.imports").description("Knowledge imports by outcome")
                .tag("outcome", "failed").register(meterRegistry);
    }

    // --- starting and reading imports ---------------------------------------------------------

    public KnowledgeImport importUrl(String tenantId, String url, String actor) {
        URI uri = guard.check(url);
        KnowledgeImport started = start(tenantId, "url", uri.toString(), actor);
        Thread.ofVirtual().name("knowledge-import-" + started.id()).start(() -> run(started, () -> readUrl(uri)));
        return started;
    }

    public KnowledgeImport importPdf(String tenantId, String fileName, byte[] content, String actor) {
        String name = fileName == null || fileName.isBlank() ? "document.pdf" : fileName.strip();
        if (content == null || content.length == 0) {
            throw new KnowledgeRuleException("the PDF is empty");
        }
        if (content.length > properties.maxDocumentSizeOrDefault().toBytes()) {
            throw new KnowledgeRuleException("the PDF is larger than " + properties.maxDocumentSizeOrDefault());
        }
        if (content.length < 5 || !new String(content, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new KnowledgeRuleException("not a PDF: " + name);
        }
        KnowledgeImport started = start(tenantId, "pdf", name, actor);
        Thread.ofVirtual().name("knowledge-import-" + started.id()).start(() -> run(started, () -> readPdf(name, content)));
        return started;
    }

    public List<KnowledgeImport> imports(String tenantId) {
        return jdbc.query("SELECT * FROM knowledge_import WHERE tenant_id = ? ORDER BY requested_at DESC, id DESC", IMPORT, tenantId);
    }

    public Optional<KnowledgeImport> importOf(String tenantId, long id) {
        return jdbc.query("SELECT * FROM knowledge_import WHERE tenant_id = ? AND id = ?", IMPORT, tenantId, id).stream().findFirst();
    }

    private KnowledgeImport start(String tenantId, String kind, String source, String actor) {
        Long id = jdbc.queryForObject("INSERT INTO knowledge_import (tenant_id, source_kind, source, state, requested_by, requested_at) "
                + "VALUES (?, ?, ?, 'running', ?, ?) RETURNING id", Long.class, tenantId, kind, source, actor, Timestamp.from(Instant.now()));
        return importOf(tenantId, id).orElseThrow();
    }

    private interface Reading {
        List<Chunk> read() throws IOException, InterruptedException;
    }

    private void run(KnowledgeImport started, Reading reading) {
        try {
            List<Chunk> chunks = reading.read();
            if (chunks.isEmpty()) {
                throw new KnowledgeRuleException("no text was found in " + started.source());
            }
            int written = write(started, chunks);
            jdbc.update("UPDATE knowledge_import SET state = 'done', entries = ?, finished_at = ? WHERE id = ?",
                    written, Timestamp.from(Instant.now()), started.id());
            done.increment();
            log.info("Imported {} {} for tenant {}: {} draft entries", started.sourceKind(), started.source(), started.tenantId(), written);
        }
        catch (Exception e) {
            String reason = e instanceof KnowledgeRuleException ? e.getMessage() : describe(e);
            jdbc.update("UPDATE knowledge_import SET state = 'failed', error = ?, finished_at = ? WHERE id = ?",
                    reason, Timestamp.from(Instant.now()), started.id());
            failed.increment();
            log.warn("Import {} of {} for tenant {} failed: {}", started.id(), started.source(), started.tenantId(), reason);
        }
    }

    // --- reading ------------------------------------------------------------------------------

    /** Fetches under the guard, following at most {@link #MAX_REDIRECTS} redirects, each checked again. */
    List<Chunk> readUrl(URI first) throws IOException, InterruptedException {
        URI uri = first;
        for (int hop = 0; ; hop++) {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(properties.fetchTimeoutOrDefault())
                    .header("Accept", "text/html, application/xhtml+xml").header("User-Agent", "ai-customer-service-import/1")
                    .GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                response.body().close();
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new KnowledgeRuleException("the page redirected without saying where"));
                if (hop >= MAX_REDIRECTS) {
                    throw new KnowledgeRuleException("the page redirected more than " + MAX_REDIRECTS + " times");
                }
                uri = guard.check(uri.resolve(location).toString());
                continue;
            }
            if (status != 200) {
                response.body().close();
                throw new KnowledgeRuleException("the page answered " + status);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("text/html") && !contentType.startsWith("application/xhtml")) {
                response.body().close();
                throw new KnowledgeRuleException("not an HTML page (" + (contentType.isEmpty() ? "no content type" : contentType) + ")");
            }
            byte[] html = readBounded(response.body(), (int) properties.maxDocumentSizeOrDefault().toBytes());
            String title = Jsoup.parse(new String(html, StandardCharsets.UTF_8)).title();
            if (title == null || title.isBlank()) {
                title = uri.getHost() + (uri.getPath() == null ? "" : uri.getPath());
            }
            List<Document> read = new JsoupDocumentReader(new ByteArrayResource(html),
                    JsoupDocumentReaderConfig.builder().selector("body").allElements(false).build()).get();
            return chunk(title, read);
        }
    }

    List<Chunk> readPdf(String fileName, byte[] content) {
        List<Document> pages = new PagePdfDocumentReader(new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        }, PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build()).get();
        String title = fileName.toLowerCase(Locale.ROOT).endsWith(".pdf") ? fileName.substring(0, fileName.length() - 4) : fileName;
        return chunk(title, pages);
    }

    private List<Chunk> chunk(String title, List<Document> documents) {
        // The PDF layout stripper keeps columns aligned with runs of spaces; a passage is prose.
        List<Document> withText = documents.stream()
                .filter(d -> d.getText() != null && !d.getText().isBlank())
                .map(d -> new Document(d.getText().replaceAll("[ \\t\\u00a0]+", " ").replaceAll(" ?\\n ?", "\n").strip())).toList();
        if (withText.isEmpty()) {
            return List.of();
        }
        TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(properties.chunkTokensOrDefault())
                .withMinChunkSizeChars(200).withMinChunkLengthToEmbed(5).withMaxNumChunks(1000).withKeepSeparator(true).build();
        List<Document> pieces = splitter.apply(withText);
        String heading = title.strip().length() > MAX_TITLE ? title.strip().substring(0, MAX_TITLE) : title.strip();
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            String text = pieces.get(i).getText().strip();
            if (text.isEmpty()) {
                continue;
            }
            chunks.add(new Chunk(pieces.size() == 1 ? heading : heading + " (" + (i + 1) + "/" + pieces.size() + ")", text));
        }
        return chunks;
    }

    private static byte[] readBounded(InputStream in, int max) throws IOException {
        try (in) {
            byte[] bytes = in.readNBytes(max + 1);
            if (bytes.length > max) {
                throw new KnowledgeRuleException("the page is larger than " + max + " bytes");
            }
            return bytes;
        }
    }

    // --- writing ------------------------------------------------------------------------------

    /** Every chunk a draft under its entry; entries of the same source beyond the new count retired. */
    private int write(KnowledgeImport started, List<Chunk> chunks) {
        String prefix = started.sourceKind() + "-" + hash8(started.source()) + "-";
        Timestamp now = Timestamp.from(Instant.now());
        String note = "imported from " + started.source();
        return transaction.execute(status -> {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);
                String entryId = prefix + (i + 1);
                ids.add(entryId);
                jdbc.update("""
                        INSERT INTO knowledge_entry (tenant_id, entry_id, category, retired, created_at, created_by, source_kind, source)
                        VALUES (?, ?, ?, false, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, entry_id) DO UPDATE SET retired = false, source_kind = EXCLUDED.source_kind, source = EXCLUDED.source
                        """, started.tenantId(), entryId, IMPORTED_CATEGORY, now, started.requestedBy(), started.sourceKind(), started.source());
                String language = languageOf(chunk.text());
                jdbc.update("DELETE FROM knowledge_revision WHERE tenant_id = ? AND entry_id = ? AND language = ? AND state = 'draft' "
                        + "AND id NOT IN (SELECT revision_id FROM knowledge_version_document)", started.tenantId(), entryId, language);
                jdbc.update("INSERT INTO knowledge_revision (tenant_id, entry_id, language, question, answer, state, created_at, created_by, note) "
                                + "VALUES (?, ?, ?, ?, ?, 'draft', ?, ?, ?)",
                        started.tenantId(), entryId, language, chunk.title(), chunk.text(), now, started.requestedBy(), note);
            }
            // The rest of this source: chunks that no longer exist are retired, and their drafts dropped.
            List<String> stale = jdbc.queryForList("SELECT entry_id FROM knowledge_entry WHERE tenant_id = ? AND source = ? "
                    + "AND source_kind = ? AND NOT retired", String.class, started.tenantId(), started.source(), started.sourceKind())
                    .stream().filter(id -> !ids.contains(id)).toList();
            for (String entryId : stale) {
                jdbc.update("DELETE FROM knowledge_revision WHERE tenant_id = ? AND entry_id = ? AND state = 'draft' "
                        + "AND id NOT IN (SELECT revision_id FROM knowledge_version_document)", started.tenantId(), entryId);
                jdbc.update("UPDATE knowledge_entry SET retired = true WHERE tenant_id = ? AND entry_id = ?", started.tenantId(), entryId);
            }
            return ids.size();
        });
    }

    /** Chinese when a fifth of the letters are CJK; the corpus is bilingual and that is the split it needs. */
    static String languageOf(String text) {
        long letters = 0;
        long cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                Character.UnicodeScript script = Character.UnicodeScript.of(c);
                if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL) {
                    cjk++;
                }
            }
        }
        return letters > 0 && cjk * 5 >= letters ? "zh" : "en";
    }

    static String hash8(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = e.getClass().getSimpleName() + ": " + e.getMessage()
                + (root == e ? "" : " (caused by " + root.getClass().getSimpleName() + ": " + root.getMessage() + ")");
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
