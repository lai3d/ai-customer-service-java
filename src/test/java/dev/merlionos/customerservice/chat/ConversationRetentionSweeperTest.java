package dev.merlionos.customerservice.chat;

import dev.merlionos.customerservice.tenancy.Tenant;
import dev.merlionos.customerservice.MigratedPostgres;
import dev.merlionos.customerservice.chat.ConversationRetentionSweeper.Table;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retention sweep against a real database: what goes, what stays, and that a second run
 * finds nothing. The retention is 90 days here; rows are aged by writing their timestamps.
 */
class ConversationRetentionSweeperTest {

    private static final Duration RETENTION = Duration.ofDays(90);
    private static final Instant OLD = Instant.now().minus(Duration.ofDays(91));
    private static final Instant RECENT = Instant.now().minus(Duration.ofDays(89));

    static MigratedPostgres db;
    static TurnRecorder recorder;

    private MeterRegistry meters;
    private ConversationRetentionSweeper sweeper;

    @BeforeAll
    static void start() {
        db = MigratedPostgres.start();
        recorder = new TurnRecorder(db.jdbc);
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    @BeforeEach
    void sweeper() {
        meters = new SimpleMeterRegistry();
        sweeper = new ConversationRetentionSweeper(db.jdbc, db.transactionManager,
                new ChatProperties(Duration.ofSeconds(150), RETENTION), meters);
    }

    private static ConversationRetentionSweeper withRetention(Duration retention) {
        return new ConversationRetentionSweeper(db.jdbc, db.transactionManager,
                new ChatProperties(Duration.ofSeconds(150), retention), new SimpleMeterRegistry());
    }

    /** A finished turn with a retrieval row and a tool call, started at {@code startedAt}. */
    private String turn(String conversation, Instant startedAt, boolean finished) {
        String turn = UUID.randomUUID().toString();
        recorder.start(turn, Tenant.DEFAULT, conversation, TurnRecorder.Path.STREAM, "运费多少钱");
        recorder.retrieved(turn, List.of(new TurnEvent.Passage("shipping-cost", "zh", 0.87, "v1")));
        recorder.toolCalled(turn, "lookup_order", "found");
        if (finished) {
            recorder.finish(turn, TurnRecorder.Outcome.COMPLETED, "满 50 美元免运费。", "claude-opus-5", 10, 5, null, null);
        }
        db.jdbc.update("UPDATE conversation_turn SET started_at = ? WHERE turn_id = ?", Timestamp.from(startedAt), turn);
        return turn;
    }

    private void feedback(String turn, String conversation) {
        db.jdbc.update("INSERT INTO answer_feedback (turn_id, tenant_id, conversation_id, issue, reported_by, reported_at) "
                + "VALUES (?, 'default', ?, 'incorrect', 'agent', ?)", turn, conversation, Timestamp.from(Instant.now()));
    }

    private void message(String conversation, Instant at) {
        db.jdbc.update("INSERT INTO spring_ai_chat_memory (conversation_id, content, type, \"timestamp\") VALUES (?, ?, 'USER', ?)",
                conversation, "hello", Timestamp.from(at));
    }

    private boolean turnExists(String turn) {
        return db.jdbc.queryForObject("SELECT count(*) FROM conversation_turn WHERE turn_id = ?", Integer.class, turn) == 1;
    }

    private int rowsFor(String table, String turn) {
        return db.jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE turn_id = ?", Integer.class, turn);
    }

    private int messages(String conversation) {
        return db.jdbc.queryForObject("SELECT count(*) FROM spring_ai_chat_memory WHERE conversation_id = ?",
                Integer.class, conversation);
    }

    private double counted(Table table) {
        return meters.get("chat.retention.deleted").tag("table", table.name).counter().count();
    }

    @Test
    @DisplayName("turns older than the retention go with their rows; newer ones stay")
    void oldTurnsGoNewOnesStay() {
        String conversation = UUID.randomUUID().toString();
        String old = turn(conversation, OLD, true);
        String recent = turn(conversation, RECENT, true);

        Map<Table, Integer> counts = sweeper.sweep();

        assertThat(turnExists(old)).isFalse();
        assertThat(rowsFor("turn_retrieval", old)).isZero();
        assertThat(rowsFor("turn_tool_call", old)).isZero();
        assertThat(turnExists(recent)).isTrue();
        assertThat(rowsFor("turn_retrieval", recent)).isEqualTo(1);
        assertThat(rowsFor("turn_tool_call", recent)).isEqualTo(1);
        assertThat(counts.get(Table.CONVERSATION_TURN)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a turn still running is never deleted, however old")
    void runningTurnStays() {
        String conversation = UUID.randomUUID().toString();
        String running = turn(conversation, OLD, false);

        sweeper.sweep();

        assertThat(turnExists(running)).isTrue();
        assertThat(db.jdbc.queryForMap("SELECT outcome FROM conversation_turn WHERE turn_id = ?", running))
                .containsEntry("outcome", "running");
        assertThat(rowsFor("turn_retrieval", running)).isEqualTo(1);
    }

    @Test
    @DisplayName("feedback on a deleted turn goes with it; feedback on a kept turn stays")
    void feedbackFollowsItsTurn() {
        String conversation = UUID.randomUUID().toString();
        String old = turn(conversation, OLD, true);
        String recent = turn(conversation, RECENT, true);
        feedback(old, conversation);
        feedback(recent, conversation);

        Map<Table, Integer> counts = sweeper.sweep();

        assertThat(rowsFor("answer_feedback", old)).isZero();
        assertThat(rowsFor("answer_feedback", recent)).isEqualTo(1);
        assertThat(counts.get(Table.ANSWER_FEEDBACK)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("chat memory older than the retention goes by its own timestamp; newer messages stay")
    void oldMemoryGoes() {
        String conversation = UUID.randomUUID().toString();
        message(conversation, OLD);
        message(conversation, OLD);
        message(conversation, RECENT);

        Map<Table, Integer> counts = sweeper.sweep();

        assertThat(messages(conversation)).isEqualTo(1);
        assertThat(counts.get(Table.CHAT_MEMORY)).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a second sweep finds nothing: the sweep is idempotent")
    void secondSweepDeletesNothing() {
        String conversation = UUID.randomUUID().toString();
        turn(conversation, OLD, true);
        message(conversation, OLD);

        sweeper.sweep();
        Map<Table, Integer> second = sweeper.sweep();

        assertThat(second.values()).as("nothing left to delete").allMatch(count -> count == 0);
    }

    @Test
    @DisplayName("more turns than one batch are all gone after one sweep, batch by batch")
    void sweepsInBatchesUntilDone() {
        String conversation = UUID.randomUUID().toString();
        int turns = ConversationRetentionSweeper.BATCH * 2 + 7;
        Timestamp startedAt = Timestamp.from(OLD);
        db.jdbc.batchUpdate("INSERT INTO conversation_turn (turn_id, tenant_id, conversation_id, path, started_at, outcome, question) "
                        + "VALUES (?, 'default', ?, 'stream', ?, 'completed', 'q')",
                IntStream.range(0, turns).mapToObj(i -> UUID.randomUUID().toString()).toList(), 100,
                (ps, id) -> {
                    ps.setString(1, id);
                    ps.setString(2, conversation);
                    ps.setTimestamp(3, startedAt);
                });
        int messages = ConversationRetentionSweeper.BATCH + 3;
        for (int i = 0; i < messages; i++) {
            message(conversation, OLD);
        }

        Map<Table, Integer> counts = sweeper.sweep();

        assertThat(counts.get(Table.CONVERSATION_TURN)).isGreaterThanOrEqualTo(turns);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM conversation_turn WHERE conversation_id = ?",
                Integer.class, conversation)).isZero();
        assertThat(messages(conversation)).isZero();
    }

    @Test
    @DisplayName("the counter is registered at zero per table and carries what the sweep deleted")
    void countsWhatItDeleted() {
        for (Table table : Table.values()) {
            assertThat(counted(table)).as(table.name + " before any sweep").isZero();
        }
        String conversation = UUID.randomUUID().toString();
        String old = turn(conversation, OLD, true);
        feedback(old, conversation);
        message(conversation, OLD);

        Map<Table, Integer> counts = sweeper.sweep();

        for (Table table : Table.values()) {
            assertThat(counted(table)).as(table.name).isEqualTo((double) counts.get(table));
        }
        assertThat(counted(Table.CONVERSATION_TURN)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a retention of zero or less is refused at construction, with the property named")
    void refusesNonPositiveRetention() {
        assertThatThrownBy(() -> withRetention(Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.chat.record-retention").hasMessageContaining("CONVERSATION_RETENTION");
        assertThatThrownBy(() -> withRetention(Duration.ofDays(-1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(withRetention(null).retention()).as("unset falls back to the default").isEqualTo(Duration.ofDays(90));
    }
}
