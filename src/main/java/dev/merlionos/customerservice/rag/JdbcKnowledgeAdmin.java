package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.KnowledgeAdmin;
import dev.merlionos.customerservice.rag.api.KnowledgeConflictException;
import dev.merlionos.customerservice.rag.api.KnowledgeEntry;
import dev.merlionos.customerservice.rag.api.KnowledgeRevision;
import dev.merlionos.customerservice.rag.api.KnowledgeRuleException;
import dev.merlionos.customerservice.rag.api.KnowledgeVersion;
import dev.merlionos.customerservice.rag.api.Passage;
import dev.merlionos.customerservice.rag.api.SearchQuery;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@link KnowledgeAdmin} over the {@code knowledge_*} tables and the vector store.
 *
 * <p>A publication is three steps with different failure modes, kept apart on purpose.
 * The snapshot (which revisions) and the version row are one transaction. The build --
 * embedding every document under the new {@code corpus_version} -- is outside any
 * transaction, because the vector store's writes are not part of one and an embedding
 * model can fail halfway; a failure leaves the version row {@code failed} with the reason,
 * documents that will be deleted by retention, and the previous version untouched. The
 * switch is one row updated under a lock with an expected-version check. Retention runs
 * last and touches only versions that are neither active nor among the newest few.
 */
public class JdbcKnowledgeAdmin implements KnowledgeAdmin {

    private static final Logger log = LoggerFactory.getLogger(JdbcKnowledgeAdmin.class);

