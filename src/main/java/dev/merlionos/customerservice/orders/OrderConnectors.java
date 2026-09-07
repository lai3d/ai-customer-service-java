package dev.merlionos.customerservice.orders;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** One order connector per tenant, in {@code order_connector}. */
@Component
public class OrderConnectors {

    /** A Shopify store's admin host; the Admin API lives nowhere else, which is also what keeps this from being a fetch of anything. */
    static final Pattern SHOP_DOMAIN = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}\\.myshopify\\.com");
    static final Pattern API_VERSION = Pattern.compile("\\d{4}-(01|04|07|10)");
    public static final String DEFAULT_API_VERSION = "2025-07";

    private final JdbcTemplate jdbc;
    private final ConnectorSecrets secrets;

    public OrderConnectors(JdbcTemplate jdbc, ConnectorSecrets secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
    }

    public Optional<OrderConnector> of(String tenantId) {
        return jdbc.query("SELECT * FROM order_connector WHERE tenant_id = ?", (rs, i) -> new OrderConnector(
                rs.getString("tenant_id"), rs.getString("kind"), rs.getString("shop_domain"), secrets.open(rs.getString("access_token")),
                rs.getString("api_version"), rs.getTimestamp("configured_at").toInstant(), rs.getString("configured_by")), tenantId)
                .stream().findFirst();
    }

    /** Validated and stored; replaces whatever the tenant had. */
    public OrderConnector configureShopify(String tenantId, String shopDomain, String accessToken, String apiVersion, String actor) {
        String domain = shopDomain == null ? "" : shopDomain.strip().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("/.*$", "");
        if (!SHOP_DOMAIN.matcher(domain).matches()) {
            throw new IllegalArgumentException("a Shopify shop domain looks like my-store.myshopify.com");
        }
        if (accessToken == null || accessToken.isBlank() || accessToken.strip().length() < 16) {
            throw new IllegalArgumentException("an Admin API access token is required (a custom app's shpat_... token)");
        }
        String version = apiVersion == null || apiVersion.isBlank() ? DEFAULT_API_VERSION : apiVersion.strip();
        if (!API_VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("an API version looks like 2025-07");
        }
        jdbc.update("""
                INSERT INTO order_connector (tenant_id, kind, shop_domain, access_token, api_version, configured_at, configured_by)
                VALUES (?, 'shopify', ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET kind = EXCLUDED.kind, shop_domain = EXCLUDED.shop_domain,
                    access_token = EXCLUDED.access_token, api_version = EXCLUDED.api_version,
                    configured_at = EXCLUDED.configured_at, configured_by = EXCLUDED.configured_by
                """, tenantId, domain, secrets.seal(accessToken.strip()), version, Timestamp.from(Instant.now()), actor);
        return of(tenantId).orElseThrow();
    }

    public boolean remove(String tenantId) {
        return jdbc.update("DELETE FROM order_connector WHERE tenant_id = ?", tenantId) == 1;
    }
}
