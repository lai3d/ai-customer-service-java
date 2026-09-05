package dev.merlionos.customerservice.chat;

import dev.merlionos.customerservice.MigratedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationLeaseTest {

    static MigratedPostgres postgres;
    static ConversationLease lease;

    @BeforeAll
    static void start() {
        postgres = MigratedPostgres.start();
        lease = new ConversationLease(postgres.jdbc, new ChatProperties(Duration.ofSeconds(150)));
    }

    @AfterAll
    static void stop() {
        postgres.close();
    }

    private static String conversation() {
        return UUID.randomUUID().toString();
    }

    private String holder(String conversationId) {
        return postgres.jdbc.queryForObject(
                "SELECT turn_id FROM conversation_lease WHERE conversation_id = ?", String.class, conversationId);
    }

    @Test
    @DisplayName("a second turn on a conversation with one in flight is refused")
    void refusesOverlap() {
        String conversation = conversation();
        lease.acquire(conversation, "turn-1");

        assertThatThrownBy(() -> lease.acquire(conversation, "turn-2"))
                .isInstanceOf(ConversationBusyException.class)
                .hasMessageContaining(conversation);
        assertThat(holder(conversation)).isEqualTo("turn-1");
    }

    @Test
    @DisplayName("releasing lets the next turn in, and different conversations never wait on each other")
    void releaseAdmitsTheNext() {
        String conversation = conversation();
        lease.acquire(conversation, "turn-1");
        assertThatCode(() -> lease.acquire(conversation(), "elsewhere")).doesNotThrowAnyException();

        lease.release(conversation, "turn-1");

        assertThatCode(() -> lease.acquire(conversation, "turn-2")).doesNotThrowAnyException();
        assertThat(holder(conversation)).isEqualTo("turn-2");
    }

    @Test
    @DisplayName("a turn that is not the holder cannot release the holder's lease")
    void onlyTheHolderReleases() {
        String conversation = conversation();
        lease.acquire(conversation, "turn-1");

        lease.release(conversation, "turn-that-never-held-it");

        assertThat(holder(conversation)).isEqualTo("turn-1");
    }

    @Test
    @DisplayName("an expired lease can be taken over, so a dead replica holds nothing forever")
    void expiredLeaseIsTakenOver() throws InterruptedException {
        ConversationLease shortLease = new ConversationLease(postgres.jdbc, new ChatProperties(Duration.ofMillis(200)));
        String conversation = conversation();
        shortLease.acquire(conversation, "turn-from-a-dead-replica");
        assertThatThrownBy(() -> shortLease.acquire(conversation, "too-soon")).isInstanceOf(ConversationBusyException.class);

        Thread.sleep(300);

        assertThatCode(() -> shortLease.acquire(conversation, "turn-2")).doesNotThrowAnyException();
        assertThat(holder(conversation)).isEqualTo("turn-2");
        // The dead replica's late release must not evict the new holder.
        shortLease.release(conversation, "turn-from-a-dead-replica");
        assertThat(holder(conversation)).isEqualTo("turn-2");
    }

    @Test
    @DisplayName("ten replicas admitting the same conversation at once admit exactly one")
    void exactlyOneWinsARace() throws Exception {
        String conversation = conversation();
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            for (int i = 0; i < 10; i++) {
                String turn = "turn-" + i;
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        lease.acquire(conversation, turn);
                        return true;
                    }
                    catch (ConversationBusyException e) {
                        return false;
                    }
                }));
            }
            go.countDown();
            int admitted = 0;
            for (Future<Boolean> future : futures) {
                admitted += future.get() ? 1 : 0;
            }
            assertThat(admitted).isEqualTo(1);
        }
        assertThat(postgres.jdbc.queryForObject(
                "SELECT count(*) FROM conversation_lease WHERE conversation_id = ?", Integer.class, conversation))
                .isEqualTo(1);
    }
}
