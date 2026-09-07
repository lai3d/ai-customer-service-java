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

    /** What is shown about a key after it was issued: everything but the key. */
    public record Issued(String keyId, String label, Instant createdAt, Instant revokedAt) {
    }

    public List<Issued> of(String tenantId) {
        return jdbc.query("SELECT key_id, label, created_at, revoked_at FROM tenant_api_key WHERE tenant_id = ? ORDER BY created_at",
                (rs, i) -> new Issued(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()),
                tenantId);
    }

    public boolean any() {
        return jdbc.queryForObject("SELECT count(*) > 0 FROM tenant_api_key", Boolean.class);
    }

    /** The enabled tenant this key belongs to, or empty for anything else: unknown, revoked, disabled, malformed. */
    public Optional<Tenant> resolve(String key) {
        if (key == null || !key.startsWith(PREFIX) || key.length() < PREFIX.length() + ID_LENGTH + 1) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT k.key_hash, t.tenant_id, t.name, t.enabled, t.created_at
                FROM tenant_api_key k JOIN tenant t ON t.tenant_id = k.tenant_id
                WHERE k.key_id = ? AND k.revoked_at IS NULL
                """, (rs, i) -> {
            boolean matches = MessageDigest.isEqual(
                    rs.getString("key_hash").getBytes(StandardCharsets.US_ASCII),
                    hash(key).getBytes(StandardCharsets.US_ASCII));
            return matches && rs.getBoolean("enabled")
                    ? new Tenant(rs.getString("tenant_id"), rs.getString("name"), true, rs.getTimestamp("created_at").toInstant())
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
