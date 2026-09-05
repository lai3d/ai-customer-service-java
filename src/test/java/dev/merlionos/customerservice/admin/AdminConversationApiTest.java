package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.chat.TurnEvent;
import dev.merlionos.customerservice.chat.TurnRecorder;
import dev.merlionos.customerservice.ticket.api.TicketOperations;
import dev.merlionos.customerservice.ticket.api.TicketRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conversation list and detail over the admin API, from rows the recorder wrote. Same
 * context configuration as {@code CustomerServiceApplicationTests} (CLAUDE.md, the ONNX
 * ceiling); data through beans.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rag.import-mode=startup")
@AutoConfigureObservability
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AdminConversationApiTest {

    static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffAccounts accounts;

    @Autowired
    TurnRecorder recorder;

    @Autowired
    TicketOperations tickets;

    String good;
    String bad;
    String ticketNumber;

    @BeforeEach
    void twoRecordedConversations() {
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM admin_audit");
        jdbc.update("DELETE FROM staff_account");
        // Flags reference turns; another test in this shared context may have left some.
        // CI's test order is not this machine's, which is how that surfaced.
        jdbc.update("DELETE FROM answer_feedback");
        jdbc.update("DELETE FROM turn_retrieval");
        jdbc.update("DELETE FROM turn_tool_call");
        jdbc.update("DELETE FROM conversation_turn");
        accounts.create("alice", PASSWORD, StaffRole.SUPPORT, "root");

        good = UUID.randomUUID().toString();
        String turn1 = UUID.randomUUID().toString();
        recorder.start(turn1, good, TurnRecorder.Path.STREAM, "运费多少钱");
        recorder.retrieved(turn1, List.of(new TurnEvent.Passage("shipping-cost", "zh", 0.8731)));
        recorder.finish(turn1, TurnRecorder.Outcome.COMPLETED, "满 **50** 美元免运费。", "claude-opus-5", 1204, 87, "trace-1", null);
        String turn2 = UUID.randomUUID().toString();
        recorder.start(turn2, good, TurnRecorder.Path.BLOCKING, "My parcel arrived crushed");
        recorder.toolCalled(turn2, "create_support_ticket", "created");
        ticketNumber = tickets.create(new TicketRequest(UUID.randomUUID().toString(), good,
                "Parcel arrived crushed", "returns", null)).ticket().ticketNumber();
        recorder.finish(turn2, TurnRecorder.Outcome.COMPLETED, "I have raised a ticket.", "claude-opus-5", 900, 40, "trace-2", null);

        bad = UUID.randomUUID().toString();
        String turn3 = UUID.randomUUID().toString();
        recorder.start(turn3, bad, TurnRecorder.Path.STREAM, "hello?");
        recorder.finish(turn3, TurnRecorder.Outcome.FAILED, null, null, null, null, null,
                new IllegalStateException("provider down"));
    }

    @Test
    @DisplayName("the list shows each conversation once, most recent first, with its turn count and how its turns ended")
    void listsConversations() throws Exception {
        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);

        HttpResponse<String> all = alice.get("/admin/api/conversations?size=100");
        assertThat(all.statusCode()).isEqualTo(200);
        assertThat(all.body()).contains("\"total\":2")
                .contains("\"conversationId\":\"" + good + "\",\"turns\":2")
                .contains("\"conversationId\":\"" + bad + "\",\"turns\":1")
                .contains("\"lastOutcome\":\"failed\"");
        assertThat(all.body().indexOf(bad)).as("the failed one started last").isLessThan(all.body().indexOf(good));

        assertThat(alice.get("/admin/api/conversations?outcome=failed").body())
                .contains("\"total\":1").contains(bad).doesNotContain(good);
        assertThat(alice.get("/admin/api/conversations?conversationId=" + good).body())
                .contains("\"total\":1").contains(good);
        assertThat(alice.get("/admin/api/conversations?from=" + Instant.now().plusSeconds(60)).body())
                .contains("\"total\":0");
        assertThat(alice.get("/admin/api/conversations?outcome=bogus").statusCode()).isEqualTo(400);
        assertThat(new AdminBrowser(port).get("/admin/api/conversations").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a conversation's detail is its turns with evidence, tools, cost and tickets, and opening it is recorded")
    void detailIsTheRecord() throws Exception {
        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);

        HttpResponse<String> detail = alice.get("/admin/api/conversations/" + good);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body())
                .contains("\"question\":\"运费多少钱\"", "\"answer\":\"满 **50** 美元免运费。\"")
                .contains("\"entryId\":\"shipping-cost\"", "\"score\":0.8731")
                .contains("\"tool\":\"create_support_ticket\"", "\"outcome\":\"created\"")
                .contains("\"inputTokens\":1204", "\"traceId\":\"trace-1\"")
                .contains("\"ticketNumber\":\"" + ticketNumber + "\"")
                .contains("never recorded");
        assertThat(detail.body().indexOf("运费多少钱")).as("oldest turn first").isLessThan(detail.body().indexOf("crushed"));

        HttpResponse<String> failed = alice.get("/admin/api/conversations/" + bad);
        assertThat(failed.body()).contains("\"outcome\":\"failed\"", "provider down", "\"answer\":null");

        assertThat(alice.get("/admin/api/conversations/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForList("SELECT target FROM admin_audit WHERE action = 'viewed_conversation' ORDER BY id", String.class))
                .containsExactly(good, bad);
    }
}
