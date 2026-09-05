package dev.merlionos.customerservice.chat;

import dev.merlionos.customerservice.MigratedPostgres;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The turn record, row by row, and the sweep that closes what a dead process left open. */
class TurnRecorderTest {

    static MigratedPostgres db;
    static TurnRecorder recorder;

    @BeforeAll
    static void start() {
        db = MigratedPostgres.start();
        recorder = new TurnRecorder(db.jdbc);
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    private Map<String, Object> row(String turnId) {
        return db.jdbc.queryForMap("SELECT * FROM conversation_turn WHERE turn_id = ?", turnId);
    }

    @Test
    @DisplayName("a turn starts running with its question, gathers retrieval and tool calls, and ends once")
    void aWholeTurn() {
        String turn = UUID.randomUUID().toString();
        recorder.start(turn, "conv-1", TurnRecorder.Path.STREAM, "运费多少钱");
        assertThat(row(turn)).containsEntry("outcome", "running").containsEntry("path", "stream")
                .containsEntry("question", "运费多少钱").containsEntry("ended_at", null);

        recorder.retrieved(turn, List.of(new TurnEvent.Passage("shipping-cost", "zh", 0.87),
                new TurnEvent.Passage("returns-damaged", "zh", 0.81)));
        recorder.toolCalled(turn, "lookup_order", "found");
        recorder.finish(turn, TurnRecorder.Outcome.COMPLETED, "满 50 美元免运费。", "claude-opus-5", 1204, 87, "abc123", null);
        recorder.finish(turn, TurnRecorder.Outcome.FAILED, null, null, null, null, null, new RuntimeException("late"));

        assertThat(row(turn)).containsEntry("outcome", "completed").containsEntry("answer", "满 50 美元免运费。")
                .containsEntry("model", "claude-opus-5").containsEntry("input_tokens", 1204)
                .containsEntry("output_tokens", 87).containsEntry("trace_id", "abc123").containsEntry("failure", null);
        assertThat(db.jdbc.queryForList("SELECT entry_id FROM turn_retrieval WHERE turn_id = ? ORDER BY rank", String.class, turn))
                .containsExactly("shipping-cost", "returns-damaged");
        assertThat(db.jdbc.queryForList("SELECT tool || ':' || outcome FROM turn_tool_call WHERE turn_id = ? ORDER BY id", String.class, turn))
                .containsExactly("lookup_order:found");
    }

    @Test
    @DisplayName("a failure is recorded as its class and message, cut to a bounded length")
    void failureIsDescribed() {
        String turn = UUID.randomUUID().toString();
        recorder.start(turn, "conv-2", TurnRecorder.Path.BLOCKING, "hello");
        recorder.finish(turn, TurnRecorder.Outcome.FAILED, null, null, null, null, null,
                new IllegalStateException("x".repeat(1000)));

        String failure = (String) row(turn).get("failure");
        assertThat(failure).startsWith("IllegalStateException: xxx").hasSize(TurnRecorder.FAILURE_LENGTH);
        assertThat(row(turn)).containsEntry("outcome", "failed");

        assertThat(TurnRecorder.describe(new RuntimeException("Stream processing failed", new IllegalStateException("provider down"))))
                .isEqualTo("RuntimeException: Stream processing failed (caused by IllegalStateException: provider down)");
    }

    @Test
    @DisplayName("a second start with the same turn id is an error, not a silent overwrite")
    void startIsNotIdempotent() {
        String turn = UUID.randomUUID().toString();
        recorder.start(turn, "conv-3", TurnRecorder.Path.BLOCKING, "one");
        assertThatThrownBy(() -> recorder.start(turn, "conv-3", TurnRecorder.Path.BLOCKING, "two"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    @DisplayName("the sweep marks running turns older than the lease as unknown, and leaves the rest alone")
    void sweepMarksStaleTurnsUnknown() {
        String stale = UUID.randomUUID().toString();
        String fresh = UUID.randomUUID().toString();
        String done = UUID.randomUUID().toString();
        recorder.start(stale, "conv-4", TurnRecorder.Path.STREAM, "q");
        recorder.start(fresh, "conv-4", TurnRecorder.Path.STREAM, "q");
        recorder.start(done, "conv-4", TurnRecorder.Path.STREAM, "q");
        recorder.finish(done, TurnRecorder.Outcome.COMPLETED, "a", null, null, null, null, null);
        db.jdbc.update("UPDATE conversation_turn SET started_at = ? WHERE turn_id IN (?, ?)",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))), stale, done);

        int marked = recorder.sweep(Duration.ofSeconds(150));

        assertThat(marked).isEqualTo(1);
        assertThat(row(stale)).containsEntry("outcome", "unknown");
        assertThat(row(stale).get("ended_at")).isNotNull();
        assertThat(row(fresh)).containsEntry("outcome", "running");
        assertThat(row(done)).containsEntry("outcome", "completed");
        assertThat(recorder.sweep(Duration.ofSeconds(150))).as("nothing left to mark").isZero();
    }
}
