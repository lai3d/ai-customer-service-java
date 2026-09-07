package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.chat.TurnRecorder;
import dev.merlionos.customerservice.tenancy.Conversations;
import dev.merlionos.customerservice.tenancy.Tenant;
import dev.merlionos.customerservice.tenancy.Tenants;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pilot report against rows written through the beans: the counts, the deflection
 * rate, the cost from the configured prices, an unpriced model named rather than costed
 * at zero, an evaluation conversation left out, and the tenant scope. Same context as
 * {@link AdminLoginTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rag.import-mode=startup")
@AutoConfigureObservability
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AdminPilotReportApiTest {

    static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StaffAccounts accounts;
    @Autowired Tenants tenants;
    @Autowired Conversations conversations;
    @Autowired TicketOperations tickets;
    @Autowired TurnRecorder recorder;
    @Autowired AnswerFeedback feedback;

    @BeforeEach
    void twoConversationsOneEscalated() {
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM admin_audit");
        jdbc.update("DELETE FROM staff_account");
        jdbc.update("DELETE FROM answer_feedback");
        jdbc.update("DELETE FROM turn_retrieval");
        jdbc.update("DELETE FROM turn_tool_call");
        jdbc.update("DELETE FROM conversation_turn");
        jdbc.update("DELETE FROM ticket_event");
        jdbc.update("DELETE FROM ticket_operation");
        jdbc.update("DELETE FROM conversation_ticket_guard");
        jdbc.update("DELETE FROM support_ticket");
        if (tenants.find("acme").isEmpty()) {
            tenants.create("acme", "Acme Retail");
        }
        accounts.create("root", PASSWORD, StaffRole.ADMIN, null, "seed");
        accounts.create("carol", PASSWORD, StaffRole.ADMIN, "acme", "root");

        String settled = conversations.resolve(Tenant.DEFAULT, "web-" + UUID.randomUUID().toString().substring(0, 8));
        String escalated = conversations.resolve(Tenant.DEFAULT, "web-" + UUID.randomUUID().toString().substring(0, 8));
        turn(settled, "claude-opus-5", 1000, 100);
        turn(settled, "claude-opus-5", 1000, 100);
        String flagged = turn(escalated, "mystery-model", 500, 50);
        tickets.create(new TicketRequest(Tenant.DEFAULT, UUID.randomUUID().toString(), escalated, "Crushed", "returns", "ORD-1"));
        feedback.report(flagged, "incorrect", "wrong", "root");
        // A rehearsal: an evaluation run's conversation, with tokens that must not be costed to the customer
        String rehearsal = conversations.createEvaluation(Tenant.DEFAULT, "eval-" + UUID.randomUUID().toString().substring(0, 8));
        turn(rehearsal, "claude-opus-5", 100000, 10000);
    }

    private String turn(String conversation, String model, int in, int out) {
        String id = UUID.randomUUID().toString();
        recorder.start(id, Tenant.DEFAULT, conversation, TurnRecorder.Path.STREAM, "q");
        recorder.finish(id, TurnRecorder.Outcome.COMPLETED, "a", model, in, out, null, null);
        return id;
    }

    @Test
    @DisplayName("the report counts the window's customer conversations, prices what it can, names what it cannot, and leaves rehearsals out")
    void reportForTheDefaultTenant() throws Exception {
        AdminBrowser root = AdminBrowser.signedIn(port, "root", PASSWORD);
        HttpResponse<String> response = root.get("/admin/api/reports/pilot?days=7");
        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        assertThat(body).contains("\"tenant\":\"default\"").contains("\"conversations\":2").contains("\"turns\":3")
                .contains("\"escalated\":1").contains("\"flagged\":1").contains("\"deflectionRate\":0.5")
                .contains("\"inputTokens\":2500").contains("\"outputTokens\":250").contains("\"unmeteredTurns\":0")
                // 2000 input at 5 per million plus 200 output at 25 per million; the mystery model is named, not costed
                .contains("\"costUsd\":0.015").contains("\"unpricedModels\":[\"mystery-model\"]").contains("\"costPerConversationUsd\":0.0075")
                .contains("\"evaluation\":null").contains("\"definitions\":{");
        assertThat(root.get("/admin/api/reports/pilot?days=0").statusCode()).isEqualTo(400);
        assertThat(root.get("/admin/api/reports/pilot?tenant=acme&days=7").body())
                .as("another tenant, empty").contains("\"tenant\":\"acme\"").contains("\"conversations\":0").contains("\"deflectionRate\":null")
                .contains("\"costPerConversationUsd\":null");
    }

    @Test
    @DisplayName("a tenant's admin gets their own report and cannot ask for another tenant's")
    void scopedToTheCallersTenant() throws Exception {
        AdminBrowser carol = AdminBrowser.signedIn(port, "carol", PASSWORD);
        assertThat(carol.get("/admin/api/reports/pilot").body()).contains("\"tenant\":\"acme\"");
        assertThat(carol.get("/admin/api/reports/pilot?tenant=default").statusCode()).isEqualTo(403);
    }
}
