package dev.merlionos.customerservice.tenancy;

import dev.merlionos.customerservice.MigratedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The key table against a real Postgres, no Spring context. */
class TenantApiKeysTest {

    static MigratedPostgres postgres;
    static Tenants tenants;
    static TenantApiKeys keys;

    @BeforeAll
    static void start() {
        postgres = MigratedPostgres.start();
        tenants = new Tenants(postgres.jdbc);
        keys = new TenantApiKeys(postgres.jdbc);
    }

    @AfterAll
    static void stop() {
        postgres.close();
    }

    @Test
    @DisplayName("the migration adopted the existing installation as the default tenant, and never issued a key for it")
    void defaultTenantExists() {
        assertThat(tenants.find(Tenant.DEFAULT)).map(Tenant::enabled).contains(true);
        assertThat(postgres.jdbc.queryForObject(
                "SELECT count(*) FROM tenant_api_key WHERE tenant_id = ?", Integer.class, Tenant.DEFAULT))
                .as("a key is seeded by the application from configuration, not by the schema")
                .isZero();
    }

    @Test
    @DisplayName("an issued key resolves to its tenant, and only its hash is stored")
    void issuedKeyResolves() {
        tenants.create("acme", "Acme");
        String key = keys.issue("acme", "first");

        assertThat(key).startsWith(TenantApiKeys.PREFIX).hasSize(3 + 8 + 32);
        assertThat(keys.resolve(key)).map(Tenant::id).contains("acme");
        String stored = postgres.jdbc.queryForObject(
                "SELECT key_hash FROM tenant_api_key WHERE key_id = ?", String.class, TenantApiKeys.keyId(key));
        assertThat(stored).hasSize(64).isNotEqualTo(key).doesNotContain(key.substring(11));
    }

    @Test
    @DisplayName("a wrong secret under a real key id, a revoked key and a disabled tenant all resolve to nothing")
    void refusals() {
        tenants.create("beta", "Beta");
        String key = keys.issue("beta", "first");
        String forged = key.substring(0, 11) + "x".repeat(32);

        assertThat(keys.resolve(forged)).isEmpty();
        assertThat(keys.resolve(null)).isEmpty();
        assertThat(keys.resolve("not-a-key")).isEmpty();
        assertThat(keys.resolve("cs_short")).isEmpty();

        assertThat(keys.revoke(TenantApiKeys.keyId(key))).isEqualTo(1);
        assertThat(keys.revoke(TenantApiKeys.keyId(key))).as("revoking twice changes nothing").isZero();
        assertThat(keys.resolve(key)).isEmpty();

        String second = keys.issue("beta", "second");
        assertThat(keys.resolve(second)).isPresent();
        tenants.setEnabled("beta", false);
        assertThat(keys.resolve(second)).as("a disabled tenant's keys stop working at once").isEmpty();
    }
}
