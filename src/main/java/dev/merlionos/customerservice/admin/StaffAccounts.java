package dev.merlionos.customerservice.admin;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;

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
 */
public class StaffAccounts {

    /** Short, lower case, no whitespace: something that fits in a log line and an audit row. */
    static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,63}");

    /** Bcrypt hashes the first 72 bytes; the floor is about guessability, not the algorithm. */
    static final int MIN_PASSWORD_LENGTH = 12;

    private static final RowMapper<StaffAccount> ACCOUNT = (rs, i) -> new StaffAccount(
            rs.getString("username"), StaffRole.fromValue(rs.getString("role")), rs.getBoolean("enabled"),
            rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"));

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public StaffAccounts(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    /** What the login needs and nothing else needs. */
    public record Credential(String username, String passwordHash, StaffRole role, boolean enabled) {
    }

    public Optional<Credential> credential(String username) {
        return jdbc.query("SELECT username, password_hash, role, enabled FROM staff_account WHERE username = ?",
                        (rs, i) -> new Credential(rs.getString("username"), rs.getString("password_hash"),
                                StaffRole.fromValue(rs.getString("role")), rs.getBoolean("enabled")),
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
        StaffAccount account = new StaffAccount(name, role, true, Instant.now(), createdBy);
        try {
            jdbc.update("""
                    INSERT INTO staff_account (username, password_hash, role, enabled, created_at, created_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, account.username(), passwordEncoder.encode(rawPassword), role.value(), true,
                    Timestamp.from(account.createdAt()), createdBy);
        }
        catch (DuplicateKeyException e) {
            throw new DuplicateStaffAccountException(name);
        }
        return account;
    }

    public Optional<StaffAccount> find(String username) {
        return jdbc.query("SELECT username, role, enabled, created_at, created_by FROM staff_account WHERE username = ?",
                ACCOUNT, normalise(username)).stream().findFirst();
    }

    /** Every account, oldest first. */
    public List<StaffAccount> list() {
        return jdbc.query("SELECT username, role, enabled, created_at, created_by FROM staff_account "
                + "ORDER BY created_at, username", ACCOUNT);
    }

    public boolean isEmpty() {
        return jdbc.queryForObject("SELECT count(*) FROM staff_account", Integer.class) == 0;
    }

    static String normalise(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }
}
