package dev.merlionos.customerservice.rag;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

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

    private final JdbcTemplate jdbc;

    CorpusReadinessIndicator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * UP when a knowledge version is active and has documents. On a fresh database that is
     * the bundled corpus once the importer has recorded it and the bootstrap adopted it;
     * afterwards it is whatever was last published or rolled back to.
     */
    @Override
    public Health health() {
        return jdbc.query("SELECT a.version, v.document_count FROM knowledge_active a "
                        + "JOIN knowledge_version v ON v.version = a.version WHERE a.id = 1",
                        (rs, i) -> Map.entry(rs.getString(1), rs.getInt(2)))
                .stream().findFirst()
                .filter(active -> active.getValue() > 0)
                .map(active -> Health.up().withDetail("corpusVersion", active.getKey())
                        .withDetail("documents", active.getValue()).build())
                .orElseGet(() -> Health.down().withDetail("reason", "corpus not yet imported").build());
    }
}
