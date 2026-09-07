package dev.merlionos.customerservice.orders;

import dev.merlionos.customerservice.internal.PublicUrlGuard;
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
    private final PublicUrlGuard guard;

    public OrderConnectors(JdbcTemplate jdbc, ConnectorSecrets secrets, ConnectorProperties properties) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.guard = new PublicUrlGuard(properties.allowPrivateNetworksOrDefault());
    }

    public Optional<OrderConnector> of(String tenantId) {
        return jdbc.query("SELECT * FROM order_connector WHERE tenant_id = ?", (rs, i) -> new OrderConnector(
                rs.getString("tenant_id"), rs.getString("kind"), rs.getString("shop_domain"), rs.getString("base_url"),
                rs.getString("access_token") == null ? null : secrets.open(rs.getString("access_token")),
                rs.getString("api_version"), rs.getTimestamp("configured_at").toInstant(), rs.getString("configured_by")), tenantId)
                .stream().findFirst();
    }

    /**
     * An Xboard panel: its base URL, public (the panel is what the tenant's customers reach;
     * {@link PublicUrlGuard} refuses anything inside the deployment), and an optional admin
     * token for what the customer's own token cannot do, kept for later.
     */
    public OrderConnector configureXboard(String tenantId, String baseUrl, String adminToken, String actor) {
        String url = baseUrl == null ? "" : baseUrl.strip().replaceFirst("/+$", "");
        java.net.URI checked = guard.check(url);
        if (checked.getPath() != null && !checked.getPath().isEmpty() && !checked.getPath().equals("/")) {
            throw new IllegalArgumentException("a panel URL is its origin, https://panel.example.com, with no path");
        }
        String token = adminToken == null || adminToken.isBlank() ? null : secrets.seal(adminToken.strip());
        jdbc.update("""
                INSERT INTO order_connector (tenant_id, kind, shop_domain, base_url, access_token, api_version, configured_at, configured_by)
                VALUES (?, 'xboard', NULL, ?, ?, 'v1', ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET kind = EXCLUDED.kind, shop_domain = NULL, base_url = EXCLUDED.base_url,
                    access_token = EXCLUDED.access_token, api_version = EXCLUDED.api_version,
                    configured_at = EXCLUDED.configured_at, configured_by = EXCLUDED.configured_by
                """, tenantId, checked.getScheme().toLowerCase(Locale.ROOT) + "://" + checked.getAuthority().toLowerCase(Locale.ROOT),
                token, Timestamp.from(Instant.now()), actor);
        return of(tenantId).orElseThrow();
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
                INSERT INTO order_connector (tenant_id, kind, shop_domain, base_url, access_token, api_version, configured_at, configured_by)
                VALUES (?, 'shopify', ?, NULL, ?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET kind = EXCLUDED.kind, shop_domain = EXCLUDED.shop_domain, base_url = NULL,
                    access_token = EXCLUDED.access_token, api_version = EXCLUDED.api_version,
                    configured_at = EXCLUDED.configured_at, configured_by = EXCLUDED.configured_by
                """, tenantId, domain, secrets.seal(accessToken.strip()), version, Timestamp.from(Instant.now()), actor);
        return of(tenantId).orElseThrow();
    }

    public boolean remove(String tenantId) {
        return jdbc.update("DELETE FROM order_connector WHERE tenant_id = ?", tenantId) == 1;
    }
}
