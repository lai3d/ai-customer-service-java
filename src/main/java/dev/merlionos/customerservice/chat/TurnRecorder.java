package dev.merlionos.customerservice.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Writes the operational record of a turn ({@code conversation_turn}, {@code turn_retrieval},
 * {@code turn_tool_call}; see {@code V7__turn_records.sql}).
 *
 * <p>Two rules, from the proposal, that the shape of this class enforces:
 *
 * <ul>
 * <li>{@link #start} may throw. A turn that cannot write its first row does not call the
 * model: the alternative is a model call that spends money and leaves no trace.</li>
 * <li>Nothing after {@code start} throws. Retrieval, tool calls and the finish are recorded
 * on a best-effort basis and logged at error level when they fail, because by then the
 * model call is in flight or done and failing the customer's turn over bookkeeping would be
 * the wrong trade. A turn whose finish was lost stays {@code running} and becomes
 * {@code unknown} when the sweeper reaches it, which is the honest outcome.</li>
 * </ul>
 *
 * <p>Blocking JDBC calls on whatever thread the event arrives on, including Reactor's. The
 * writes are single small inserts on a pooled connection; this application is blocking
 * everywhere else and does not pretend otherwise here.
 */
@Component
public class TurnRecorder {

    private static final Logger log = LoggerFactory.getLogger(TurnRecorder.class);

    /** How long a failure message may be; the whole stack trace is in the log, not the row. */
    static final int FAILURE_LENGTH = 500;

    public enum Path { BLOCKING, STREAM }

    public enum Outcome { RUNNING, COMPLETED, FAILED, INTERRUPTED, UNKNOWN }

    private final JdbcTemplate jdbc;

    public TurnRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The first row, before the model is called. Throws on failure, deliberately. */
    public void start(String turnId, String conversationId, Path path, String question) {
        jdbc.update("""
                INSERT INTO conversation_turn (turn_id, conversation_id, path, started_at, outcome, question)
                VALUES (?, ?, ?, ?, 'running', ?)
                """, turnId, conversationId, value(path), Timestamp.from(Instant.now()), question);
    }

    public void retrieved(String turnId, List<TurnEvent.Passage> passages) {
        try {
            jdbc.batchUpdate("INSERT INTO turn_retrieval (turn_id, rank, entry_id, language, score) VALUES (?, ?, ?, ?, ?) "
                            + "ON CONFLICT DO NOTHING",
                    passages, passages.size(), (ps, passage) -> {
                        ps.setString(1, turnId);
                        ps.setInt(2, passages.indexOf(passage) + 1);
                        ps.setString(3, passage.entryId());
                        ps.setString(4, passage.language());
                        ps.setDouble(5, passage.score());
                    });
        }
        catch (DataAccessException e) {
            log.error("Could not record retrieval for turn {}", turnId, e);
        }
    }

    public void toolCalled(String turnId, String tool, String outcome) {
        try {
            jdbc.update("INSERT INTO turn_tool_call (turn_id, tool, outcome, occurred_at) VALUES (?, ?, ?, ?)",
                    turnId, tool, outcome, Timestamp.from(Instant.now()));
        }
        catch (DataAccessException e) {
            log.error("Could not record tool call {} for turn {}", tool, turnId, e);
        }
    }

    /**
     * The terminal row. Idempotent on a turn already finished: the first outcome stands, so a
     * completion path and a {@code doFinally} that both call this record one ending.
     */
    public void finish(String turnId, Outcome outcome, String answer, String model,
                       Integer inputTokens, Integer outputTokens, String traceId, Throwable failure) {
        try {
            int updated = jdbc.update("""
                    UPDATE conversation_turn
                    SET outcome = ?, ended_at = ?, answer = ?, model = ?, input_tokens = ?, output_tokens = ?,
                        trace_id = ?, failure = ?
                    WHERE turn_id = ? AND outcome = 'running'
                    """, value(outcome), Timestamp.from(Instant.now()), answer, model, inputTokens, outputTokens,
                    traceId, describe(failure), turnId);
            if (updated == 0) {
                log.debug("Turn {} was already finished; keeping its first outcome", turnId);
            }
        }
        catch (DataAccessException e) {
            log.error("Could not record the end of turn {} as {}; the sweeper will mark it unknown", turnId, outcome, e);
        }
    }

    /**
     * Marks turns still {@code running} after {@code olderThan} as {@code unknown}. A turn
     * cannot legitimately outlive its lease, so anything older belongs to a process that died
     * with it; nothing knows how it ended, and the row says so.
     *
     * @return how many rows were marked
     */
    public int sweep(Duration olderThan) {
        return jdbc.update("""
                UPDATE conversation_turn SET outcome = 'unknown', ended_at = ?
                WHERE outcome = 'running' AND started_at < ?
                """, Timestamp.from(Instant.now()), Timestamp.from(Instant.now().minus(olderThan)));
    }

    /**
     * The failure's class and message, and its root cause's when there is one: Spring AI
     * reports a failed model stream as "Stream processing failed" and keeps the provider's
     * reason one level down, which is the level a person reading the record wants.
     */
    static String describe(Throwable failure) {
        if (failure == null) {
            return null;
        }
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        if (root != failure) {
            text += " (caused by " + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
        }
        return text.length() > FAILURE_LENGTH ? text.substring(0, FAILURE_LENGTH) : text;
    }

    private static String value(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }
}
