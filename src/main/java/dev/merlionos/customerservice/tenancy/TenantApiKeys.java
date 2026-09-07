package dev.merlionos.customerservice.tenancy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * API keys, one or more per tenant. A key is {@code cs_} plus 8 characters of id plus 32 of
 * secret, shown once at issue time; the table holds the id and a SHA-256 of the whole key.
 * Resolution is a lookup by id and a constant-time compare of the hash, so neither the key
 * nor anything that yields it is ever at rest.
 *
 * <p>Two kinds. A {@code secret} key is for a server the tenant controls. A {@code widget}
 * key sits in the tenant's web page for anyone to read, so what bounds it is not secrecy but
 * the origins it may be used from; {@link ApiKeyFilter} enforces that and answers CORS only
 * for them. The hashing is the same: a scraped widget key is still no use from another site,
 * and nothing in the table yields the key.
 */
@Component
public class TenantApiKeys {

    public static final String PREFIX = "cs_";
    private static final int ID_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final JdbcTemplate jdbc;

    public TenantApiKeys(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Issues a key for the tenant and returns it -- the only time it is readable. */
    public String issue(String tenantId, String label) {
        String key = PREFIX + random(ID_LENGTH) + random(32);
        store(tenantId, key, label);
        return key;
    }

    /** Stores a caller-chosen key, for the seeded default; the same hashing as {@link #issue}. */
    public void store(String tenantId, String key, String label) {
        jdbc.update("INSERT INTO tenant_api_key (key_id, tenant_id, key_hash, label, created_at) VALUES (?, ?, ?, ?, ?)",
                keyId(key), tenantId, hash(key), label, Timestamp.from(Instant.now()));
    }

    public int revoke(String keyId) {
        return jdbc.update("UPDATE tenant_api_key SET revoked_at = ? WHERE key_id = ? AND revoked_at IS NULL",
                Timestamp.from(Instant.now()), keyId);
    }

    public static final String SECRET = "secret";
    public static final String WIDGET = "widget";

    /** What is shown about a key after it was issued: everything but the key. */
    public record Issued(String keyId, String label, String kind, List<String> origins, Instant createdAt, Instant revokedAt) {
    }

    /** A key that resolved: whose it is, and what it may be used from. */
    public record ApiKey(Tenant tenant, String keyId, String kind, List<String> origins) {

        public boolean isWidget() {
            return WIDGET.equals(kind);
        }

        /** Exact match on {@code scheme://host[:port]}, the host case-insensitively. */
        public boolean allowsOrigin(String origin) {
            if (origin == null) {
                return false;
            }
            String wanted = normaliseOrigin(origin);
            return origins.stream().map(TenantApiKeys::normaliseOrigin).anyMatch(wanted::equals);
        }
    }

    public List<Issued> of(String tenantId) {
        return jdbc.query("SELECT key_id, label, kind, origins, created_at, revoked_at FROM tenant_api_key WHERE tenant_id = ? ORDER BY created_at",
                (rs, i) -> new Issued(rs.getString(1), rs.getString(2), rs.getString(3), origins(rs.getString(4)),
                        rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()),
                tenantId);
    }

    /** Issues a widget key: usable from a browser on these origins and nowhere else. */
    public String issueWidget(String tenantId, String label, List<String> origins) {
        List<String> checked = checkOrigins(origins);
        String key = PREFIX + random(ID_LENGTH) + random(32);
        jdbc.update("INSERT INTO tenant_api_key (key_id, tenant_id, key_hash, label, created_at, kind, origins) VALUES (?, ?, ?, ?, ?, ?, ?)",
                keyId(key), tenantId, hash(key), label, Timestamp.from(Instant.now()), WIDGET, String.join(",", checked));
        return key;
    }

    /** {@code scheme://host[:port]} each, http or https, no path, no credentials; at least one. */
    public static List<String> checkOrigins(List<String> origins) {
        if (origins == null || origins.isEmpty()) {
            throw new IllegalArgumentException("a widget key needs at least one origin it may be used from, e.g. https://shop.example.com");
        }
        List<String> checked = new java.util.ArrayList<>();
        for (String origin : origins) {
            String text = origin == null ? "" : origin.strip();
            java.net.URI uri;
            try {
                uri = java.net.URI.create(text);
            }
            catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("not an origin: " + text);
            }
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean http = "http".equalsIgnoreCase(uri.getScheme());
            if (!(https || http) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || text.contains(",")) {
                throw new IllegalArgumentException("an origin is scheme://host[:port] with no path, e.g. https://shop.example.com; got " + text);
            }
            checked.add(normaliseOrigin(text));
        }
        return checked;
    }

    static String normaliseOrigin(String origin) {
        java.net.URI uri = java.net.URI.create(origin.strip());
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    static List<String> origins(String stored) {
        return stored == null || stored.isBlank() ? List.of() : List.of(stored.split(","));
    }

    public boolean any() {
        return jdbc.queryForObject("SELECT count(*) > 0 FROM tenant_api_key", Boolean.class);
    }

    /** The enabled tenant this key belongs to, or empty for anything else: unknown, revoked, disabled, malformed. */
    public Optional<Tenant> resolve(String key) {
        return resolveKey(key).map(ApiKey::tenant);
    }

    /** The key itself, with its kind and origins, under the same rules as {@link #resolve}. */
    public Optional<ApiKey> resolveKey(String key) {
        if (key == null || !key.startsWith(PREFIX) || key.length() < PREFIX.length() + ID_LENGTH + 1) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT k.key_hash, k.key_id, k.kind, k.origins, t.tenant_id, t.name, t.enabled, t.created_at
                FROM tenant_api_key k JOIN tenant t ON t.tenant_id = k.tenant_id
                WHERE k.key_id = ? AND k.revoked_at IS NULL
                """, (rs, i) -> {
            boolean matches = MessageDigest.isEqual(
                    rs.getString("key_hash").getBytes(StandardCharsets.US_ASCII),
                    hash(key).getBytes(StandardCharsets.US_ASCII));
            return matches && rs.getBoolean("enabled")
                    ? new ApiKey(new Tenant(rs.getString("tenant_id"), rs.getString("name"), true, rs.getTimestamp("created_at").toInstant()),
                            rs.getString("key_id"), rs.getString("kind"), origins(rs.getString("origins")))
                    : null;
        }, keyId(key)).stream().filter(t -> t != null).findFirst();
    }

    public static String keyId(String key) {
        return key.substring(PREFIX.length(), PREFIX.length() + ID_LENGTH);
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

}
