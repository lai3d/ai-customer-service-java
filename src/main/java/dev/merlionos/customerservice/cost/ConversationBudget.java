package dev.merlionos.customerservice.cost;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Caps what one conversation can spend, and records what everything spends.
 *
 * <p>Without a cap, a single conversation is an open-ended bill. Memory is windowed at 40
 * messages so any one request is bounded, but the number of requests is not: a customer who
 * keeps typing, or a script that does, runs indefinitely. The failure is not dramatic -- no
 * error, no alert, just a larger invoice at the end of the month. Reaching the cap is treated
 * as a reason to fetch a human, which is the right outcome for a conversation that long anyway.
 *
 * <p>Spend is kept in memory in a bounded LRU map. That is honest about what it is: it resets
 * on restart and is per-replica, so it limits blast radius rather than enforcing an exact
 * ledger. A deployment that needs the real thing would keep this in Redis or in Postgres beside
 * the chat memory. The bound matters more than it looks -- an unbounded map keyed by
 * conversation id is a memory leak with a long fuse.
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
    private final Map<String, AtomicLong> spentByConversation;

    ConversationBudget(CostProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;

        int capacity = Math.max(1, properties.trackedConversations());
        this.spentByConversation = Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, AtomicLong> eldest) {
                        return size() > capacity;
                    }
                });
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
        AtomicLong counter = spentByConversation.get(conversationId);
        return counter == null ? 0L : counter.get();
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

        long total = spentByConversation
                .computeIfAbsent(conversationId, key -> new AtomicLong())
                .addAndGet(input + output);

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
}
