package dev.merlionos.customerservice.rag;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness, as far as retrieval is concerned: is the bundled corpus version in the database?
 *
 * <p>Wired into the readiness group in {@code application.yml}. Before this, a fresh install
 * reported ready the moment the web server was up, and every question asked before the
 * importer finished was answered with no passages at all -- a grounded assistant with nothing
 * to ground on. Liveness is untouched: a process with no corpus is not broken, it is waiting.
 */
@Component("corpus")
class CorpusReadinessIndicator implements HealthIndicator {

    private final CorpusImporter importer;
    private final FaqIngestionService ingestion;

    CorpusReadinessIndicator(CorpusImporter importer, FaqIngestionService ingestion) {
        this.importer = importer;
        this.ingestion = ingestion;
    }

    @Override
    public Health health() {
        String version = ingestion.bundledVersion();
        return importer.recordedDocumentCount(version)
                .map(count -> Health.up().withDetail("corpusVersion", version).withDetail("documents", count).build())
                .orElseGet(() -> Health.down().withDetail("corpusVersion", version)
                        .withDetail("reason", "corpus not yet imported").build());
    }
}
