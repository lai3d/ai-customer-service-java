package dev.merlionos.customerservice.cost;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationBudgetTest {

    private static final String MODEL = "claude-opus-5";

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private ConversationBudget budget(long tokenBudget, int tracked) {
        return new ConversationBudget(new CostProperties(tokenBudget, tracked,
                Map.of(MODEL, new CostProperties.ModelPrice(5.00, 25.00))), meterRegistry);
    }

    @Test
    @DisplayName("spend accumulates across the turns of one conversation")
    void accumulatesSpend() {
        ConversationBudget budget = budget(1000, 100);

        budget.record("c1", MODEL, new DefaultUsage(100, 20));
        budget.record("c1", MODEL, new DefaultUsage(200, 30));

        assertThat(budget.spent("c1")).isEqualTo(350);
        assertThat(budget.spent("c2")).isZero();
    }

    @Test
    @DisplayName("a conversation that reaches its budget is refused rather than billed further")
    void refusesWhenBudgetSpent() {
        ConversationBudget budget = budget(300, 100);

        budget.record("c1", MODEL, new DefaultUsage(200, 50));
        assertThatCode(() -> budget.checkRemaining("c1")).doesNotThrowAnyException();

        budget.record("c1", MODEL, new DefaultUsage(50, 10));
        assertThatThrownBy(() -> budget.checkRemaining("c1"))
                .isInstanceOf(ConversationBudgetExceededException.class);
    }

    @Test
    @DisplayName("a budget of zero means no cap")
    void zeroDisablesTheCap() {
        ConversationBudget budget = budget(0, 100);

        budget.record("c1", MODEL, new DefaultUsage(10_000_000, 10_000_000));

        assertThatCode(() -> budget.checkRemaining("c1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("tracking is bounded, so the map is not a slow memory leak")
    void evictsLeastRecentlyUsedConversations() {
        ConversationBudget budget = budget(1000, 2);

        budget.record("a", MODEL, new DefaultUsage(10, 10));
        budget.record("b", MODEL, new DefaultUsage(10, 10));
        budget.record("c", MODEL, new DefaultUsage(10, 10));

        assertThat(budget.spent("a")).as("evicted as least recently used").isZero();
        assertThat(budget.spent("b")).isEqualTo(20);
        assertThat(budget.spent("c")).isEqualTo(20);
    }

    @Test
    @DisplayName("tokens and cost are metered by model, never by conversation")
    void metersByModelOnly() {
        ConversationBudget budget = budget(0, 100);

        budget.record("c1", MODEL, new DefaultUsage(1_000_000, 100_000));

        assertThat(meterRegistry.get("chat.tokens").tag("model", MODEL).tag("type", "input")
                .counter().count()).isEqualTo(1_000_000);
        assertThat(meterRegistry.get("chat.tokens").tag("model", MODEL).tag("type", "output")
                .counter().count()).isEqualTo(100_000);

        // 1M input at $5 + 100k output at $25/M = 5.00 + 2.50
        assertThat(meterRegistry.get("chat.cost.usd").tag("model", MODEL).counter().count())
                .isEqualTo(7.50);

        // Conversation id as a tag would grow cardinality without limit and take the metrics
        // backend down long before the bill did.
        assertThat(meterRegistry.get("chat.tokens").counters())
                .allSatisfy(counter -> assertThat(counter.getId().getTags())
                        .noneMatch(tag -> tag.getKey().toLowerCase().contains("conversation")));
    }

    @Test
    @DisplayName("an unpriced model still has its tokens counted")
    void countsTokensForUnpricedModels() {
        ConversationBudget budget = budget(0, 100);

        budget.record("c1", "some-new-model", new DefaultUsage(10, 5));

        assertThat(meterRegistry.get("chat.tokens").tag("model", "some-new-model")
                .tag("type", "input").counter().count()).isEqualTo(10);
        assertThat(meterRegistry.find("chat.cost.usd").tag("model", "some-new-model").counter())
                .isNull();
    }
}
