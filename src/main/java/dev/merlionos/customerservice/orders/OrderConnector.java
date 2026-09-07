package dev.merlionos.customerservice.orders;

import java.time.Instant;

/**
 * A tenant's order system. {@code accessToken} is the secret as the connector needs it;
 * {@link #masked()} is what the admin is shown.
 */
public record OrderConnector(String tenantId, String kind, String shopDomain, String baseUrl, String accessToken, String apiVersion,
                             Instant configuredAt, String configuredBy) {

    public static final String SHOPIFY = "shopify";
    public static final String XBOARD = "xboard";

    /** The same record with the token reduced to its last four characters, or absent. */
    public OrderConnector masked() {
        String masked = accessToken == null ? null
                : "****" + (accessToken.length() < 4 ? "" : accessToken.substring(accessToken.length() - 4));
        return new OrderConnector(tenantId, kind, shopDomain, baseUrl, masked, apiVersion, configuredAt, configuredBy);
    }
}
