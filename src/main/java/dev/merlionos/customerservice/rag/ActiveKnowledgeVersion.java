package dev.merlionos.customerservice.rag;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/**
 * Which knowledge version retrieval reads: the one row of {@code knowledge_active}, cached
 * for a moment so a burst of turns does not each ask the database, but never for longer
 * than a switch should take to be seen everywhere.
 */
public class ActiveKnowledgeVersion {

    static final long CACHE_MILLIS = 2_000;

    private final JdbcTemplate jdbc;
    private volatile String cached;
    private volatile long cachedAt;

    public ActiveKnowledgeVersion(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > CACHE_MILLIS) {
            // The row always exists; its version is null until something is active, and a
            // null element is what findFirst refuses, so it is filtered rather than found.
            cached = jdbc.query("SELECT version FROM knowledge_active WHERE id = 1", (rs, i) -> rs.getString(1))
                    .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
            cachedAt = now;
        }
        return Optional.ofNullable(cached);
    }

    /** Forgets the cached value, so the process that switched sees its own switch at once. */
    public void refresh() {
        cachedAt = 0;
    }
}
