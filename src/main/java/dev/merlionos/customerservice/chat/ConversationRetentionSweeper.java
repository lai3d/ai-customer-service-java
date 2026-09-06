package dev.merlionos.customerservice.chat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Deletes customer conversation text once it is older than {@code app.chat.record-retention}:
 * the turn record ({@code conversation_turn} with its {@code turn_retrieval},
 * {@code turn_tool_call} and {@code answer_feedback} rows) and the model's chat memory
 * ({@code spring_ai_chat_memory}). Deletion, not anonymisation: the owner's decision, and the
 * only one the tables can honour in one statement each.
 *
 * <p>Two tables, two clocks. A turn goes by {@code started_at}, with the rows that reference
 * it, and never while it is still {@code running} -- that row is a process mid-turn or, past
 * the lease, a dead one the {@link TurnRecordSweeper} has not yet marked. A memory row goes by
 * its own {@code timestamp}, so a long conversation loses its oldest messages first and the
 * window the model sees is only ever what the customer said within the retention.
 *
 * <p>Batches, each its own transaction, until a batch comes back short. A first run against a
 * year of conversations would otherwise hold one transaction and one lock set across every
 * row; a batch of {@value #BATCH} turns is a few thousand rows and a few milliseconds. Safe
 * from every replica at once: each batch is selected {@code FOR UPDATE SKIP LOCKED}, so two
 * sweepers share the work rather than wait on each other, and the memory delete is by
 * {@code ctid} because that table has no key.
 *
 * <p>What this never touches: {@code support_ticket} (a ticket outlives the conversation that
 * raised it), {@code admin_audit} (the record of what staff did), {@code conversation_budget}
 * (its own sweep in {@code ConversationBudget}) and {@code conversation_lease}.
 */
@Component
class ConversationRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(ConversationRetentionSweeper.class);

    static final String PROPERTY = "app.chat.record-retention";
    static final Duration DEFAULT_RETENTION = Duration.ofDays(90);
    static final int BATCH = 500;

    /** What one sweep removed, per table. */
    enum Table {
        ANSWER_FEEDBACK("answer_feedback"),
        TURN_RETRIEVAL("turn_retrieval"),
        TURN_TOOL_CALL("turn_tool_call"),
        CONVERSATION_TURN("conversation_turn"),
        CHAT_MEMORY("spring_ai_chat_memory");

        final String name;

        Table(String name) {
            this.name = name;
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Duration retention;
    private final Map<Table, Counter> deleted = new EnumMap<>(Table.class);

    ConversationRetentionSweeper(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                 ChatProperties properties, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.retention = properties.recordRetention() == null ? DEFAULT_RETENTION : properties.recordRetention();
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalStateException(PROPERTY + " (CONVERSATION_RETENTION) must be positive, got " + retention
                    + ": a retention of zero would delete every conversation as soon as it was recorded, "
                    + "so refusing to start rather than run that sweep.");
        }
        // Registered at zero so the series exists before the first sweep and a flat line reads
        // as "nothing old enough yet" rather than "no such metric".
        for (Table table : Table.values()) {
            deleted.put(table, Counter.builder("chat.retention.deleted")
                    .description("Rows deleted because they were older than the conversation retention")
                    .tag("table", table.name)
                    .register(meterRegistry));
        }
    }

    Duration retention() {
        return retention;
    }

    /** Hourly, like the budget sweep; the first hour after a start is left to the startup work. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    void scheduled() {
        try {
            sweep();
        }
        catch (RuntimeException e) {
            // A scheduled method that throws is logged once by Spring and tried again next hour;
            // saying which sweep it was is the part Spring's log line lacks.
            log.error("The conversation retention sweep failed; it will run again in an hour", e);
        }
    }

    /**
     * Deletes everything older than the retention, in batches, and returns the counts per table.
     * Package-private so the test can call it against a database with no context around it.
     */
    Map<Table, Integer> sweep() {
        Instant cutoff = Instant.now().minus(retention);
        Map<Table, Integer> counts = new EnumMap<>(Table.class);
        for (Table table : Table.values()) {
            counts.put(table, 0);
        }

        int turns;
        do {
            Map<Table, Integer> batch = transaction.execute(status -> sweepTurns(cutoff));
            batch.forEach((table, count) -> counts.merge(table, count, Integer::sum));
            turns = batch.get(Table.CONVERSATION_TURN);
        }
        while (turns == BATCH);

        int messages;
        do {
            messages = transaction.execute(status -> jdbc.update("""
                    DELETE FROM spring_ai_chat_memory
                    WHERE ctid IN (SELECT ctid FROM spring_ai_chat_memory WHERE "timestamp" < ? LIMIT ?)
                    """, Timestamp.from(cutoff), BATCH));
            counts.merge(Table.CHAT_MEMORY, messages, Integer::sum);
        }
        while (messages == BATCH);

        counts.forEach((table, count) -> deleted.get(table).increment(count));
        if (counts.values().stream().anyMatch(count -> count > 0)) {
            log.info("Deleted conversation text older than {}: {} turns ({} retrieval, {} tool call, {} feedback rows) "
                            + "and {} chat memory messages",
                    retention, counts.get(Table.CONVERSATION_TURN), counts.get(Table.TURN_RETRIEVAL),
                    counts.get(Table.TURN_TOOL_CALL), counts.get(Table.ANSWER_FEEDBACK), counts.get(Table.CHAT_MEMORY));
        }
        return counts;
    }

    /** One batch of turns and the rows that reference them, inside the caller's transaction. */
    private Map<Table, Integer> sweepTurns(Instant cutoff) {
        Map<Table, Integer> counts = new EnumMap<>(Table.class);
        List<String> turnIds = jdbc.queryForList("""
                SELECT turn_id FROM conversation_turn
                WHERE started_at < ? AND outcome <> 'running'
                ORDER BY started_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, String.class, Timestamp.from(cutoff), BATCH);
        if (turnIds.isEmpty()) {
            for (Table table : Table.values()) {
                counts.put(table, 0);
            }
            return counts;
        }
        // Referencing rows first; the foreign keys have no ON DELETE CASCADE, deliberately, so
        // that nothing but this class removes a turn.
        counts.put(Table.ANSWER_FEEDBACK, deleteByTurn("DELETE FROM answer_feedback WHERE turn_id = ANY(?)", turnIds));
        counts.put(Table.TURN_RETRIEVAL, deleteByTurn("DELETE FROM turn_retrieval WHERE turn_id = ANY(?)", turnIds));
        counts.put(Table.TURN_TOOL_CALL, deleteByTurn("DELETE FROM turn_tool_call WHERE turn_id = ANY(?)", turnIds));
        counts.put(Table.CONVERSATION_TURN, deleteByTurn("DELETE FROM conversation_turn WHERE turn_id = ANY(?)", turnIds));
        counts.put(Table.CHAT_MEMORY, 0);
        return counts;
    }

    private int deleteByTurn(String sql, List<String> turnIds) {
        return jdbc.update(sql, ps -> ps.setArray(1, ps.getConnection().createArrayOf("varchar", turnIds.toArray())));
    }
}
