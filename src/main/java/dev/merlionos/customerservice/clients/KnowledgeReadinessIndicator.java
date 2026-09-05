package dev.merlionos.customerservice.clients;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * A chat process is ready when the knowledge service it retrieves through is: readiness
 * inherits across the seam, so a fresh distributed install with no corpus imported yet reports
 * not-ready at the public Service rather than answering ungrounded. The knowledge service's
 * own readiness already includes its corpus indicator.
 */
public class KnowledgeReadinessIndicator implements HealthIndicator {

    private final RestClient client;

    public KnowledgeReadinessIndicator(RestClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            client.get().uri("/actuator/health/readiness").retrieve().toBodilessEntity();
            return Health.up().build();
        }
        catch (RestClientException e) {
            return Health.down().withDetail("reason", "knowledge service not ready: " + e.getMessage()).build();
        }
    }
}
