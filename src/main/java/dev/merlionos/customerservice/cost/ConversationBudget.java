package dev.merlionos.customerservice.cost;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Caps what one conversation can spend, and records what everything spends.
 *
 * <p>Without a cap, a single conversation is an open-ended bill. Memory is windowed at 40
 * messages so any one request is bounded, but the number of requests is not: a customer who
 * keeps typing, or a script that does, runs indefinitely. The failure is not dramatic -- no
 * error, no alert, just a larger invoice at the end of the month. Reaching the cap is treated
 * as a reason to fetch a human, which is the right outcome for a conversation that long anyway.
 *
 * <p>Spend is a row per conversation in {@code conversation_budget}, so every replica sees the
 * same number and a restart forgets nothing. The predecessor was a bounded in-memory map,
 * honest about limiting blast radius rather than keeping a ledger; two replicas behind one
 * Service each gave a conversation its own allowance. The row is still not an exact ledger,
 * for a reason that has nothing to do with where it lives: usage arrives on the provider's
 * final chunk, so a turn cancelled early is recorded as whatever had been reported by then,
 * often nothing. That is stated in docs/reliability.md rather than papered over with an
 * estimate; reserving an allowance up front is deferred in ADR 001 until an estimate has
 * been measured.
 *
 * <p>Rows untouched for {@code app.cost.budget-retention} are swept on a schedule. A table
 * keyed by conversation id otherwise grows without bound -- the same leak the old map was
 * bounded against.
 *
 * <p>Metrics are tagged by model, never by conversation id. Per-conversation tags would make
 * cardinality grow without limit and take the metrics backend down long before the bill did.
 *
 * <p>Tokens are always counted; cost only when the reported model has a configured price.
 * Those two can disagree, so {@code chat.unpriced.model.calls} counts the disagreement rather
 * than letting a zero cost pass for a cheap month.
 */
@Component
public class ConversationBudget {

    private static final Logger log = LoggerFactory.getLogger(ConversationBudget.class);

    private final CostProperties properties;
    private final MeterRegistry meterRegistry;
    private final JdbcTemplate jdbc;

    ConversationBudget(CostProperties properties, MeterRegistry meterRegistry, JdbcTemplate jdbc) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.jdbc = jdbc;
    }

    /**
     * @throws ConversationBudgetExceededException when this conversation has spent its budget
     */
    public void checkRemaining(String conversationId) {
        if (properties.conversationTokenBudget() <= 0) {
            return;
        }
        long spent = spent(conversationId);
        if (spent >= properties.conversationTokenBudget()) {
            throw new ConversationBudgetExceededException(
                    conversationId, spent, properties.conversationTokenBudget());
        }
    }

    public long spent(String conversationId) {
        return jdbc.query("SELECT tokens_spent FROM conversation_budget WHERE conversation_id = ?",
                        (rs, i) -> rs.getLong(1), conversationId)
                .stream().findFirst().orElse(0L);
    }

    /**
     * Records one turn's usage against the conversation, and against the global meters.
     *
     * <p>Takes totals rather than a provider {@code Usage} because a turn is not a model call:
     * a tool-calling turn bills for at least two, and summing them correctly is the caller's
     * problem. See {@code TurnUsage}.
     */
    public void record(String conversationId, String model, long input, long output) {
        if (input == 0 && output == 0) {
            return;
        }

        // One statement, so two replicas recording for the same conversation at once both add
        // rather than one overwriting the other's read.
        long total = jdbc.queryForObject("""
                INSERT INTO conversation_budget (conversation_id, tokens_spent, last_seen)
                VALUES (?, ?, ?)
                ON CONFLICT (conversation_id) DO UPDATE
                    SET tokens_spent = conversation_budget.tokens_spent + EXCLUDED.tokens_spent,
                        last_seen = EXCLUDED.last_seen
                RETURNING tokens_spent
                """, Long.class, conversationId, input + output, Timestamp.from(Instant.now()));

        meterRegistry.counter("chat.tokens", "model", model, "type", "input").increment(input);
        meterRegistry.counter("chat.tokens", "model", model, "type", "output").increment(output);

        CostProperties.ModelPrice price = properties.prices().get(model);
        if (price != null) {
            double usd = input * price.inputPerMillionUsd() / 1_000_000
                    + output * price.outputPerMillionUsd() / 1_000_000;
            meterRegistry.counter("chat.cost.usd", "model", model).increment(usd);
        }
        else {
            // A model with no price is the quiet version of a billing bug: tokens keep being
            // counted, chat.cost.usd stays flat, and the dashboard reads as "we spent nothing"
            // rather than "we cannot tell". It happens for a mundane reason -- providers answer
            // with a dated id (asking for gpt-5 yields gpt-5-2025-08-07), so a price keyed on
            // the requested name never matches. A counter makes the gap visible; a debug log
            // does not, because nobody turns debug on for a number that looks plausible.
            meterRegistry.counter("chat.unpriced.model.calls", "model", model).increment();
            log.debug("No price configured for model {}; tokens counted, cost not", model);
        }

        if (properties.conversationTokenBudget() > 0 && total >= properties.conversationTokenBudget()) {
            log.info("Conversation {} reached its token budget ({} of {})",
                    conversationId, total, properties.conversationTokenBudget());
        }
    }

    /** Sweeps rows older than the retention. Returns how many went, for tests and logs. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    public int sweep() {
        Duration retention = properties.budgetRetention() == null ? Duration.ofDays(30) : properties.budgetRetention();
        int swept = jdbc.update("DELETE FROM conversation_budget WHERE last_seen < ?",
                Timestamp.from(Instant.now().minus(retention)));
        if (swept > 0) {
            log.info("Swept {} conversation budget rows untouched for {}", swept, retention);
        }
        return swept;
    }
}
