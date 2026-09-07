package dev.merlionos.customerservice.tenancy;

import dev.merlionos.customerservice.MigratedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

/** The client-id to internal-id mapping against a real Postgres, no Spring context. */
class ConversationsTest {

    static MigratedPostgres postgres;
    static Conversations conversations;

    @BeforeAll
    static void start() {
        postgres = MigratedPostgres.start();
        conversations = new Conversations(postgres.jdbc);
        new Tenants(postgres.jdbc).create("acme", "Acme");
    }

    @AfterAll
    static void stop() {
        postgres.close();
    }

    @Test
    @DisplayName("a client id maps to one internal id per tenant, stable across calls")
    void stableWithinATenant() {
        String first = conversations.resolve(Tenant.DEFAULT, "web-123");
        String again = conversations.resolve(Tenant.DEFAULT, "web-123");

        assertThat(again).isEqualTo(first).isNotEqualTo("web-123");
        assertThat(conversations.externalIdOf(first)).contains("web-123");
        assertThat(conversations.find(Tenant.DEFAULT, "web-123")).contains(first);
    }

    @Test
    @DisplayName("the same client id under another tenant is another conversation")
    void isolatedBetweenTenants() {
        String ours = conversations.resolve(Tenant.DEFAULT, "shared-7");
        String theirs = conversations.resolve("acme", "shared-7");

        assertThat(theirs).isNotEqualTo(ours);
        assertThat(conversations.find("acme", "shared-7")).contains(theirs);
        assertThat(conversations.find(Tenant.DEFAULT, "shared-7")).contains(ours);
    }

    @Test
    @DisplayName("with no client id a new conversation is minted whose id is both")
    void mintsWhenAbsent() {
        String minted = conversations.resolve(Tenant.DEFAULT, null);
        String blank = conversations.resolve(Tenant.DEFAULT, "  ");

        assertThat(minted).isNotEqualTo(blank);
        assertThat(conversations.externalIdOf(minted)).contains(minted);
        assertThat(conversations.resolve(Tenant.DEFAULT, minted)).isEqualTo(minted);
    }

    @Test
    @DisplayName("two first turns racing on one client id agree on the internal id")
    void firstTurnRace() throws Exception {
        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            results.add(pool.submit(() -> {
                gate.await();
                return conversations.resolve("acme", "raced");
            }));
        }
        gate.countDown();
        Set<String> ids = new HashSet<>();
        for (Future<String> result : results) {
            ids.add(result.get());
        }
        pool.shutdown();

        assertThat(ids).hasSize(1);
        assertThat(postgres.jdbc.queryForObject(
                "SELECT count(*) FROM conversation WHERE tenant_id = 'acme' AND external_id = 'raced'", Integer.class))
                .isEqualTo(1);
    }
}
