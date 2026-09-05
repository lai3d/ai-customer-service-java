package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.ImportMode;
import dev.merlionos.customerservice.rag.api.RagProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Decides whether, and under what protection, the bundled corpus is written.
 *
 * <p>Two things this guards that re-embedding on every start did not. First, several replicas
 * of the knowledge role starting together, or one restarting on a reschedule, no longer each
 * spend their first seconds re-embedding a corpus that is already there: the version the
 * import completed is recorded in {@code corpus_import}, and a recorded version is skipped.
 * Second, a fresh database has no previous corpus to keep serving while an import runs -- the
 * write-first ordering in {@link FaqIngestionService} covers a re-import, not a first one -- so
 * readiness ({@link CorpusReadinessIndicator}) reads the same table and stays DOWN until the
 * row exists.
 *
 * <p>The import runs inside one transaction holding a Postgres transaction-scoped advisory
 * lock. The lock serialises importers across replicas; the transaction means a reader sees
 * the corpus rows and the status row appear together, or not at all. A crashed importer
 * releases the lock and rolls back with its connection, so nothing is left half-imported and
 * nothing is marked ready that is not.
 *
 * <p>The atomic staging-and-switch of a <em>new</em> version while an old one is being served
 * is deliberately not this: ADR 001 defers it until a corpus has to change without a
 * maintenance window. What this does is make a first install and a same-version restart
 * correct.
 */
@Service
public class CorpusImporter {

    private static final Logger log = LoggerFactory.getLogger(CorpusImporter.class);

    /** Advisory-lock namespace is per database; distinct from the schema-initialisation key. */
    static final long IMPORT_LOCK_KEY = 8_524_101_020_260_906L;

    /** What one call did. */
    public enum Outcome { IMPORTED, ALREADY_PRESENT }

    private final FaqIngestionService ingestion;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RagProperties properties;
    private final ConfigurableApplicationContext context;
    private final ObjectProvider<ExitHandler> exitHandler;
    private final ObjectProvider<KnowledgeBootstrap> bootstrap;
    private final Timer imported;
    private final Timer alreadyPresent;
    /** Memoised: reading the version parses the bundled corpus, fine once and not per scrape. */
    private volatile String bundledVersion;

    CorpusImporter(FaqIngestionService ingestion, JdbcTemplate jdbc,
                   PlatformTransactionManager transactionManager, RagProperties properties,
                   ConfigurableApplicationContext context, ObjectProvider<ExitHandler> exitHandler,
                   ObjectProvider<KnowledgeBootstrap> bootstrap, MeterRegistry meterRegistry) {
        this.ingestion = ingestion;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.context = context;
        this.exitHandler = exitHandler;
        this.bootstrap = bootstrap;
        // Both outcomes registered up front so the timer exists at zero, the way the tool
        // counters do; a start that skipped the import is a data point, not an absence.
        this.imported = importTimer(meterRegistry, "imported");
        this.alreadyPresent = importTimer(meterRegistry, "already_present");
        // Read from the database on each scrape rather than remembered from this process's
        // own import, because with several replicas the one that imported is usually not the
        // one being scraped. It is the query readiness already runs, at the scrape interval.
        Gauge.builder("corpus.documents", this, CorpusImporter::documentCount)
                .description("Documents recorded for the bundled corpus version; 0 until it is imported")
                .strongReference(true)
                .register(meterRegistry);
    }

    private static Timer importTimer(MeterRegistry registry, String outcome) {
        return Timer.builder("corpus.import")
                .description("Time a start spent under the import lock, by what it found there")
                .tag("outcome", outcome)
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(0)
    void onReady() {
        ImportMode mode = properties.importMode() == null ? ImportMode.STARTUP : properties.importMode();
        switch (mode) {
            case OFF -> log.info("Corpus import off (app.rag.import-mode=off); serving what the database holds");
            case STARTUP -> importIfMissing();
            case ONCE -> {
                int code;
                try {
                    importIfMissing();
                    code = 0;
                }
                catch (RuntimeException e) {
                    log.error("Corpus import failed; exiting non-zero", e);
                    code = 1;
                }
                int exitCode = code;
                // Off the event thread: closing the context from inside its own event
                // publication is the kind of thing that works until it does not.
                // No ExitHandler bean means a real process: end the JVM with the code.
                ExitHandler exit = exitHandler.getIfAvailable(() -> System::exit);
                Thread.ofVirtual().name("corpus-import-exit").start(() ->
                        exit.exit(SpringApplication.exit(context, () -> exitCode)));
            }
        }
    }

    /**
     * Imports the bundled corpus unless its version is already recorded as complete.
     * Serialised across processes by an advisory lock held for the transaction.
     */
    public Outcome importIfMissing() {
        String version = bundledVersion();
        long started = System.nanoTime();

        Outcome outcome = transaction.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, IMPORT_LOCK_KEY);

            if (recordedDocumentCount(version).isPresent()) {
                log.info("Corpus version {} already imported; skipping", version);
                return Outcome.ALREADY_PRESENT;
            }

            int written = ingestion.ingest();
            jdbc.update("INSERT INTO corpus_import (corpus_version, document_count, completed_at) VALUES (?, ?, ?)",
                    version, written, Timestamp.from(Instant.now()));
            return Outcome.IMPORTED;
        });
        // The lock wait is included: a replica that waited on another's import was not ready
        // for that long, which is what the timer is for. A failed import records nothing; it
        // propagates, and in `once` mode ends the process non-zero.
        (outcome == Outcome.IMPORTED ? imported : alreadyPresent)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        // The bundled corpus becomes the first managed knowledge version the moment it is in
        // the database, whichever path put it there; readiness reads the active version.
        bootstrap.ifAvailable(KnowledgeBootstrap::adoptBundledIfPresent);
        return outcome;
    }

    /** What the {@code corpus.documents} gauge reports. */
    int documentCount() {
        // A database that cannot be reached makes the gauge NaN: Micrometer catches the
        // exception, logs it, and reports "unknown", which is more honest than 0.
        return recordedDocumentCount(bundledVersion()).orElse(0);
    }

    private String bundledVersion() {
        String version = bundledVersion;
        if (version == null) {
            version = ingestion.bundledVersion();
            bundledVersion = version;
        }
        return version;
    }

    /** The document count recorded for a version, or empty if that version was never completed. */
    public Optional<Integer> recordedDocumentCount(String version) {
        return jdbc.query("SELECT document_count FROM corpus_import WHERE corpus_version = ?",
                        (rs, i) -> rs.getInt(1), version)
                .stream().findFirst();
    }
}
