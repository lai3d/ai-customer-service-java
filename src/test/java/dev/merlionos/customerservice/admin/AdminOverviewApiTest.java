package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.chat.TurnRecorder;
import dev.merlionos.customerservice.ticket.api.TicketActor;
import dev.merlionos.customerservice.ticket.api.TicketOperations;
import dev.merlionos.customerservice.ticket.api.TicketRequest;
import dev.merlionos.customerservice.ticket.api.TicketWorkflow;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The overview's numbers against rows written through the beans, and its definitions. Shared context. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rag.import-mode=startup")
@AutoConfigureObservability
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AdminOverviewApiTest {

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

    @Autowired
    TicketWorkflow workflow;

    @Autowired
    AdminOverview overview;

    @BeforeEach
    void freshRecords() {
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM admin_audit");
        jdbc.update("DELETE FROM staff_account");
        jdbc.update("DELETE FROM answer_feedback");
        jdbc.update("DELETE FROM turn_retrieval");
        jdbc.update("DELETE FROM turn_tool_call");
        jdbc.update("DELETE FROM conversation_turn");
        jdbc.update("DELETE FROM ticket_event");
        jdbc.update("DELETE FROM ticket_operation");
        jdbc.update("DELETE FROM support_ticket");
        jdbc.update("DELETE FROM conversation_ticket_guard");
        accounts.create("alice", PASSWORD, StaffRole.SUPPORT, "root");
    }

    @Test
    @DisplayName("the numbers are what the rows say, each with a definition, over the window asked for")
    void numbersAndDefinitions() throws Exception {
        String conversation = UUID.randomUUID().toString();
        String t1 = UUID.randomUUID().toString();
        recorder.start(t1, conversation, TurnRecorder.Path.STREAM, "q1");
        recorder.finish(t1, TurnRecorder.Outcome.COMPLETED, "a1", "claude-opus-5", 100, 20, null, null);
        String t2 = UUID.randomUUID().toString();
        recorder.start(t2, conversation, TurnRecorder.Path.STREAM, "q2");
        recorder.finish(t2, TurnRecorder.Outcome.FAILED, null, null, null, null, null, new IllegalStateException("down"));
        String t3 = UUID.randomUUID().toString();
        recorder.start(t3, UUID.randomUUID().toString(), TurnRecorder.Path.BLOCKING, "q3");
        recorder.finish(t3, TurnRecorder.Outcome.INTERRUPTED, null, null, null, null, null, null);
        String number = tickets.create(new TicketRequest(UUID.randomUUID().toString(), conversation, "crushed", "returns", null))
                .ticket().ticketNumber();
        workflow.claim(number, TicketActor.staff("alice"), 0);
        workflow.resolve(number, "sent a replacement", TicketActor.staff("alice"), 1);

        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);
        alice.get("/admin/api/conversations/" + conversation);

        HttpResponse<String> response = alice.get("/admin/api/overview");
        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        assertThat(body).contains("\"key\":\"turns\",\"label\":\"Turns\",\"value\":3")
                .contains("\"key\":\"conversations\",\"label\":\"Conversations\",\"value\":2")
                .contains("\"key\":\"failed\",\"label\":\"Failed\",\"value\":1")
                .contains("\"key\":\"interrupted\",\"label\":\"Interrupted\",\"value\":1")
                .contains("\"key\":\"failureRate\",\"label\":\"Failure rate\",\"value\":33.3")
                .contains("\"key\":\"inputTokens\",\"label\":\"Input tokens\",\"value\":100")
                .contains("\"key\":\"unmetered\",\"label\":\"Turns without usage\",\"value\":2")
                .contains("\"key\":\"resolved\",\"label\":\"Resolved\",\"value\":1")
                .contains("\"key\":\"created\",\"label\":\"Created\",\"value\":1")
                .contains("\"key\":\"claimedInWindow\",\"label\":\"Claimed in window\",\"value\":1")
                .contains("\"key\":\"resolvedInWindow\",\"label\":\"Resolved in window\",\"value\":1")
                .contains("\"key\":\"views\",\"label\":\"Conversations opened\",\"value\":1")
                .contains("\"key\":\"activeVersion\"")
                .contains("\"key\":\"documents\",\"label\":\"Documents\",\"value\":36")
                .contains("Interruptions are the customer's choice and are not failures");

        HttpResponse<String> empty = alice.get("/admin/api/overview?from=" + Instant.now().plusSeconds(3600) + "&to=" + Instant.now().plusSeconds(7200));
        assertThat(empty.body()).contains("\"key\":\"turns\",\"label\":\"Turns\",\"value\":0")
                .contains("\"key\":\"failureRate\",\"label\":\"Failure rate\",\"value\":null");
        assertThat(alice.get("/admin/api/overview?from=2026-01-01T00:00:00Z&to=2025-01-01T00:00:00Z").statusCode()).isEqualTo(400);
        assertThat(alice.get("/admin/api/overview?from=2020-01-01T00:00:00Z&to=2026-01-01T00:00:00Z").statusCode()).isEqualTo(400);
        assertThat(new AdminBrowser(port).get("/admin/api/overview").statusCode()).isEqualTo(401);
        assertThat(AdminOverview.definitions(overview.over(Instant.now().minusSeconds(60), Instant.now())))
                .as("every stat has a definition").allSatisfy((key, definition) -> assertThat(definition).isNotBlank());
    }
}
