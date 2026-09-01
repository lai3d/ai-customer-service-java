package dev.merlionos.customerservice.cost;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
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

    /** Records one turn's usage against the conversation, and against the global meters. */
    public void record(String conversationId, String model, Usage usage) {
        if (usage == null) {
            return;
        }
        long input = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long output = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
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
            log.debug("No price configured for model {}; tokens counted, cost not", model);
        }

        if (properties.conversationTokenBudget() > 0 && total >= properties.conversationTokenBudget()) {
            log.info("Conversation {} reached its token budget ({} of {})",
                    conversationId, total, properties.conversationTokenBudget());
        }
    }
}
