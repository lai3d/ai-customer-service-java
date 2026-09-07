package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.tenancy.Tenant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code staff_account} table. Passwords go in as bcrypt hashes and never come out: the
 * one reader of the hash is {@link StaffUserDetailsService}, through {@link #credential},
 * and every other query leaves the column out.
 *
 * <p>Usernames are normalised to lower case so {@code Alice} and {@code alice} are one
 * account; the primary key makes a second creation a {@link DuplicateStaffAccountException}
 * rather than a race two admins could win together.
 *
 * <p>Changing an account is one transaction shape: lock every enabled admin's row
 * ({@code FOR UPDATE}), decide from what is locked, write. That is what keeps two admins
 * demoting each other at the same moment from leaving nobody who can undo it: the second
 * waits for the first, then sees one admin left and is refused
 * ({@link StaffRuleException}). Your own access is never yours to remove.
 */
public class StaffAccounts {

    /** Short, lower case, no whitespace: something that fits in a log line and an audit row. */
    static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,63}");

    /** Bcrypt hashes the first 72 bytes; the floor is about guessability, not the algorithm. */
    static final int MIN_PASSWORD_LENGTH = 12;

    private static final String ACCOUNT_COLUMNS = "username, role, enabled, created_at, created_by, tenant_id";

    private static final RowMapper<StaffAccount> ACCOUNT = (rs, i) -> new StaffAccount(
            rs.getString("username"), StaffRole.fromValue(rs.getString("role")), rs.getBoolean("enabled"),
            rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"), rs.getString("tenant_id"));

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;

    public StaffAccounts(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** What the login needs and nothing else needs. */
    public record Credential(String username, String passwordHash, StaffRole role, boolean enabled, String tenantId) {
    }

    public Optional<Credential> credential(String username) {
        return jdbc.query("SELECT username, password_hash, role, enabled, tenant_id FROM staff_account WHERE username = ?",
                        (rs, i) -> new Credential(rs.getString("username"), rs.getString("password_hash"),
                                StaffRole.fromValue(rs.getString("role")), rs.getBoolean("enabled"), rs.getString("tenant_id")),
                        normalise(username))
                .stream().findFirst();
    }

    /**
     * Creates an account. Validation failures are {@link IllegalArgumentException}s naming
     * the rule; a taken username is {@link DuplicateStaffAccountException}.
     *
     * @param createdBy the staff username doing the creating, or a marker such as
     *                  {@code seed} for an account nobody created interactively
     */
    public StaffAccount create(String username, String rawPassword, StaffRole role, String createdBy) {
        return create(username, rawPassword, role, role == StaffRole.ADMIN ? null : Tenant.DEFAULT, createdBy);
    }

    /**
     * @param tenantId the tenant the account belongs to, or null for a platform account,
     *                 which must be an admin: platform staff with nothing to administer would
     *                 be an account that can sign in and do nothing
     */
    public StaffAccount create(String username, String rawPassword, StaffRole role, String tenantId, String createdBy) {
        String name = normalise(username);
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "username must be 3-64 characters of a-z, 0-9, '.', '_' or '-', starting with a letter or digit");
        }
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (role == null) {
            throw new IllegalArgumentException("role is required: one of admin, support");
        }
        String tenant = tenantId == null || tenantId.isBlank() ? null : tenantId.strip();
        if (tenant == null && role != StaffRole.ADMIN) {
            throw new IllegalArgumentException("a support account belongs to a tenant; only admins can be platform staff");
        }
        StaffAccount account = new StaffAccount(name, role, true, Instant.now(), createdBy, tenant);
        try {
            jdbc.update("""
                    INSERT INTO staff_account (username, password_hash, role, enabled, created_at, created_by, tenant_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, account.username(), passwordEncoder.encode(rawPassword), role.value(), true,
                    Timestamp.from(account.createdAt()), createdBy, tenant);
        }
        catch (DuplicateKeyException e) {
            throw new DuplicateStaffAccountException(name);
        }
        catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("No tenant '" + tenant + "'");
        }
        return account;
    }

    /**
     * Enables or disables an account. Disabling is refused for the actor's own account and
     * for the last enabled admin; enabling has no rule. The caller ends the account's
     * sessions: a disabled account must not keep a signed-in browser.
     */
    public StaffAccount setEnabled(String username, boolean enabled, String actor) {
        String name = normalise(username);
        return transaction.execute(status -> {
            StaffAccount current = lockedAccount(name);
            if (!enabled) {
                if (name.equals(normalise(actor))) {
                    throw new StaffRuleException(name, "You cannot disable your own account");
                }
                refuseIfLastAdmin(current, "disable", actor);
            }
            jdbc.update("UPDATE staff_account SET enabled = ? WHERE username = ?", enabled, name);
            return new StaffAccount(name, current.role(), enabled, current.createdAt(), current.createdBy(), current.tenantId());
        });
    }

    /**
     * Changes an account's role. Refused for the actor's own account and when it would
     * demote the last enabled admin. The caller ends the account's sessions, because a
     * session carries the authorities it was signed in with.
     */
    public StaffAccount setRole(String username, StaffRole role, String actor) {
        String name = normalise(username);
        if (role == null) {
            throw new IllegalArgumentException("role is required: one of admin, support");
        }
        return transaction.execute(status -> {
            StaffAccount current = lockedAccount(name);
            if (name.equals(normalise(actor))) {
                throw new StaffRuleException(name, "You cannot change your own role");
            }
            if (role != StaffRole.ADMIN) {
                if (current.platform()) {
                    throw new StaffRuleException(name, "Platform staff are admins; give '" + name + "' a tenant instead");
                }
                refuseIfLastAdmin(current, "demote", actor);
            }
            jdbc.update("UPDATE staff_account SET role = ? WHERE username = ?", role.value(), name);
            return new StaffAccount(name, role, current.enabled(), current.createdAt(), current.createdBy(), current.tenantId());
        });
    }

    /** Replaces the password; the old one stops working at once. The caller ends the account's other sessions. */
    public void resetPassword(String username, String rawPassword) {
        String name = normalise(username);
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        String hash = passwordEncoder.encode(rawPassword);
        transaction.executeWithoutResult(status -> {
            lockedAccount(name);
            jdbc.update("UPDATE staff_account SET password_hash = ? WHERE username = ?", hash, name);
        });
    }

    /**
     * Replaces the account's own password, given the current one. A wrong current password
     * and a new password equal to the current one are {@link StaffRuleException}s: the
     * caller is signed in, so neither is an authentication failure, and both are refusals
     * worth a row. The rule on length is creation's. The caller ends the account's other
     * sessions, keeping the one this was done from.
     */
    public void changePassword(String username, String currentPassword, String newPassword) {
        String name = normalise(username);
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        String hash = passwordEncoder.encode(newPassword);
        transaction.executeWithoutResult(status -> {
            lockedAccount(name);
            String current = jdbc.queryForObject("SELECT password_hash FROM staff_account WHERE username = ?", String.class, name);
            if (currentPassword == null || !passwordEncoder.matches(currentPassword, current)) {
                throw new StaffRuleException(name, "The current password is wrong");
            }
            if (currentPassword.equals(newPassword)) {
                throw new StaffRuleException(name, "The new password must differ from the current one");
            }
            jdbc.update("UPDATE staff_account SET password_hash = ? WHERE username = ?", hash, name);
        });
    }

    /** Locks the enabled admins' rows and the target's, so two changes to who is an admin take turns. */
    private StaffAccount lockedAccount(String name) {
        jdbc.queryForList("SELECT username FROM staff_account WHERE (role = 'admin' AND enabled) OR username = ? "
                + "ORDER BY username FOR UPDATE", String.class, name);
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM staff_account WHERE username = ?", ACCOUNT, name)
                .stream().findFirst()
                .orElseThrow(() -> new StaffAccountNotFoundException(name));
    }

    /**
     * The last enabled admin of a scope stays one: of a tenant, among that tenant's accounts;
     * of platform, among platform accounts. A platform admin may remove a tenant's last admin,
     * because the tenant still has platform above it to make another; nobody may remove the
     * last platform admin, because nothing is above that.
     */
    private void refuseIfLastAdmin(StaffAccount account, String verb, String actor) {
        if (account.role() != StaffRole.ADMIN || !account.enabled()) {
            return;
        }
        int admins = jdbc.queryForObject("SELECT count(*) FROM staff_account WHERE role = 'admin' AND enabled "
                + "AND tenant_id IS NOT DISTINCT FROM ?", Integer.class, account.tenantId());
        if (admins > 1) {
            return;
        }
        boolean actorIsPlatform = find(actor).map(StaffAccount::platform).orElse(false);
        if (!account.platform() && actorIsPlatform) {
            return;
        }
        throw new StaffRuleException(account.username(), "Cannot " + verb + " '" + account.username() + "': it is the only enabled admin"
                + (account.platform() ? " of the platform" : " of tenant '" + account.tenantId() + "'"));
    }

    public Optional<StaffAccount> find(String username) {
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM staff_account WHERE username = ?",
                ACCOUNT, normalise(username)).stream().findFirst();
    }

    /** Every account, oldest first. */
    public List<StaffAccount> list() {
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM staff_account ORDER BY created_at, username", ACCOUNT);
    }

    /** One tenant's accounts, oldest first; null for the platform accounts. */
    public List<StaffAccount> list(String tenantId) {
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM staff_account WHERE tenant_id IS NOT DISTINCT FROM ? "
                + "ORDER BY created_at, username", ACCOUNT, tenantId);
    }

    public boolean isEmpty() {
        return jdbc.queryForObject("SELECT count(*) FROM staff_account", Integer.class) == 0;
    }

    static String normalise(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }
}
