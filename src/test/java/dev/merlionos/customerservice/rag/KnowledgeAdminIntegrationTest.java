package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.PostgresTestcontainer;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    static String bundled;
    static String published;

    private List<String> entriesFound(String question, String version) {
        return admin.preview(new SearchQuery(question, 3, 0), version).stream()
                .map(p -> String.valueOf(p.metadata().get("entry_id"))).toList();
    }

    @Test
    @Order(1)
    @DisplayName("the bundled corpus was adopted as the active version, with its entries as published revisions, without re-embedding")
    void bundledCorpusIsAdopted() {
        bundled = ingestion.bundledVersion();
        assertThat(admin.activeVersion()).hasValue(bundled);
        KnowledgeVersion version = admin.version(bundled).orElseThrow();
        assertThat(version.state()).isEqualTo("active");
        assertThat(version.documentCount()).isEqualTo(36);
        assertThat(version.createdBy()).isEqualTo(KnowledgeBootstrap.BUNDLED_ACTOR);
        assertThat(admin.entries()).hasSize(18).allSatisfy(entry ->
                assertThat(entry.revisions()).extracting(r -> r.state()).containsOnly("published"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store", Integer.class))
                .as("adoption embedded nothing").isEqualTo(36);
        assertThat(search.search(new SearchQuery("运费多少钱", 3, 0))).extracting(p -> p.metadata().get("entry_id"))
                .contains("shipping-cost");
    }

    @Test
    @Order(2)
    @DisplayName("a draft changes nothing a customer sees; a publication builds a new version, activates it, and retrieval follows")
    void draftThenPublish() {
        KnowledgeEntry created = admin.createEntry("gift-wrap", "orders", "alice");
        assertThat(created.revisions()).isEmpty();
        admin.saveDraft("gift-wrap", "en", "Do you offer gift wrapping?",
                "Yes. Choose gift wrapping at checkout for 3 dollars per item; a handwritten note is free.", "new service", "alice");
        admin.saveDraft("gift-wrap", "zh", "可以礼品包装吗？", "可以。结账时选择礼品包装，每件 3 美元，手写贺卡免费。", null, "alice");
        admin.saveDraft("shipping-cost", "en", "How much does shipping cost?",
                "Shipping is free on orders over 50 dollars. Below that, standard shipping is 5 dollars.", "threshold named", "alice");
        assertThat(admin.entry("gift-wrap").orElseThrow().revisions()).extracting(r -> r.state()).containsOnly("draft");

        assertThat(entriesFound("gift wrapping", null)).as("drafts are invisible to retrieval").doesNotContain("gift-wrap");
        assertThatThrownBy(() -> admin.saveDraft("nope", "en", "q", "a", null, "alice")).isInstanceOf(KnowledgeRuleException.class);
        assertThatThrownBy(() -> admin.saveDraft("gift-wrap", "en", "", "a", null, "alice")).isInstanceOf(KnowledgeRuleException.class);
        assertThatThrownBy(() -> admin.createEntry("gift-wrap", "orders", "alice")).isInstanceOf(KnowledgeRuleException.class);

        KnowledgeVersion version = admin.publish("gift wrapping and the shipping threshold", "alice", bundled);
        published = version.version();
        assertThat(version.state()).isEqualTo("active");
        assertThat(version.documentCount()).as("18 bundled entries in two languages, plus the new one in two").isEqualTo(38);
        assertThat(admin.activeVersion()).hasValue(published);
        assertThat(admin.version(bundled).orElseThrow().state()).as("retained for rollback").isEqualTo("ready");
        assertThat(admin.entry("gift-wrap").orElseThrow().revisions()).extracting(r -> r.state()).containsOnly("published");
        assertThat(jdbc.queryForList("SELECT state FROM knowledge_revision WHERE entry_id = 'shipping-cost' AND language = 'en' ORDER BY id",
                String.class)).containsExactly("superseded", "published");

        assertThat(entriesFound("gift wrapping", null)).contains("gift-wrap");
        assertThat(search.search(new SearchQuery("gift wrapping", 3, 0))).extracting(p -> p.metadata().get("entry_id"))
                .as("the retrieval seam reads the new version").contains("gift-wrap");
        assertThat(search.search(new SearchQuery("gift wrapping", 3, 0)).getFirst().metadata())
                .containsEntry("corpus_version", published);
        assertThat(entriesFound("gift wrapping", bundled)).as("the old version is still searchable by name").doesNotContain("gift-wrap");
    }

    @Test
    @Order(3)
    @DisplayName("a stale expected version is a conflict and activates nothing; rollback re-activates a retained version")
    void conflictAndRollback() {
        assertThatThrownBy(() -> admin.publish("late", "bob", bundled)).isInstanceOf(KnowledgeConflictException.class);
        assertThat(admin.activeVersion()).hasValue(published);

        KnowledgeVersion back = admin.rollback(bundled, published, "bob");
        assertThat(back.state()).isEqualTo("active");
        assertThat(admin.activeVersion()).hasValue(bundled);
        assertThat(admin.version(published).orElseThrow().state()).isEqualTo("ready");
        assertThat(entriesFound("gift wrapping", null)).doesNotContain("gift-wrap");
        assertThatThrownBy(() -> admin.rollback(published, "wrong", "bob")).isInstanceOf(KnowledgeConflictException.class);
        assertThatThrownBy(() -> admin.rollback("nope", null, "bob")).isInstanceOf(KnowledgeRuleException.class);

        admin.rollback(published, bundled, "bob");
        assertThat(admin.activeVersion()).hasValue(published);
    }

    @Test
    @Order(4)
    @DisplayName("retiring an entry leaves it out of the next publication; old versions lose their documents after the retained few")
    void retireAndRetention() {
        admin.retire("gift-wrap", true, "alice");
        String before = admin.activeVersion().orElseThrow();
        KnowledgeVersion v2 = admin.publish("without gift wrap", "alice", before);
        assertThat(v2.documentCount()).isEqualTo(36);
        assertThat(entriesFound("gift wrapping", null)).doesNotContain("gift-wrap");
        assertThat(entriesFound("gift wrapping", published)).as("the retained version still has it").contains("gift-wrap");

        admin.publish("three", "alice", v2.version());
        admin.publish("four", "alice", null);
        List<KnowledgeVersion> versions = admin.versions();
        assertThat(versions).filteredOn(v -> v.state().equals("active")).hasSize(1);
        assertThat(versions).filteredOn(v -> v.state().equals("ready")).hasSize(JdbcKnowledgeAdmin.RETAINED_VERSIONS);
        assertThat(versions).filteredOn(v -> v.state().equals("retired")).extracting(KnowledgeVersion::version)
                .as("the oldest ready version, the bundled one, was retired").contains(bundled);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE metadata->>'corpus_version' = ?", Integer.class, bundled))
                .as("its documents are gone").isZero();
        assertThatThrownBy(() -> admin.rollback(bundled, null, "bob")).isInstanceOf(KnowledgeRuleException.class);

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
            for (KnowledgeEntry entry : admin.entries()) {
                if (entry.retired()) {
                    continue;
                }
                for (KnowledgeRevision revision : entry.revisions()) {
                    if (revision.state().equals("published")) {
                        admin.saveDraft(entry.entryId(), revision.language(), revision.question(),
                                revision.answer() + " (revision " + i + ")", null, "alice");
                    }
                }
            }
            admin.publish("churn " + i, "alice", null);
        }
        assertThat(jdbc.queryForObject("SELECT n_dead_tup FROM pg_stat_user_tables WHERE relname = 'vector_store'", Long.class))
                .as("the dead rows the scan has to step over are really there").isGreaterThan(300);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT embedding::text) FROM vector_store", Long.class))
                .as("no two live rows share a vector, so none share a graph element")
                .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM vector_store", Long.class));
        String active = admin.activeVersion().orElseThrow();
        String plan = String.join("\n", jdbc.queryForList("EXPLAIN SELECT * FROM vector_store WHERE metadata::jsonb @@ '$.corpus_version == \""
                + active + "\"'::jsonpath ORDER BY embedding <=> (SELECT embedding FROM vector_store LIMIT 1) LIMIT 8", String.class));
        assertThat(plan).as("the search goes through the HNSW index, not an exact scan").contains("Index Scan using spring_ai_vector_index");
        for (String question : List.of("shipping", "退货", "password", "my parcel arrived crushed")) {
            List<Passage> found = admin.preview(new SearchQuery(question, 8, 0), null);
            assertThat(found).as("top-8 for '%s' after churn", question).hasSize(8);
            assertThat(found).allSatisfy(p -> assertThat(p.metadata()).containsEntry("corpus_version", active));
        }
        assertThat(search.search(new SearchQuery("shipping", 8, 0))).as("the seam sees the same").hasSize(8);
    }
}
