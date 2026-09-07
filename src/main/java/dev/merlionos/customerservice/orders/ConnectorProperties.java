package dev.merlionos.customerservice.orders;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param secretKey       base64 of 32 bytes; encrypts connector tokens at rest. Unset, tokens are
 *                        stored as given and startup says so
 * @param timeout         connect and read timeout for a call to a tenant's order system
 * @param shopifyBaseUrl  where Shopify is; blank means {@code https://<shop domain>}. Set to a
 *                        local server by the tests and by a demo without a store
 * @param xboardBaseUrl   where every tenant's Xboard panel is; blank means the connector's own
 *                        base URL. A local stand-in for tests and a demo
 * @param allowPrivateNetworks lets a panel URL point inside the deployment: tests and a laptop
 */
@ConfigurationProperties("app.connectors")
public record ConnectorProperties(String secretKey, Duration timeout, String shopifyBaseUrl, String xboardBaseUrl,
                                  Boolean allowPrivateNetworks) {

    public boolean allowPrivateNetworksOrDefault() {
        return Boolean.TRUE.equals(allowPrivateNetworks);
    }

    public Duration timeoutOrDefault() {
        return timeout == null ? Duration.ofSeconds(8) : timeout;
    }
}