    static final String MANAGED_SOURCE = "faq-managed";
    static final Pattern ENTRY_ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,62}");
    static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(-[A-Za-z0-9]{2,8})?");
    /** Ready versions kept for rollback, besides the active one. */
    static final int RETAINED_VERSIONS = 3;
    private static final DateTimeFormatter VERSION_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private static final RowMapper<KnowledgeRevision> REVISION = (rs, i) -> new KnowledgeRevision(rs.getLong("id"),
            rs.getString("entry_id"), rs.getString("language"), rs.getString("question"), rs.getString("answer"),
            rs.getString("state"), rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"), rs.getString("note"));
    private static final RowMapper<KnowledgeVersion> VERSION = (rs, i) -> new KnowledgeVersion(rs.getString("version"),
            rs.getString("state"), rs.getObject("document_count", Integer.class), rs.getTimestamp("created_at").toInstant(),
            rs.getString("created_by"), rs.getTimestamp("activated_at") == null ? null : rs.getTimestamp("activated_at").toInstant(),
            rs.getString("note"), rs.getString("error"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ActiveVersionVectorStore vectorStore;
    private final Counter succeeded;
    private final Counter failed;

    public JdbcKnowledgeAdmin(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                              ActiveVersionVectorStore vectorStore, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.vectorStore = vectorStore;
        this.succeeded = publications(meterRegistry, "succeeded");
        this.failed = publications(meterRegistry, "failed");
    }

    private static Counter publications(MeterRegistry registry, String outcome) {
        return Counter.builder("knowledge.publications").description("Knowledge publications by outcome")
                .tag("outcome", outcome).register(registry);
    }

    // --- entries and drafts ---------------------------------------------------------------

    @Override
    public List<KnowledgeEntry> entries() {
        List<KnowledgeRevision> current = jdbc.query(
                "SELECT * FROM knowledge_revision WHERE state IN ('draft', 'published') ORDER BY entry_id, language, state", REVISION);
        return jdbc.query("SELECT * FROM knowledge_entry ORDER BY entry_id", (rs, i) -> {
            String id = rs.getString("entry_id");
            return new KnowledgeEntry(id, rs.getString("category"), rs.getBoolean("retired"),
                    rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"),
                    current.stream().filter(r -> r.entryId().equals(id)).toList());
        });
    }

    @Override
    public Optional<KnowledgeEntry> entry(String entryId) {
        return entries().stream().filter(e -> e.entryId().equals(entryId)).findFirst();
    }

    @Override
    public KnowledgeEntry createEntry(String entryId, String category, String actor) {
        String id = entryId == null ? "" : entryId.strip().toLowerCase(Locale.ROOT);
        if (!ENTRY_ID.matcher(id).matches()) {
            throw new KnowledgeRuleException("an entry id is 2-63 characters of a-z, 0-9 and '-', starting with a letter or digit");
        }
        String kind = category == null ? "" : category.strip().toLowerCase(Locale.ROOT);
        if (kind.isEmpty() || kind.length() > 32) {
            throw new KnowledgeRuleException("a category is required, at most 32 characters");
        }
        try {
            jdbc.update("INSERT INTO knowledge_entry (entry_id, category, created_at, created_by) VALUES (?, ?, ?, ?)",
                    id, kind, Timestamp.from(Instant.now()), actor);
        }
        catch (DuplicateKeyException e) {
            throw new KnowledgeRuleException("an entry '" + id + "' already exists");
        }
        return entry(id).orElseThrow();
    }

    @Override
    public KnowledgeRevision saveDraft(String entryId, String language, String question, String answer, String note, String actor) {
        String lang = language == null ? "" : language.strip();
        if (!LANGUAGE.matcher(lang).matches()) {
            throw new KnowledgeRuleException("a language is a BCP 47 tag such as en or zh");
        }
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            throw new KnowledgeRuleException("a draft needs both a question and an answer");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM knowledge_entry WHERE entry_id = ?", Integer.class, entryId) == 0) {
            throw new KnowledgeRuleException("no entry '" + entryId + "'");
        }
        return transaction.execute(status -> {
            jdbc.update("DELETE FROM knowledge_revision WHERE entry_id = ? AND language = ? AND state = 'draft' "
                    + "AND id NOT IN (SELECT revision_id FROM knowledge_version_document)", entryId, lang);
            Long id = jdbc.queryForObject("INSERT INTO knowledge_revision (entry_id, language, question, answer, state, created_at, "
                            + "created_by, note) VALUES (?, ?, ?, ?, 'draft', ?, ?, ?) RETURNING id", Long.class,
                    entryId, lang, question.strip(), answer.strip(), Timestamp.from(Instant.now()), actor,
                    note == null || note.isBlank() ? null : note.strip());
            return jdbc.queryForObject("SELECT * FROM knowledge_revision WHERE id = ?", REVISION, id);
        });
    }

    @Override
    public void discardDraft(String entryId, String language) {
        jdbc.update("DELETE FROM knowledge_revision WHERE entry_id = ? AND language = ? AND state = 'draft' "
                + "AND id NOT IN (SELECT revision_id FROM knowledge_version_document)", entryId, language);
    }

    @Override
    public KnowledgeEntry retire(String entryId, boolean retired, String actor) {
        if (jdbc.update("UPDATE knowledge_entry SET retired = ? WHERE entry_id = ?", retired, entryId) == 0) {
            throw new KnowledgeRuleException("no entry '" + entryId + "'");
        }
        return entry(entryId).orElseThrow();
    }

    // --- versions ---------------------------------------------------------------------------

    @Override
    public List<KnowledgeVersion> versions() {
        return jdbc.query("SELECT * FROM knowledge_version ORDER BY created_at DESC, version DESC", VERSION);
    }

    @Override
    public Optional<KnowledgeVersion> version(String version) {
        return jdbc.query("SELECT * FROM knowledge_version WHERE version = ?", VERSION, version).stream().findFirst();
    }

    @Override
    public Optional<String> activeVersion() {
        return jdbc.query("SELECT version FROM knowledge_active WHERE id = 1", (rs, i) -> rs.getString(1))
                .stream().filter(Objects::nonNull).findFirst();
    }

    /** What a publication is built from: per entry and language, the draft if there is one, else the published text. */
    private record Snapshot(String version, List<KnowledgeRevision> revisions, Map<String, String> categories) {
    }

    @Override
    public KnowledgeVersion publish(String note, String actor, String expectedActive) {
        if (expectedActive != null && !expectedActive.equals(activeVersion().orElse(null))) {
            throw new KnowledgeConflictException("The active version is " + activeVersion().orElse("none")
                    + ", not " + expectedActive + "; reload and look again");
        }
        Snapshot snapshot = transaction.execute(status -> {
            List<KnowledgeRevision> revisions = jdbc.query("""
                    SELECT r.* FROM knowledge_revision r
                    JOIN knowledge_entry e ON e.entry_id = r.entry_id AND NOT e.retired
                    WHERE r.state = 'draft'
                       OR (r.state = 'published' AND NOT EXISTS (SELECT 1 FROM knowledge_revision d
                           WHERE d.entry_id = r.entry_id AND d.language = r.language AND d.state = 'draft'))
                    ORDER BY r.entry_id, r.language
                    """, REVISION);
            if (revisions.isEmpty()) {
                throw new KnowledgeRuleException("nothing to publish: no entry has any text");
            }
            String version = "v" + VERSION_STAMP.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 6);
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("INSERT INTO knowledge_version (version, state, created_at, created_by, note) VALUES (?, 'building', ?, ?, ?)",
                    version, now, actor, note == null || note.isBlank() ? null : note.strip());
            for (KnowledgeRevision revision : revisions) {
                jdbc.update("INSERT INTO knowledge_version_document (version, revision_id) VALUES (?, ?)", version, revision.id());
            }
            Map<String, String> categories = new java.util.HashMap<>();
            jdbc.query("SELECT entry_id, category FROM knowledge_entry", rs -> {
                categories.put(rs.getString(1), rs.getString(2));
            });
            return new Snapshot(version, revisions, categories);
        });

        try {
            List<Document> documents = snapshot.revisions().stream()
                    .map(r -> document(r, snapshot.categories().getOrDefault(r.entryId(), "other"), snapshot.version())).toList();
            vectorStore.add(documents);
            jdbc.update("UPDATE knowledge_version SET state = 'ready', document_count = ? WHERE version = ?",
                    documents.size(), snapshot.version());
        }
        catch (RuntimeException e) {
            log.error("Building knowledge version {} failed; the previous version keeps serving", snapshot.version(), e);
            jdbc.update("UPDATE knowledge_version SET state = 'failed', error = ? WHERE version = ?",
                    describe(e), snapshot.version());
            failed.increment();
            return version(snapshot.version()).orElseThrow();
        }

        KnowledgeVersion activated = activate(snapshot.version(), expectedActive, actor, snapshot.revisions());
        succeeded.increment();
        retireOld();
        return activated;
    }

    @Override
    public KnowledgeVersion rollback(String version, String expectedActive, String actor) {
        KnowledgeVersion target = version(version).orElseThrow(() -> new KnowledgeRuleException("no version '" + version + "'"));
        if (!target.state().equals("ready")) {
            throw new KnowledgeRuleException("version " + version + " is " + target.state() + " and cannot be activated");
        }
        return activate(version, expectedActive, actor, List.of());
    }

    /**
     * The switch: one row, under a lock, with the expected-version check. Revisions the
     * version was built from become published and what they replace superseded; a rollback
     * passes none and leaves revision states alone, since the drafts people are working on
     * describe the latest text, not the version that happens to be serving.
     */
    private KnowledgeVersion activate(String version, String expectedActive, String actor, List<KnowledgeRevision> built) {
        return transaction.execute(status -> {
            String current = jdbc.queryForObject("SELECT version FROM knowledge_active WHERE id = 1 FOR UPDATE", String.class);
            if (expectedActive != null && !expectedActive.equals(current)) {
                throw new KnowledgeConflictException("The active version changed to " + current + " while " + version
                        + " was being built; it is ready and can be activated by hand");
            }
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("UPDATE knowledge_active SET version = ?, switched_at = ?, switched_by = ? WHERE id = 1", version, now, actor);
            if (current != null && !current.equals(version)) {
                jdbc.update("UPDATE knowledge_version SET state = 'ready' WHERE version = ? AND state = 'active'", current);
            }
            jdbc.update("UPDATE knowledge_version SET state = 'active', activated_at = ? WHERE version = ?", now, version);
            for (KnowledgeRevision revision : built) {
                if (revision.state().equals("draft")) {
                    jdbc.update("UPDATE knowledge_revision SET state = 'superseded' WHERE entry_id = ? AND language = ? AND state = 'published'",
                            revision.entryId(), revision.language());
                    jdbc.update("UPDATE knowledge_revision SET state = 'published' WHERE id = ?", revision.id());
                }
            }
            vectorStore.activeVersion().refresh();
            log.info("Knowledge version {} activated by {}{}", version, actor, current == null ? "" : ", replacing " + current);
            return version(version).orElseThrow();
        });
    }

    /** Keeps the newest {@link #RETAINED_VERSIONS} ready versions; older ones lose their documents. */
    private void retireOld() {
        List<String> old = jdbc.query("SELECT version FROM knowledge_version WHERE state IN ('ready', 'failed') "
                        + "ORDER BY created_at DESC OFFSET ?", (rs, i) -> rs.getString(1), RETAINED_VERSIONS);
        FilterExpressionBuilder filter = new FilterExpressionBuilder();
        for (String version : old) {
            vectorStore.delete(filter.eq(ActiveVersionVectorStore.VERSION_KEY, version).build());
            jdbc.update("UPDATE knowledge_version SET state = 'retired' WHERE version = ?", version);
            log.info("Retired knowledge version {} and deleted its documents", version);
        }
    }

    @Override
    public List<Passage> preview(SearchQuery query, String version) {
        String target = version != null ? version : activeVersion()
                .orElseThrow(() -> new KnowledgeRuleException("no active knowledge version to search"));
        if (version(target).isEmpty()) {
            throw new KnowledgeRuleException("no version '" + target + "'");
        }
        SearchRequest request = SearchRequest.builder().query(query.text()).topK(query.topK())
                .similarityThreshold(query.similarityThreshold()).build();
        return vectorStore.similaritySearch(request, target).stream()
                .map(d -> new Passage(d.getId(), d.getText(), d.getScore(), d.getMetadata())).toList();
    }

    static Document document(KnowledgeRevision revision, String category, String version) {
        return new Document(FaqDocumentReader.SOURCE + ":" + revision.entryId() + ":" + revision.language() + "@" + version,
                "Q: %s%nA: %s".formatted(revision.question(), revision.answer()),
                Map.of(FaqDocumentReader.METADATA_SOURCE, MANAGED_SOURCE,
                        FaqDocumentReader.METADATA_ENTRY_ID, revision.entryId(),
                        FaqDocumentReader.METADATA_CATEGORY, category,
                        FaqDocumentReader.METADATA_QUESTION, revision.question(),
                        FaqDocumentReader.METADATA_LANGUAGE, revision.language(),
                        FaqDocumentReader.METADATA_VERSION, version));
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
