package dev.merlionos.customerservice.evaluation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Runs and their results, in {@code evaluation_run} and {@code evaluation_result}. */
@Component
public class Evaluations {

    private static final RowMapper<EvaluationRun> RUN = (rs, i) -> new EvaluationRun(rs.getLong("id"), rs.getString("tenant_id"),
            rs.getString("state"), rs.getInt("cases"), rs.getInt("passed"), rs.getInt("retrieval_hits"), rs.getInt("answer_passes"),
            rs.getInt("tool_passes"), rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getString("model"),
            rs.getString("note"), rs.getString("error"), rs.getString("requested_by"), rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
    private static final RowMapper<EvaluationResult> RESULT = (rs, i) -> new EvaluationResult(rs.getLong("run_id"), rs.getLong("case_id"),
            rs.getString("question"), rs.getString("conversation_id"), Json.read(rs.getString("retrieved")), Json.read(rs.getString("tools")),
            rs.getString("answer"), rs.getBoolean("retrieval_hit"), rs.getBoolean("answer_pass"), rs.getBoolean("tool_pass"),
            rs.getBoolean("passed"), rs.getString("failures"), rs.getObject("input_tokens", Integer.class),
            rs.getObject("output_tokens", Integer.class), rs.getObject("millis", Long.class));

    private final JdbcTemplate jdbc;

    public Evaluations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public EvaluationRun start(String tenantId, int cases, String actor, String note) {
        Long id = jdbc.queryForObject("INSERT INTO evaluation_run (tenant_id, state, cases, note, requested_by, started_at) "
                + "VALUES (?, 'running', ?, ?, ?, ?) RETURNING id", Long.class, tenantId, cases,
                note == null || note.isBlank() ? null : note.strip(), actor, Timestamp.from(Instant.now()));
        return find(tenantId, id).orElseThrow();
    }

    public Optional<EvaluationRun> running(String tenantId) {
        return jdbc.query("SELECT * FROM evaluation_run WHERE tenant_id = ? AND state = 'running' ORDER BY id DESC", RUN, tenantId)
                .stream().findFirst();
    }

    public Optional<EvaluationRun> latestDone(String tenantId) {
        return jdbc.query("SELECT * FROM evaluation_run WHERE tenant_id = ? AND state = 'done' ORDER BY started_at DESC, id DESC", RUN, tenantId)
                .stream().findFirst();
    }

    public List<EvaluationRun> of(String tenantId) {
        return jdbc.query("SELECT * FROM evaluation_run WHERE tenant_id = ? ORDER BY started_at DESC, id DESC", RUN, tenantId);
    }

    public Optional<EvaluationRun> find(String tenantId, long id) {
        return jdbc.query("SELECT * FROM evaluation_run WHERE tenant_id = ? AND id = ?", RUN, tenantId, id).stream().findFirst();
    }

    public List<EvaluationResult> resultsOf(long runId) {
        return jdbc.query("SELECT * FROM evaluation_result WHERE run_id = ? ORDER BY case_id", RESULT, runId);
    }

    void record(EvaluationResult result) {
        jdbc.update("""
                INSERT INTO evaluation_result (run_id, case_id, question, conversation_id, retrieved, tools, answer, retrieval_hit,
                                               answer_pass, tool_pass, passed, failures, input_tokens, output_tokens, millis)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, result.runId(), result.caseId(), result.question(), result.conversationId(), Json.write(result.retrieved()),
                Json.write(result.tools()), result.answer(), result.retrievalHit(), result.answerPass(), result.toolPass(),
                result.passed(), result.failures(), result.inputTokens(), result.outputTokens(), result.millis());
    }

    void finish(long runId, int passed, int retrievalHits, int answerPasses, int toolPasses, long inputTokens, long outputTokens, String model) {
        jdbc.update("UPDATE evaluation_run SET state = 'done', passed = ?, retrieval_hits = ?, answer_passes = ?, tool_passes = ?, "
                + "input_tokens = ?, output_tokens = ?, model = ?, finished_at = ? WHERE id = ?",
                passed, retrievalHits, answerPasses, toolPasses, inputTokens, outputTokens, model, Timestamp.from(Instant.now()), runId);
    }

    /** The model the turn record names for a conversation's last turn, so a run says what it measured. */
    Optional<String> modelOf(String conversationId) {
        return jdbc.query("SELECT model FROM conversation_turn WHERE conversation_id = ? AND model IS NOT NULL ORDER BY started_at DESC",
                (rs, i) -> rs.getString(1), conversationId).stream().findFirst();
    }

    void fail(long runId, String error) {
        jdbc.update("UPDATE evaluation_run SET state = 'failed', error = ?, finished_at = ? WHERE id = ?",
                error, Timestamp.from(Instant.now()), runId);
    }
}
