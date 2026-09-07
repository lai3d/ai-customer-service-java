package dev.merlionos.customerservice.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * The {@code admin_audit} table: what staff did in the admin that is not a change to a
 * ticket. See {@code V6__admin_audit.sql} for why views and refusals are the two things here.
 */
public class AdminAudit {

    public enum Action {
        VIEWED_CONVERSATION("viewed_conversation"), REFUSED("refused"), PUBLISHED("published"), ROLLED_BACK("rolled_back"),
        ACCOUNT_DISABLED("account_disabled"), ACCOUNT_ENABLED("account_enabled"), ROLE_CHANGED("role_changed"),
        PASSWORD_RESET("password_reset"), PASSWORD_CHANGED("password_changed"),
        TENANT_CREATED("tenant_created"), TENANT_ENABLED("tenant_enabled"), TENANT_DISABLED("tenant_disabled"),
        KEY_ISSUED("key_issued"), KEY_REVOKED("key_revoked"), IMPORTED("imported"), EVALUATED("evaluated"), CONNECTOR_CHANGED("connector_changed");

        final String value;

        Action(String value) {
            this.value = value;
        }
    }

    public record Entry(long id, String actor, String action, String target, String detail, Instant occurredAt) {
    }

    private static final RowMapper<Entry> ENTRY = (rs, i) -> new Entry(rs.getLong("id"), rs.getString("actor"),
            rs.getString("action"), rs.getString("target"), rs.getString("detail"),
            rs.getTimestamp("occurred_at").toInstant());

    private final JdbcTemplate jdbc;

    public AdminAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, Action action, String target, String detail) {
        jdbc.update("INSERT INTO admin_audit (actor, action, target, detail, occurred_at) VALUES (?, ?, ?, ?, ?)",
                actor, action.value, target, detail, Timestamp.from(Instant.now()));
    }

    /** Everything recorded against one target -- a ticket number or a conversation id -- oldest first. */
    public List<Entry> forTarget(String target) {
        return jdbc.query("SELECT * FROM admin_audit WHERE target = ? ORDER BY id", ENTRY, target);
    }
}
