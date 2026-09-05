package dev.merlionos.customerservice.cost;

import dev.merlionos.customerservice.MigratedPostgres;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationBudgetTest {

    private static final String MODEL = "claude-opus-5";

    static MigratedPostgres postgres;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeAll
    static void start() {
        postgres = MigratedPostgres.start();
    }

    @AfterAll
    static void stop() {
        postgres.close();
    }

    private ConversationBudget budget(long tokenBudget, Duration retention) {
        return new ConversationBudget(new CostProperties(tokenBudget, retention,
                Map.of(MODEL, new CostProperties.ModelPrice(5.00, 25.00))), meterRegistry, postgres.jdbc);
    }

    private static String conversation() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("spend accumulates across the turns of one conversation, in the row")
    void accumulatesSpend() {
        ConversationBudget budget = budget(1000, Duration.ofDays(30));
        String c1 = conversation();

        budget.record(c1, MODEL, 100, 20);
        budget.record(c1, MODEL, 200, 30);

        assertThat(budget.spent(c1)).isEqualTo(350);
        assertThat(budget.spent(conversation())).isZero();
        assertThat(postgres.jdbc.queryForObject(
                "SELECT tokens_spent FROM conversation_budget WHERE conversation_id = ?", Long.class, c1))
                .isEqualTo(350L);
    }

    @Test
    @DisplayName("two replicas recording the same conversation at once both add, neither overwrites")
    void concurrentRecordsAllAdd() throws Exception {
        ConversationBudget replicaA = budget(0, Duration.ofDays(30));
        ConversationBudget replicaB = budget(0, Duration.ofDays(30));
        String c = conversation();

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) {
                ConversationBudget replica = i % 2 == 0 ? replicaA : replicaB;
                futures.add(pool.submit(() -> replica.record(c, MODEL, 7, 3)));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(replicaA.spent(c)).isEqualTo(40 * 10);
    }

    @Test
    @DisplayName("a conversation that reaches its budget is refused rather than billed further")
    void refusesWhenBudgetSpent() {
        ConversationBudget budget = budget(300, Duration.ofDays(30));
        String c = conversation();

        budget.record(c, MODEL, 200, 50);
        assertThatCode(() -> budget.checkRemaining(c)).doesNotThrowAnyException();

        budget.record(c, MODEL, 40, 10);
        assertThatThrownBy(() -> budget.checkRemaining(c))
                .isInstanceOf(ConversationBudgetExceededException.class);
    }

    @Test
    @DisplayName("a budget of zero means no cap")
    void zeroMeansUnlimited() {
        ConversationBudget budget = budget(0, Duration.ofDays(30));
        String c = conversation();

        budget.record(c, MODEL, 1_000_000, 1_000_000);

        assertThatCode(() -> budget.checkRemaining(c)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rows untouched for longer than the retention are swept; recent ones stay")
    void sweepsStaleRows() {
        ConversationBudget budget = budget(1000, Duration.ofDays(30));
        String stale = conversation();
        String recent = conversation();
        budget.record(stale, MODEL, 10, 10);
        budget.record(recent, MODEL, 10, 10);
        postgres.jdbc.update("UPDATE conversation_budget SET last_seen = ? WHERE conversation_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(31))), stale);

        int swept = budget.sweep();

        assertThat(swept).isGreaterThanOrEqualTo(1);
        assertThat(budget.spent(stale)).isZero();
        assertThat(budget.spent(recent)).isEqualTo(20);
    }

    @Test
    @DisplayName("tokens and cost are metered by model, never by conversation")
    void metersByModel() {
        ConversationBudget budget = budget(0, Duration.ofDays(30));

        budget.record(conversation(), MODEL, 1_000_000, 100_000);

        assertThat(meterRegistry.get("chat.tokens").tag("model", MODEL).tag("type", "input")
                .counter().count()).isEqualTo(1_000_000);
        assertThat(meterRegistry.get("chat.tokens").tag("model", MODEL).tag("type", "output")
                .counter().count()).isEqualTo(100_000);
        assertThat(meterRegistry.get("chat.cost.usd").tag("model", MODEL).counter().count())
                .isEqualTo(5.00 + 2.50);
        assertThat(meterRegistry.get("chat.tokens").counters())
                .allSatisfy(counter -> assertThat(counter.getId().getTags())
                        .noneMatch(tag -> tag.getKey().contains("conversation")));
    }

    @Test
    @DisplayName("an unpriced model is counted, so a flat cost meter is distinguishable from a cheap month")
    void countsUnpricedModels() {
        ConversationBudget budget = budget(0, Duration.ofDays(30));

        budget.record(conversation(), "some-new-model", 10, 5);

        assertThat(meterRegistry.get("chat.tokens").tag("model", "some-new-model")
                .tag("type", "input").counter().count()).isEqualTo(10);
        assertThat(meterRegistry.find("chat.cost.usd").tag("model", "some-new-model").counter()).isNull();
        assertThat(meterRegistry.get("chat.unpriced.model.calls")
                .tag("model", "some-new-model").counter().count()).isEqualTo(1);
    }
}
