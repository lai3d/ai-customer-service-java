package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.chat.ChatService;
import dev.merlionos.customerservice.chat.TurnEvent;
import dev.merlionos.customerservice.rag.api.KnowledgeImport;
import dev.merlionos.customerservice.rag.api.TenantFilter;
import dev.merlionos.customerservice.tenancy.Tenants;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import dev.merlionos.customerservice.rag.api.KnowledgeAdmin;
import dev.merlionos.customerservice.rag.api.KnowledgeConflictException;
import dev.merlionos.customerservice.rag.api.KnowledgeEntry;
import dev.merlionos.customerservice.rag.api.KnowledgeRevision;
import dev.merlionos.customerservice.rag.api.KnowledgeRuleException;
import dev.merlionos.customerservice.rag.api.KnowledgeSearch;
import dev.merlionos.customerservice.rag.api.KnowledgeVersion;
import dev.merlionos.customerservice.rag.api.Passage;
import dev.merlionos.customerservice.rag.api.SearchQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static dev.merlionos.customerservice.rag.api.SearchQuery.DEFAULT_TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Editing and publishing over the real embedding model and pgvector. Deliberately its own
 * context, and so its own database: a publication changes what the vector store holds and
 * what retrieval returns, which is exactly what the retrieval tests measure, and they must
 * not share a database with something that publishes. One more context is what the shared
 * Postgres container (CLAUDE.md) made affordable. Ordered: a publication is a sequence.
 */
