package dev.merlionos.customerservice.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param seed the first admin account, created at startup when -- and only when -- the
 *             {@code staff_account} table is empty. Both values or neither; see
 *             {@link StaffSeeder}.
 */
@ConfigurationProperties("app.admin")
public record AdminProperties(Seed seed) {

    public AdminProperties {
        seed = seed == null ? new Seed(null, null) : seed;
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
