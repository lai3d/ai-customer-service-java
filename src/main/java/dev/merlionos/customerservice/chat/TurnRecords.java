package dev.merlionos.customerservice.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads what {@link TurnRecorder} wrote, for the operations admin: conversations as a list,
 * and one conversation as its turns with their evidence. Read-only; the record is append-only
 * for everything but the outcome, and only the recorder and the sweeper write that.
 */
@Component
public class TurnRecords {

    static final Set<String> OUTCOMES = Set.of("running", "completed", "failed", "interrupted", "unknown");
    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_SIZE = 25;

    /** One conversation in the list: how many turns, when, and how the turns ended. */
    public record Summary(String conversationId, int turns, Instant firstAt, Instant lastAt, String lastOutcome,
                          int failed, int interrupted, int unknown) {
    }

    /**
     * @param conversationId exact, or null
     * @param outcome        conversations with at least one turn that ended this way, or null
     * @param from           turns started at or after; @param to turns started before
     */
    public record Filter(String conversationId, String outcome, Instant from, Instant to, int page, int size) {
        public Filter {
            page = Math.max(page, 0);
            size = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
            conversationId = conversationId == null || conversationId.isBlank() ? null : conversationId.strip();
            outcome = outcome == null || outcome.isBlank() ? null : outcome.strip().toLowerCase(Locale.ROOT);
            if (outcome != null && !OUTCOMES.contains(outcome)) {
                throw new IllegalArgumentException("Unknown outcome '" + outcome + "': one of " + OUTCOMES);
            }
        }
    }

    public record Page(List<Summary> conversations, long total, int page, int size) {
    }

    public record Retrieved(int rank, String entryId, String language, double score) {
    }

    public record ToolCall(String tool, String outcome, Instant at) {
    }

    public record Turn(String turnId, String conversationId, String path, Instant startedAt, Instant endedAt,
                       String outcome, String failure, String model, Integer inputTokens, Integer outputTokens,
                       String traceId, String question, String answer, List<Retrieved> retrieval,
                       List<ToolCall> toolCalls) {
    }

    private static final RowMapper<Summary> SUMMARY = (rs, i) -> new Summary(rs.getString("conversation_id"),
            rs.getInt("turns"), rs.getTimestamp("first_at").toInstant(), rs.getTimestamp("last_at").toInstant(),
            rs.getString("last_outcome"), rs.getInt("failed"), rs.getInt("interrupted"), rs.getInt("unknown"));

    private final JdbcTemplate jdbc;

    public TurnRecords(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page conversations(Filter filter) {
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (filter.conversationId() != null) {
            where.add("conversation_id = ?");
            args.add(filter.conversationId());
        }
        if (filter.from() != null) {
            where.add("started_at >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            where.add("started_at < ?");
            args.add(Timestamp.from(filter.to()));
        }
        String clause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        String having = "";
        if (filter.outcome() != null) {
            having = " HAVING bool_or(outcome = ?)";
            args.add(filter.outcome());
        }
        String grouped = "SELECT conversation_id, count(*) AS turns, min(started_at) AS first_at, max(started_at) AS last_at, "
                + "(array_agg(outcome ORDER BY started_at DESC))[1] AS last_outcome, "
                + "count(*) FILTER (WHERE outcome = 'failed') AS failed, "
                + "count(*) FILTER (WHERE outcome = 'interrupted') AS interrupted, "
                + "count(*) FILTER (WHERE outcome = 'unknown') AS unknown "
                + "FROM conversation_turn" + clause + " GROUP BY conversation_id" + having;

        long total = jdbc.queryForObject("SELECT count(*) FROM (" + grouped + ") AS c", Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(filter.size());
        pageArgs.add((long) filter.page() * filter.size());
        List<Summary> conversations = jdbc.query(grouped + " ORDER BY last_at DESC, conversation_id LIMIT ? OFFSET ?",
                SUMMARY, pageArgs.toArray());
        return new Page(conversations, total, filter.page(), filter.size());
    }

    /** Every turn of a conversation, oldest first, each with what it retrieved and which tools it called. */
    public List<Turn> turns(String conversationId) {
        return jdbc.query("SELECT * FROM conversation_turn WHERE conversation_id = ? ORDER BY started_at, turn_id",
                (rs, i) -> {
                    String turnId = rs.getString("turn_id");
                    Timestamp ended = rs.getTimestamp("ended_at");
                    return new Turn(turnId, rs.getString("conversation_id"), rs.getString("path"),
                            rs.getTimestamp("started_at").toInstant(), ended == null ? null : ended.toInstant(),
                            rs.getString("outcome"), rs.getString("failure"), rs.getString("model"),
                            rs.getObject("input_tokens", Integer.class), rs.getObject("output_tokens", Integer.class),
                            rs.getString("trace_id"), rs.getString("question"), rs.getString("answer"),
                            retrieval(turnId), toolCalls(turnId));
                }, conversationId);
    }

    private List<Retrieved> retrieval(String turnId) {
        return jdbc.query("SELECT rank, entry_id, language, score FROM turn_retrieval WHERE turn_id = ? ORDER BY rank",
                (rs, i) -> new Retrieved(rs.getInt("rank"), rs.getString("entry_id"), rs.getString("language"),
                        rs.getDouble("score")), turnId);
    }

    private List<ToolCall> toolCalls(String turnId) {
        return jdbc.query("SELECT tool, outcome, occurred_at FROM turn_tool_call WHERE turn_id = ? ORDER BY id",
                (rs, i) -> new ToolCall(rs.getString("tool"), rs.getString("outcome"),
                        rs.getTimestamp("occurred_at").toInstant()), turnId);
    }
}
