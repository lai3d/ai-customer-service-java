package dev.merlionos.customerservice.clients;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Where a {@code chat} process finds the other roles. Required, and checked at startup, only
 * when the process is exactly {@code chat}; an {@code all} process calls them in-process.
 *
 * @param knowledge the knowledge service
 * @param ticket    the ticket service
 * @param timeout   connect and read timeout for every internal call. Separate from
 *                  {@code spring.http.client.read-timeout}, which is sized for a model that
 *                  legitimately takes two minutes; a tool that waits that long holds the model
 *                  call, the SSE stream and the customer with it
 */
@ConfigurationProperties("app.services")
public record ServicesProperties(Service knowledge, Service ticket, Duration timeout) {

    public record Service(String url) {
    }

    public Duration timeoutOrDefault() {
        return timeout == null ? Duration.ofSeconds(5) : timeout;
    }
}
