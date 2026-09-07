package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Adopts the bundled corpus as the first managed knowledge version, once, without touching
 * {@code faq.json} or re-embedding anything: the documents {@link CorpusImporter} wrote
 * already carry {@code corpus_version = <bundled version>}, so a version row with that id,
 * the entries and revisions read from the bundled file, and the active pointer are all the
 * adoption is. Runs after the importer on every start of a knowledge role and does nothing
 * the second time; serialised across replicas by the same advisory lock the importer uses.
 *
 * <p>A database that has managed versions but not this bundled version -- an upgrade that
 * ships a newer bundled corpus -- gets the new bundled version as {@code ready}, not active:
 * what operators published stays live, and the new bundled text is there to publish from.
 */
@Component
public class KnowledgeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBootstrap.class);
    static final String BUNDLED_ACTOR = "bundled";

    private final FaqIngestionService ingestion;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    KnowledgeBootstrap(FaqIngestionService ingestion, JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.ingestion = ingestion;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * For {@code import-mode=off}, where the importer never runs and the database is expected
     * to hold the corpus already. The other modes adopt from inside the import.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    void onReady() {
        adoptBundledIfPresent();
    }

    /** @return whether this call adopted the bundled corpus */
    public boolean adoptBundledIfPresent() {
        String version = ingestion.bundledVersion();
        return Boolean.TRUE.equals(transaction.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, CorpusImporter.IMPORT_LOCK_KEY);
            if (jdbc.queryForObject("SELECT count(*) FROM knowledge_version WHERE version = ?", Integer.class, version) > 0) {
                return false;
            }
            Integer documents = jdbc.query("SELECT document_count FROM corpus_import WHERE corpus_version = ?",
                    (rs, i) -> rs.getInt(1), version).stream().findFirst().orElse(null);
            if (documents == null) {
                log.info("Bundled corpus {} not imported yet; nothing to adopt", version);
                return false;
            }
            // The bundled corpus is the default tenant's; every other tenant starts empty (ADR 002).
            String tenant = SearchQuery.DEFAULT_TENANT;
            boolean nothingActive = jdbc.queryForObject("SELECT version IS NULL FROM knowledge_active WHERE tenant_id = ?", Boolean.class, tenant);
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("INSERT INTO knowledge_version (version, tenant_id, state, document_count, created_at, created_by, activated_at, note) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    version, tenant, nothingActive ? "active" : "ready", documents, now, BUNDLED_ACTOR,
                    nothingActive ? now : null, "the bundled corpus, adopted at startup");
            for (FaqEntry entry : ingestion.bundledEntries()) {
                jdbc.update("INSERT INTO knowledge_entry (tenant_id, entry_id, category, created_at, created_by) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, entry_id) DO NOTHING", tenant, entry.id(), entry.category(), now, BUNDLED_ACTOR);
                for (LocalizedFaq localized : entry.localized()) {
                    // Only where there is no managed text yet: an upgrade never overwrites what
                    // operators published or are drafting.
                    boolean managed = jdbc.queryForObject("SELECT count(*) FROM knowledge_revision WHERE tenant_id = ? AND entry_id = ? AND language = ?",
                            Integer.class, tenant, entry.id(), localized.language()) > 0;
                    Long revisionId;
                    if (managed) {
                        revisionId = jdbc.query("SELECT id FROM knowledge_revision WHERE tenant_id = ? AND entry_id = ? AND language = ? "
                                + "AND state = 'published'", (rs, i) -> rs.getLong(1), tenant, entry.id(), localized.language())
                                .stream().findFirst().orElse(null);
                    }
                    else {
                        revisionId = jdbc.queryForObject("INSERT INTO knowledge_revision (tenant_id, entry_id, language, question, answer, state, "
                                        + "created_at, created_by, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id", Long.class,
                                tenant, entry.id(), localized.language(), localized.question(), localized.answer(),
                                nothingActive ? "published" : "superseded", now, BUNDLED_ACTOR, "bundled corpus " + version);
                    }
                    if (revisionId != null) {
                        jdbc.update("INSERT INTO knowledge_version_document (version, revision_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                                version, revisionId);
                    }
                }
            }
            if (nothingActive) {
                jdbc.update("UPDATE knowledge_active SET version = ?, switched_at = ?, switched_by = ? WHERE tenant_id = ?",
                        version, now, BUNDLED_ACTOR, tenant);
            }
            log.info("Adopted the bundled corpus {} as a managed knowledge version ({})", version,
                    nothingActive ? "active" : "ready, something else is active");
            return true;
        }));
    }
}
