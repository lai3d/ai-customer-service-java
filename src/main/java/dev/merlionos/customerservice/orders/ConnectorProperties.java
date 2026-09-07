package dev.merlionos.customerservice.orders;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param secretKey       base64 of 32 bytes; encrypts connector tokens at rest. Unset, tokens are
 *                        stored as given and startup says so
 * @param timeout         connect and read timeout for a call to a tenant's order system
 * @param shopifyBaseUrl  where Shopify is; blank means {@code https://<shop domain>}. Set to a
 *                        local server by the tests and by a demo without a store
 */
@ConfigurationProperties("app.connectors")
public record ConnectorProperties(String secretKey, Duration timeout, String shopifyBaseUrl) {

    public Duration timeoutOrDefault() {
        return timeout == null ? Duration.ofSeconds(8) : timeout;
    }
}
