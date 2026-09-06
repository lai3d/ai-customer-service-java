package dev.merlionos.customerservice.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * @param seed               the first admin account, created at startup when -- and only
 *                           when -- the {@code staff_account} table is empty. Both values or
 *                           neither; see {@link StaffSeeder}.
 * @param sessionMaxLifetime how long a staff session may live from the moment it was signed
 *                           in, however busy it is. The idle timeout is
 *                           {@code spring.session.timeout}; this is the other bound. See
 *                           {@link StaffSessionPolicy}.
 * @param sessionLimit       how many sessions one account may hold at once; signing in past
 *                           it ends the least recently used. At least 1.
 */
@ConfigurationProperties("app.admin")
public record AdminProperties(Seed seed, Duration sessionMaxLifetime, Integer sessionLimit) {

    static final Duration DEFAULT_SESSION_MAX_LIFETIME = Duration.ofHours(12);
    static final int DEFAULT_SESSION_LIMIT = 3;

    @ConstructorBinding
    public AdminProperties {
        seed = seed == null ? new Seed(null, null) : seed;
        sessionMaxLifetime = sessionMaxLifetime == null ? DEFAULT_SESSION_MAX_LIFETIME : sessionMaxLifetime;
        sessionLimit = sessionLimit == null ? DEFAULT_SESSION_LIMIT : sessionLimit;
        if (sessionMaxLifetime.isZero() || sessionMaxLifetime.isNegative()) {
            throw new IllegalArgumentException("app.admin.session-max-lifetime (ADMIN_SESSION_MAX_LIFETIME) must be "
                    + "a positive duration, e.g. 12h; got " + sessionMaxLifetime);
        }
        if (sessionLimit < 1) {
            throw new IllegalArgumentException("app.admin.session-limit (ADMIN_SESSION_LIMIT) must be at least 1; got "
                    + sessionLimit);
        }
    }

    /** A seed and the default bounds; what a test that only cares about the seed constructs. */
    public AdminProperties(Seed seed) {
        this(seed, null, null);
    }

    public record Seed(String username, String password) {

        public boolean configured() {
            return has(username) || has(password);
        }

        public boolean complete() {
            return has(username) && has(password);
        }

        private static boolean has(String value) {
            return value != null && !value.isBlank();
        }
    }
}
