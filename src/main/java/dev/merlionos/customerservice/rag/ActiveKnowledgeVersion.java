package dev.merlionos.customerservice.rag;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which knowledge version retrieval reads for a tenant: that tenant's row of
 * {@code knowledge_active}, cached for a moment so a burst of turns does not each ask the
 * database, but never for longer than a switch should take to be seen everywhere.
 */
public class ActiveKnowledgeVersion {

    static final long CACHE_MILLIS = 2_000;

    private record Cached(String version, long at) {
    }

    private final JdbcTemplate jdbc;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ActiveKnowledgeVersion(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String tenantId) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(tenantId);
        if (cached == null || now - cached.at() > CACHE_MILLIS) {
            // A tenant with no row, or a row whose version is null, has nothing active; a
            // null element is what findFirst refuses, so it is filtered rather than found.
            String version = jdbc.query("SELECT version FROM knowledge_active WHERE tenant_id = ?", (rs, i) -> rs.getString(1), tenantId)
                    .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
            cached = new Cached(version, now);
            cache.put(tenantId, cached);
        }
        return Optional.ofNullable(cached.version());
    }

    /** Forgets every cached value, so the process that switched sees its own switch at once. */
    public void refresh() {
        cache.clear();
    }
}