@SpringBootTest(properties = {"app.rag.import-mode=startup", "app.test.isolated=knowledge-admin"})
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeAdminIntegrationTest {

    @Autowired KnowledgeAdmin admin;
    @Autowired KnowledgeSearch search;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired FaqIngestionService ingestion;
    @Autowired Tenants tenants;
    @Autowired VectorStore vectorStore;
    @Autowired ChatService chatService;
    @MockitoBean AnthropicChatModel chatModel;

    static String bundled;
    static String published;

    private List<String> entriesFound(String question, String version) {
        return admin.preview(new SearchQuery(DEFAULT_TENANT, question, 3, 0), version).stream()
                .map(p -> String.valueOf(p.metadata().get("entry_id"))).toList();
    }

    @Test
    @Order(1)
    @DisplayName("the bundled corpus was adopted as the active version, with its entries as published revisions, without re-embedding")
    void bundledCorpusIsAdopted() {
        bundled = ingestion.bundledVersion();
        assertThat(admin.activeVersion(DEFAULT_TENANT)).hasValue(bundled);
        KnowledgeVersion version = admin.version(DEFAULT_TENANT, bundled).orElseThrow();
        assertThat(version.state()).isEqualTo("active");
        assertThat(version.documentCount()).isEqualTo(36);
        assertThat(version.createdBy()).isEqualTo(KnowledgeBootstrap.BUNDLED_ACTOR);
        assertThat(admin.entries(DEFAULT_TENANT)).hasSize(18).allSatisfy(entry ->
                assertThat(entry.revisions()).extracting(r -> r.state()).containsOnly("published"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store", Integer.class))
                .as("adoption embedded nothing").isEqualTo(36);
        assertThat(search.search(new SearchQuery(DEFAULT_TENANT, "运费多少钱", 3, 0))).extracting(p -> p.metadata().get("entry_id"))
                .contains("shipping-cost");
    }

    @Test
    @Order(2)
    @DisplayName("a draft changes nothing a customer sees; a publication builds a new version, activates it, and retrieval follows")
    void draftThenPublish() {
        KnowledgeEntry created = admin.createEntry(DEFAULT_TENANT, "gift-wrap", "orders", "alice");
        assertThat(created.revisions()).isEmpty();
        admin.saveDraft(DEFAULT_TENANT, "gift-wrap", "en", "Do you offer gift wrapping?",
                "Yes. Choose gift wrapping at checkout for 3 dollars per item; a handwritten note is free.", "new service", "alice");
        admin.saveDraft(DEFAULT_TENANT, "gift-wrap", "zh", "可以礼品包装吗？", "可以。结账时选择礼品包装，每件 3 美元，手写贺卡免费。", null, "alice");
        admin.saveDraft(DEFAULT_TENANT, "shipping-cost", "en", "How much does shipping cost?",
                "Shipping is free on orders over 50 dollars. Below that, standard shipping is 5 dollars.", "threshold named", "alice");
        assertThat(admin.entry(DEFAULT_TENANT, "gift-wrap").orElseThrow().revisions()).extracting(r -> r.state()).containsOnly("draft");

        assertThat(entriesFound("gift wrapping", null)).as("drafts are invisible to retrieval").doesNotContain("gift-wrap");
        assertThatThrownBy(() -> admin.saveDraft(DEFAULT_TENANT, "nope", "en", "q", "a", null, "alice")).isInstanceOf(KnowledgeRuleException.class);
        assertThatThrownBy(() -> admin.saveDraft(DEFAULT_TENANT, "gift-wrap", "en", "", "a", null, "alice")).isInstanceOf(KnowledgeRuleException.class);
        assertThatThrownBy(() -> admin.createEntry(DEFAULT_TENANT, "gift-wrap", "orders", "alice")).isInstanceOf(KnowledgeRuleException.class);

        KnowledgeVersion version = admin.publish(DEFAULT_TENANT, "gift wrapping and the shipping threshold", "alice", bundled);
        published = version.version();
        assertThat(version.state()).isEqualTo("active");
        assertThat(version.documentCount()).as("18 bundled entries in two languages, plus the new one in two").isEqualTo(38);
        assertThat(admin.activeVersion(DEFAULT_TENANT)).hasValue(published);
        assertThat(admin.version(DEFAULT_TENANT, bundled).orElseThrow().state()).as("retained for rollback").isEqualTo("ready");
        assertThat(admin.entry(DEFAULT_TENANT, "gift-wrap").orElseThrow().revisions()).extracting(r -> r.state()).containsOnly("published");
        assertThat(jdbc.queryForList("SELECT state FROM knowledge_revision WHERE entry_id = 'shipping-cost' AND language = 'en' ORDER BY id",
                String.class)).containsExactly("superseded", "published");

        assertThat(entriesFound("gift wrapping", null)).contains("gift-wrap");
        assertThat(search.search(new SearchQuery(DEFAULT_TENANT, "gift wrapping", 3, 0))).extracting(p -> p.metadata().get("entry_id"))
                .as("the retrieval seam reads the new version").contains("gift-wrap");
        assertThat(search.search(new SearchQuery(DEFAULT_TENANT, "gift wrapping", 3, 0)).getFirst().metadata())
                .containsEntry("corpus_version", published);
        assertThat(entriesFound("gift wrapping", bundled)).as("the old version is still searchable by name").doesNotContain("gift-wrap");
    }

    @Test
    @Order(3)
    @DisplayName("a stale expected version is a conflict and activates nothing; rollback re-activates a retained version")
    void conflictAndRollback() {
        assertThatThrownBy(() -> admin.publish(DEFAULT_TENANT, "late", "bob", bundled)).isInstanceOf(KnowledgeConflictException.class);
        assertThat(admin.activeVersion(DEFAULT_TENANT)).hasValue(published);

        KnowledgeVersion back = admin.rollback(DEFAULT_TENANT, bundled, published, "bob");
        assertThat(back.state()).isEqualTo("active");
        assertThat(admin.activeVersion(DEFAULT_TENANT)).hasValue(bundled);
        assertThat(admin.version(DEFAULT_TENANT, published).orElseThrow().state()).isEqualTo("ready");
        assertThat(entriesFound("gift wrapping", null)).doesNotContain("gift-wrap");
        assertThatThrownBy(() -> admin.rollback(DEFAULT_TENANT, published, "wrong", "bob")).isInstanceOf(KnowledgeConflictException.class);
        assertThatThrownBy(() -> admin.rollback(DEFAULT_TENANT, "nope", null, "bob")).isInstanceOf(KnowledgeRuleException.class);

        admin.rollback(DEFAULT_TENANT, published, bundled, "bob");
        assertThat(admin.activeVersion(DEFAULT_TENANT)).hasValue(published);
    }

    @Test
    @Order(4)
    @DisplayName("retiring an entry leaves it out of the next publication; old versions lose their documents after the retained few")
    void retireAndRetention() {
        admin.retire(DEFAULT_TENANT, "gift-wrap", true, "alice");
        String before = admin.activeVersion(DEFAULT_TENANT).orElseThrow();
        KnowledgeVersion v2 = admin.publish(DEFAULT_TENANT, "without gift wrap", "alice", before);
        assertThat(v2.documentCount()).isEqualTo(36);
        assertThat(entriesFound("gift wrapping", null)).doesNotContain("gift-wrap");
        assertThat(entriesFound("gift wrapping", published)).as("the retained version still has it").contains("gift-wrap");

        admin.publish(DEFAULT_TENANT, "three", "alice", v2.version());
        admin.publish(DEFAULT_TENANT, "four", "alice", null);
        List<KnowledgeVersion> versions = admin.versions(DEFAULT_TENANT);
        assertThat(versions).filteredOn(v -> v.state().equals("active")).hasSize(1);
        assertThat(versions).filteredOn(v -> v.state().equals("ready")).hasSize(JdbcKnowledgeAdmin.RETAINED_VERSIONS);
        assertThat(versions).filteredOn(v -> v.state().equals("retired")).extracting(KnowledgeVersion::version)
                .as("the oldest ready version, the bundled one, was retired").contains(bundled);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE metadata->>'corpus_version' = ?", Integer.class, bundled))
                .as("its documents are gone").isZero();
        assertThatThrownBy(() -> admin.rollback(DEFAULT_TENANT, bundled, null, "bob")).isInstanceOf(KnowledgeRuleException.class);

    }

    @Test
    @Order(5)
    @DisplayName("after many publications that change every entry, a top-k search of the active version still returns k live rows")
    void hnswStillReturnsKAfterChurn() throws Exception {
        // The .NET and Go sides found that an HNSW scan gathers hnsw.ef_search candidates from
        // the graph and only then drops the dead and the filtered ones, so a table full of rows
        // from retired versions, plus the corpus_version filter, returns fewer than k. Measured
        // here on pgvector 0.8.6 with this data: 40 candidates, 26 of them dead, 14 live, 4 of
        // the active version, so a top-8 returned 1 or 2. Two things hid it: the planner
        // prefers a sequential scan on a table this small, which is exact, and a publication
        // re-embeds an unchanged entry to an identical vector, which pgvector keeps as one graph
        // element with several heap ids, so twenty copies cost one candidate. Neither holds
        // for a larger corpus whose entries change. So: the index is forced, every entry
        // changes on every publication, autovacuum is off so nothing leans on the vacuum that
        // happens to run between two publications, and hnsw.iterative_scan on every pooled
        // connection (application.yml) is what brings k back. This is the test that fails
        // if that setting goes missing.
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        jdbc.execute("ALTER DATABASE \"" + database + "\" SET enable_seqscan = off");
        dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
        assertThat(jdbc.queryForObject("SHOW enable_seqscan", String.class)).as("a fresh connection took the setting").isEqualTo("off");
        assertThat(jdbc.queryForObject("SHOW hnsw.iterative_scan", String.class)).as("the pool's init SQL").isEqualTo("strict_order");
        jdbc.execute("ALTER TABLE vector_store SET (autovacuum_enabled = false)");
        for (int i = 0; i < 20; i++) {
            for (KnowledgeEntry entry : admin.entries(DEFAULT_TENANT)) {
                if (entry.retired()) {
                    continue;
                }
                for (KnowledgeRevision revision : entry.revisions()) {
                    if (revision.state().equals("published")) {
                        admin.saveDraft(DEFAULT_TENANT, entry.entryId(), revision.language(), revision.question(),
                                revision.answer() + " (revision " + i + ")", null, "alice");
                    }
                }
            }
            admin.publish(DEFAULT_TENANT, "churn " + i, "alice", null);
        }
        assertThat(jdbc.queryForObject("SELECT n_dead_tup FROM pg_stat_user_tables WHERE relname = 'vector_store'", Long.class))
                .as("the dead rows the scan has to step over are really there").isGreaterThan(300);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT embedding::text) FROM vector_store", Long.class))
                .as("no two live rows share a vector, so none share a graph element")
                .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM vector_store", Long.class));
        String active = admin.activeVersion(DEFAULT_TENANT).orElseThrow();
        String plan = String.join("\n", jdbc.queryForList("EXPLAIN SELECT * FROM vector_store WHERE metadata::jsonb @@ '$.corpus_version == \""
                + active + "\"'::jsonpath ORDER BY embedding <=> (SELECT embedding FROM vector_store LIMIT 1) LIMIT 8", String.class));
        assertThat(plan).as("the search goes through the HNSW index, not an exact scan").contains("Index Scan using spring_ai_vector_index");
        for (String question : List.of("shipping", "退货", "password", "my parcel arrived crushed")) {
            List<Passage> found = admin.preview(new SearchQuery(DEFAULT_TENANT, question, 8, 0), null);
            assertThat(found).as("top-8 for '%s' after churn", question).hasSize(8);
            assertThat(found).allSatisfy(p -> assertThat(p.metadata()).containsEntry("corpus_version", active));
        }
        assertThat(search.search(new SearchQuery(DEFAULT_TENANT, "shipping", 8, 0))).as("the seam sees the same").hasSize(8);
    }

    @Test
    @Order(6)
    @DisplayName("a second tenant publishes its own knowledge; each tenant retrieves only its own, over the seam and through the advisor")
    void tenantsRetrieveOnlyTheirOwn() {
        tenants.create("acme", "Acme");
        assertThat(admin.activeVersion("acme")).as("a new tenant starts with nothing active").isEmpty();
        assertThat(admin.entries("acme")).isEmpty();
        assertThat(admin.version("acme", admin.activeVersion(DEFAULT_TENANT).orElseThrow()))
                .as("another tenant's version is not addressable under this one").isEmpty();
        assertThatThrownBy(() -> admin.preview(new SearchQuery("acme", "anything", 3, 0), null))
                .isInstanceOf(KnowledgeRuleException.class);
        assertThat(search.search(new SearchQuery("acme", "shipping", 3, 0))).as("and retrieves nothing until it publishes").isEmpty();

        // The same entry id as the default tenant's, with different text: ids are per tenant.
        admin.createEntry("acme", "shipping-cost", "orders", "acme-admin");
        admin.saveDraft("acme", "shipping-cost", "en", "What does Acme charge for shipping?",
                "Acme ships every order of unicorn saddles for a flat 9 dollars.", null, "acme-admin");
        KnowledgeVersion acme = admin.publish("acme", "first", "acme-admin", null);
        assertThat(acme.state()).isEqualTo("active");
        assertThat(acme.documentCount()).isEqualTo(1);
        assertThat(admin.activeVersion("acme")).hasValue(acme.version());
        assertThat(admin.versions("acme")).hasSize(1);
        assertThat(admin.versions(DEFAULT_TENANT)).extracting(KnowledgeVersion::version).doesNotContain(acme.version());

        // Over the seam, as a knowledge process answers a chat process.
        List<Passage> acmeFound = search.search(new SearchQuery("acme", "unicorn saddles shipping", 8, 0));
        assertThat(acmeFound).hasSize(1);
        assertThat(acmeFound.getFirst().text()).contains("unicorn saddles");
        assertThat(acmeFound.getFirst().metadata()).containsEntry("corpus_version", acme.version());
        assertThat(search.search(new SearchQuery(DEFAULT_TENANT, "unicorn saddles shipping", 8, 0)))
                .as("the default tenant does not see Acme's text").allSatisfy(p -> assertThat(p.text()).doesNotContain("unicorn"));

        // Directly against the store, the way QuestionAnswerAdvisor asks: the tenant is a filter clause.
        List<org.springframework.ai.document.Document> viaFilter = vectorStore.similaritySearch(SearchRequest.builder()
                .query("unicorn saddles shipping").topK(8).similarityThreshold(0)
                .filterExpression(TenantFilter.expression("acme")).build());
        assertThat(viaFilter).hasSize(1);
        assertThat(vectorStore.similaritySearch(SearchRequest.builder().query("unicorn saddles shipping").topK(8).similarityThreshold(0).build()))
                .as("no tenant clause is the default tenant").allSatisfy(d -> assertThat(d.getText()).doesNotContain("unicorn"));

        // Through the whole advisor chain, as a customer turn: this is the path that must not leak.
        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("Nine dollars."))))));
        assertThat(retrievedBy("acme", "how much is shipping")).containsExactly("shipping-cost");
        assertThat(retrievedVersionsBy("acme", "how much is shipping")).containsOnly(acme.version());
        assertThat(retrievedBy(DEFAULT_TENANT, "how much is shipping")).contains("shipping-cost").hasSizeGreaterThan(1);
        assertThat(retrievedVersionsBy(DEFAULT_TENANT, "how much is shipping"))
                .containsOnly(admin.activeVersion(DEFAULT_TENANT).orElseThrow()).doesNotContain(acme.version());

        // Retention and rollback are per tenant: Acme's publications retire Acme's versions only.
        String defaultActive = admin.activeVersion(DEFAULT_TENANT).orElseThrow();
        for (int i = 0; i < JdbcKnowledgeAdmin.RETAINED_VERSIONS + 1; i++) {
            admin.saveDraft("acme", "shipping-cost", "en", "What does Acme charge for shipping?",
                    "Acme ships unicorn saddles for " + (10 + i) + " dollars.", null, "acme-admin");
            admin.publish("acme", "revision " + i, "acme-admin", null);
        }
        assertThat(admin.versions("acme")).filteredOn(v -> v.state().equals("retired")).extracting(KnowledgeVersion::version)
                .contains(acme.version());
        assertThat(admin.activeVersion(DEFAULT_TENANT)).hasValue(defaultActive);
        assertThat(admin.versions(DEFAULT_TENANT)).filteredOn(v -> v.state().equals("ready")).hasSize(JdbcKnowledgeAdmin.RETAINED_VERSIONS);
        assertThatThrownBy(() -> admin.rollback("acme", defaultActive, null, "acme-admin"))
                .as("Acme cannot activate the default tenant's version").isInstanceOf(KnowledgeRuleException.class);
    }

    private List<String> retrievedBy(String tenant, String question) {
        return retrieval(tenant, question).passages().stream().map(TurnEvent.Passage::entryId).toList();
    }

    private List<String> retrievedVersionsBy(String tenant, String question) {
        return retrieval(tenant, question).passages().stream().map(TurnEvent.Passage::corpusVersion).toList();
    }

    private TurnEvent.Retrieval retrieval(String tenant, String question) {
        List<TurnEvent> events = chatService.stream(tenant, java.util.UUID.randomUUID().toString(), question).collectList().block();
        return events.stream().filter(TurnEvent.Retrieval.class::isInstance).map(TurnEvent.Retrieval.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    @Order(7)
    @DisplayName("a tenant's web page and PDF become draft entries, re-importing replaces them, and a publication makes them retrievable")
    void importsBecomeDrafts() throws Exception {
        tenants.create("importer", "Importer");
        String html = """
                <html><head><title>Acme Help Centre</title></head><body>
                <h1>Returns</h1><p>You can return any item within 30 days of delivery for a full refund. Items must be unused.</p>
                <h1>Shipping</h1><p>Orders over 50 dollars ship free. Below that, standard shipping is 5 dollars and takes 3 to 5 days.</p>
                <h1>Payment</h1><p>We take Visa, Mastercard and PayPal. Declined cards are usually a billing address mismatch.</p>
                </body></html>""";
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/help", exchange -> {
            byte[] body = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/help");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/image", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, 4);
            exchange.getResponseBody().write(new byte[4]);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();

            // A URL the knowledge role may not read is refused now, not recorded as a failed import.
            assertThatThrownBy(() -> admin.importUrl("importer", "ftp://example.com/help", "root")).isInstanceOf(KnowledgeRuleException.class);
            assertThat(admin.imports("importer")).isEmpty();

            KnowledgeImport page = await(admin.importUrl("importer", base + "/moved", "root"));
            assertThat(page.state()).as(page.error()).isEqualTo("done");
            assertThat(page.source()).as("the URL as asked, redirect and all").isEqualTo(base + "/moved");
            assertThat(page.entries()).isGreaterThanOrEqualTo(1);
            List<KnowledgeEntry> entries = admin.entries("importer");
            assertThat(entries).hasSize(page.entries()).allSatisfy(entry -> {
                assertThat(entry.sourceKind()).isEqualTo("url");
                assertThat(entry.source()).isEqualTo(base + "/moved");
                assertThat(entry.category()).isEqualTo(KnowledgeImporter.IMPORTED_CATEGORY);
                assertThat(entry.revisions()).hasSize(1).allSatisfy(r -> {
                    assertThat(r.state()).isEqualTo("draft");
                    assertThat(r.question()).startsWith("Acme Help Centre");
                    assertThat(r.language()).isEqualTo("en");
                    assertThat(r.createdBy()).isEqualTo("root");
                });
            });
            assertThat(entries.getFirst().entryId()).startsWith("url-").endsWith("-1");
            assertThat(String.join(" ", entries.stream().map(e -> e.revisions().getFirst().answer()).toList()))
                    .contains("30 days").contains("PayPal");
            assertThat(search.search(new SearchQuery("importer", "how do I return an item", 3, 0)))
                    .as("drafts are not live").isEmpty();

            // The same page again is the same entries, not more of them.
            KnowledgeImport again = await(admin.importUrl("importer", base + "/moved", "root"));
            assertThat(again.state()).isEqualTo("done");
            assertThat(admin.entries("importer")).hasSize(page.entries())
                    .allSatisfy(e -> assertThat(e.revisions()).as("one draft, replaced").hasSize(1));
            assertThat(admin.imports("importer")).hasSize(2);

            KnowledgeImport notHtml = await(admin.importUrl("importer", base + "/image", "root"));
            assertThat(notHtml.state()).isEqualTo("failed");
            assertThat(notHtml.error()).contains("not an HTML page");

            // A PDF, a page per paragraph.
            byte[] pdf = TestPdf.of(List.of(
                    "Warranty. Every lamp is covered for two years against defects in materials and workmanship.",
                    "Repairs. Send the lamp back with the order number and we repair or replace it within ten working days."));
            assertThatThrownBy(() -> admin.importPdf("importer", "notes.pdf", "hello".getBytes(), "root")).isInstanceOf(KnowledgeRuleException.class);
            KnowledgeImport booklet = await(admin.importPdf("importer", "warranty.pdf", pdf, "root"));
            assertThat(booklet.state()).as(booklet.error()).isEqualTo("done");
            assertThat(booklet.entries()).isGreaterThanOrEqualTo(1);
            List<KnowledgeEntry> fromPdf = admin.entries("importer").stream().filter(e -> "pdf".equals(e.sourceKind())).toList();
            assertThat(fromPdf).hasSize(booklet.entries()).allSatisfy(e -> {
                assertThat(e.source()).isEqualTo("warranty.pdf");
                assertThat(e.revisions().getFirst().question()).startsWith("warranty");
            });
            assertThat(String.join(" ", fromPdf.stream().map(e -> e.revisions().getFirst().answer()).toList()))
                    .contains("two years").contains("ten working days");

            // Published, the imported text is what the tenant's customers retrieve.
            KnowledgeVersion version = admin.publish("importer", "help centre and warranty", "root", null);
            assertThat(version.state()).isEqualTo("active");
            List<Passage> found = search.search(new SearchQuery("importer", "how long is the warranty on a lamp", 3, 0));
            assertThat(found).isNotEmpty();
            assertThat(found.getFirst().text()).contains("two years");
            assertThat(search.search(new SearchQuery(DEFAULT_TENANT, "how long is the warranty on a lamp", 3, 0)))
                    .allSatisfy(p -> assertThat(p.text()).doesNotContain("two years"));
        }
        finally {
            server.stop(0);
        }
    }

    private KnowledgeImport await(KnowledgeImport started) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            KnowledgeImport current = admin.importOf(started.tenantId(), started.id()).orElseThrow();
            if (current.finished()) {
                return current;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("import " + started.id() + " still running after 30 s");
    }
}
