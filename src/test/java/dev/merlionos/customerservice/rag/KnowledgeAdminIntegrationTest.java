package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.rag.api.KnowledgeAdmin;
import dev.merlionos.customerservice.rag.api.KnowledgeConflictException;
import dev.merlionos.customerservice.rag.api.KnowledgeEntry;
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
}
