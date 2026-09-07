package dev.merlionos.customerservice.orders;

import java.time.Instant;

/**
 * A tenant's order system. {@code accessToken} is the secret as the connector needs it;
 * {@link #masked()} is what the admin is shown.
 */
public record OrderConnector(String tenantId, String kind, String shopDomain, String accessToken, String apiVersion,
                             Instant configuredAt, String configuredBy) {

    public static final String SHOPIFY = "shopify";

    /** The same record with the token reduced to its last four characters. */
    public OrderConnector masked() {
        String tail = accessToken == null || accessToken.length() < 4 ? "" : accessToken.substring(accessToken.length() - 4);
        return new OrderConnector(tenantId, kind, shopDomain, "****" + tail, apiVersion, configuredAt, configuredBy);
    }
}
