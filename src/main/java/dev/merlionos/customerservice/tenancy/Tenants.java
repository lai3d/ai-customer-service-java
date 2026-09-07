package dev.merlionos.customerservice.tenancy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class Tenants {

    private final JdbcTemplate jdbc;

    public Tenants(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Tenant create(String tenantId, String name) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO tenant (tenant_id, name, enabled, created_at) VALUES (?, ?, true, ?)",
                tenantId, name, Timestamp.from(now));
        // Its knowledge pointer, with nothing active: a new tenant retrieves nothing until it
        // publishes its own knowledge (ADR 002); the bundled corpus is the default tenant's.
        jdbc.update("INSERT INTO knowledge_active (tenant_id) VALUES (?) ON CONFLICT (tenant_id) DO NOTHING", tenantId);
        return new Tenant(tenantId, name, true, now);
    }

    public Optional<Tenant> find(String tenantId) {
        return jdbc.query("SELECT tenant_id, name, enabled, created_at FROM tenant WHERE tenant_id = ?",
                (rs, i) -> new Tenant(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getTimestamp(4).toInstant()),
                tenantId).stream().findFirst();
    }

    public List<Tenant> all() {
        return jdbc.query("SELECT tenant_id, name, enabled, created_at FROM tenant ORDER BY created_at",
                (rs, i) -> new Tenant(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getTimestamp(4).toInstant()));
    }

    public void setEnabled(String tenantId, boolean enabled) {
        jdbc.update("UPDATE tenant SET enabled = ? WHERE tenant_id = ?", enabled, tenantId);
    }
}
