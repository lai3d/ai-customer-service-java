package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.MigratedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code staff_account} table and the seed, against a real Postgres with no Spring
 * context: what is stored, what is refused, and what the seed does the second time.
 */
class StaffAccountsTest {

    static MigratedPostgres db;
    static PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    StaffAccounts accounts;

    @BeforeAll
    static void start() {
        db = MigratedPostgres.start();
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    @BeforeEach
    void clean() {
        db.jdbc.update("DELETE FROM staff_account");
        accounts = new StaffAccounts(db.jdbc, encoder);
    }

    @Test
    @DisplayName("a created account is stored as a bcrypt hash under a normalised username, and the hash never leaves through the listing")
    void storesABcryptHash() {
        StaffAccount created = accounts.create("  Alice ", "correct horse battery", StaffRole.SUPPORT, "root");

        assertThat(created.username()).isEqualTo("alice");
        String stored = db.jdbc.queryForObject("SELECT password_hash FROM staff_account WHERE username = 'alice'", String.class);
        assertThat(stored).startsWith("{bcrypt}$2");
        assertThat(encoder.matches("correct horse battery", stored)).isTrue();
        assertThat(encoder.matches("correct horse batteri", stored)).isFalse();

        StaffAccounts.Credential credential = accounts.credential("ALICE").orElseThrow();
        assertThat(credential.role()).isEqualTo(StaffRole.SUPPORT);
        assertThat(credential.enabled()).isTrue();
        assertThat(accounts.list()).singleElement().satisfies(account -> {
            assertThat(account.username()).isEqualTo("alice");
            assertThat(account.createdBy()).isEqualTo("root");
        });
    }

    @Test
    @DisplayName("a taken username is a conflict, not a second row and not a silent overwrite")
    void refusesADuplicate() {
        accounts.create("alice", "correct horse battery", StaffRole.SUPPORT, "root");

        assertThatThrownBy(() -> accounts.create("Alice", "another long password", StaffRole.ADMIN, "root"))
                .isInstanceOf(DuplicateStaffAccountException.class);
        assertThat(db.count("staff_account")).isEqualTo(1);
        assertThat(accounts.credential("alice").orElseThrow().role()).isEqualTo(StaffRole.SUPPORT);
    }

    @Test
    @DisplayName("a bad username or a short password writes nothing")
    void validatesBeforeWriting() {
        assertThatThrownBy(() -> accounts.create("al", "correct horse battery", StaffRole.SUPPORT, "root"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("username");
        assertThatThrownBy(() -> accounts.create("alice smith", "correct horse battery", StaffRole.SUPPORT, "root"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("username");
        assertThatThrownBy(() -> accounts.create("alice", "short", StaffRole.SUPPORT, "root"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("12");
        assertThatThrownBy(() -> accounts.create("alice", "correct horse battery", null, "root"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("role");
        assertThat(db.count("staff_account")).isZero();
    }

    @Test
    @DisplayName("the seed creates the first admin once, and is a no-op with accounts present")
    void seedsOnlyAnEmptyTable() {
        StaffSeeder seeder = new StaffSeeder(accounts, new AdminProperties(new AdminProperties.Seed("Root", "seed-password-1")));

        assertThat(seeder.seed()).isTrue();
        assertThat(accounts.find("root")).hasValueSatisfying(admin -> {
            assertThat(admin.role()).isEqualTo(StaffRole.ADMIN);
            assertThat(admin.createdBy()).isEqualTo(StaffSeeder.CREATED_BY);
        });

        assertThat(seeder.seed()).as("a second start with the same seed changes nothing").isFalse();
        StaffSeeder other = new StaffSeeder(accounts, new AdminProperties(new AdminProperties.Seed("other", "other-password-1")));
        assertThat(other.seed()).as("a different seed against a populated table changes nothing either").isFalse();
        assertThat(accounts.list()).extracting(StaffAccount::username).containsExactly("root");
    }

    @Test
    @DisplayName("half a seed refuses to start; no seed at all does nothing")
    void seedIsBothOrNeither() {
        assertThatThrownBy(() -> new StaffSeeder(accounts, new AdminProperties(new AdminProperties.Seed("root", ""))).seed())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ADMIN_SEED_PASSWORD");
        assertThatThrownBy(() -> new StaffSeeder(accounts, new AdminProperties(new AdminProperties.Seed(null, "seed-password-1"))).seed())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ADMIN_SEED_USERNAME");
        assertThat(new StaffSeeder(accounts, new AdminProperties(null)).seed()).isFalse();
        assertThat(db.count("staff_account")).isZero();
    }

    @Test
    @DisplayName("two replicas seeding the same empty table produce one admin and no failed start")
    void seedRaceIsBenign() throws Exception {
        StaffSeeder first = new StaffSeeder(accounts, new AdminProperties(new AdminProperties.Seed("root", "seed-password-1")));
        StaffSeeder second = new StaffSeeder(new StaffAccounts(db.jdbc, encoder),
                new AdminProperties(new AdminProperties.Seed("root", "seed-password-1")));

        var a = Thread.ofVirtual().start(first::seed);
        var b = Thread.ofVirtual().start(second::seed);
        a.join();
        b.join();

        assertThat(db.count("staff_account")).isEqualTo(1);
    }
}
