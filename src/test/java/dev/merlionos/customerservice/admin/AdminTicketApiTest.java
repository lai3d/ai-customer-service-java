package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.PostgresTestcontainer;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ticket loop over the admin API, signed in as staff, against the rows: what the queue
 * shows, what each action does and refuses, what the conversation view returns and records.
 * Same context configuration as {@code CustomerServiceApplicationTests}, for the reason in
 * CLAUDE.md; data is set up through beans.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rag.import-mode=startup")
@AutoConfigureObservability
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AdminTicketApiTest {

    static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffAccounts accounts;

    @Autowired
    TicketOperations tickets;

    String conversation;
    String number;

    @BeforeEach
    void freshStaffAndOneTicket() {
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM admin_audit");
        jdbc.update("DELETE FROM staff_account");
        accounts.create("root", PASSWORD, StaffRole.ADMIN, "seed");
        accounts.create("alice", PASSWORD, StaffRole.SUPPORT, "root");
        accounts.create("bob", PASSWORD, StaffRole.SUPPORT, "root");
        conversation = UUID.randomUUID().toString();
        number = tickets.create(new TicketRequest(UUID.randomUUID().toString(), conversation,
                "Parcel arrived crushed " + conversation.substring(0, 8), "returns", "ORD-10042")).ticket().ticketNumber();
        Instant t0 = Instant.now().minusSeconds(60);
        jdbc.update("INSERT INTO spring_ai_chat_memory (conversation_id, content, type, \"timestamp\") VALUES (?, ?, ?, ?)",
                conversation, "My parcel arrived crushed, order ORD-10042", "USER", Timestamp.from(t0));
        jdbc.update("INSERT INTO spring_ai_chat_memory (conversation_id, content, type, \"timestamp\") VALUES (?, ?, ?, ?)",
                conversation, "Sorry to hear that. I have raised **" + number + "** for you.", "ASSISTANT",
                Timestamp.from(t0.plusSeconds(5)));
    }

    private int audits(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit WHERE action = ?", Integer.class, action);
    }

    @Test
    @DisplayName("the queue lists the open ticket, unassigned, and the detail carries an empty history")
    void queueAndDetail() throws Exception {
        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);

        HttpResponse<String> queue = alice.get("/admin/api/tickets?owner=-&state=open&size=100");
        assertThat(queue.statusCode()).isEqualTo(200);
        assertThat(queue.body()).contains("\"ticketNumber\":\"" + number + "\"", "\"state\":\"open\"", "\"total\":");

        HttpResponse<String> detail = alice.get("/admin/api/tickets/" + number);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("\"conversationId\":\"" + conversation + "\"", "\"version\":0", "\"history\":[]");

        assertThat(alice.get("/admin/api/tickets/TKT-0").statusCode()).isEqualTo(404);
        assertThat(new AdminBrowser(port).get("/admin/api/tickets").statusCode()).as("not without a login").isEqualTo(401);
    }

    @Test
    @DisplayName("claim, note, resolve with a conclusion, close: attributed to the signed-in account, versions moving")
    void theLoop() throws Exception {
        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);
        String base = "/admin/api/tickets/" + number;

        HttpResponse<String> claimed = alice.postJson(base + "/claim", "{\"expectedVersion\":0}");
        assertThat(claimed.statusCode()).isEqualTo(200);
        assertThat(claimed.body()).contains("\"state\":\"claimed\"", "\"owner\":\"alice\"", "\"version\":1");

        assertThat(alice.postJson(base + "/note", "{\"expectedVersion\":1,\"text\":\"Photos requested.\"}").statusCode()).isEqualTo(200);
        HttpResponse<String> noConclusion = alice.postJson(base + "/resolve", "{\"expectedVersion\":2,\"text\":\"\"}");
        assertThat(noConclusion.statusCode()).isEqualTo(422);
        assertThat(noConclusion.body()).contains("without a conclusion");
        assertThat(alice.postJson(base + "/resolve", "{\"expectedVersion\":2,\"text\":\"Replacement sent.\"}").statusCode()).isEqualTo(200);
        HttpResponse<String> closed = alice.postJson(base + "/close", "{\"expectedVersion\":3}");
        assertThat(closed.statusCode()).isEqualTo(200);
        assertThat(closed.body()).contains("\"state\":\"closed\"", "\"version\":4");

        HttpResponse<String> detail = alice.get(base);
        assertThat(detail.body()).contains("\"kind\":\"claimed\"", "\"kind\":\"note\"", "\"kind\":\"resolved\"",
                "\"kind\":\"closed\"", "\"note\":\"Replacement sent.\"");
        assertThat(jdbc.queryForList("SELECT actor FROM ticket_event WHERE ticket_number = ?", String.class, number))
                .containsOnly("alice");
        assertThat(audits("refused")).as("the missing conclusion was a refusal, and refusals are recorded").isEqualTo(1);
    }

    @Test
    @DisplayName("a stale version is 409 and not recorded; someone else's ticket is 422 and recorded; an admin overrides")
    void conflictsRefusalsAndOverride() throws Exception {
        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);
        AdminBrowser bob = AdminBrowser.signedIn(port, "bob", PASSWORD);
        AdminBrowser root = AdminBrowser.signedIn(port, "root", PASSWORD);
        String base = "/admin/api/tickets/" + number;

        assertThat(alice.postJson(base + "/claim", "{\"expectedVersion\":0}").statusCode()).isEqualTo(200);
        HttpResponse<String> stale = bob.postJson(base + "/claim", "{\"expectedVersion\":0}");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).contains("changed since it was read");
        assertThat(audits("refused")).as("losing a race is not a refusal").isZero();

        HttpResponse<String> notOwner = bob.postJson(base + "/resolve", "{\"expectedVersion\":1,\"text\":\"done\"}");
        assertThat(notOwner.statusCode()).isEqualTo(422);
        assertThat(notOwner.body()).contains("owned by alice");
        assertThat(jdbc.queryForMap("SELECT actor, action, target FROM admin_audit"))
                .containsEntry("actor", "bob").containsEntry("action", "refused").containsEntry("target", number);

        HttpResponse<String> unknownAssignee = root.postJson(base + "/assign", "{\"expectedVersion\":1,\"assignee\":\"carol\"}");
        assertThat(unknownAssignee.statusCode()).isEqualTo(422);
        assertThat(unknownAssignee.body()).contains("no enabled staff account");
        HttpResponse<String> reassigned = root.postJson(base + "/assign", "{\"expectedVersion\":1,\"assignee\":\"Bob\"}");
        assertThat(reassigned.statusCode()).isEqualTo(200);
        assertThat(reassigned.body()).contains("\"owner\":\"bob\"");
        assertThat(root.postJson(base + "/resolve", "{\"expectedVersion\":2,\"text\":\"Admin closed it out.\"}").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT owner FROM support_ticket WHERE ticket_number = ?", String.class, number))
                .as("an admin resolving keeps the owner on the ticket").isEqualTo("bob");
        assertThat(audits("refused")).isEqualTo(2);
    }

    @Test
    @DisplayName("opening the conversation returns what is stored, says what is not, and is recorded against the viewer")
    void conversationViewIsRecorded() throws Exception {
        AdminBrowser bob = AdminBrowser.signedIn(port, "bob", PASSWORD);

        HttpResponse<String> view = bob.get("/admin/api/tickets/" + number + "/conversation");
        assertThat(view.statusCode()).isEqualTo(200);
        assertThat(view.body())
                .contains("\"conversationId\":\"" + conversation + "\"")
                .contains("\"type\":\"USER\"", "My parcel arrived crushed")
                .contains("\"type\":\"ASSISTANT\"", "I have raised **" + number + "**")
                .contains("\"ticketNumber\":\"" + number + "\"")
                .contains("not persisted");
        assertThat(view.body().indexOf("\"USER\"")).isLessThan(view.body().indexOf("\"ASSISTANT\""));

        List<Map<String, Object>> audit = jdbc.queryForList("SELECT actor, action, target, detail FROM admin_audit");
        assertThat(audit).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("actor", "bob").containsEntry("action", "viewed_conversation")
                    .containsEntry("target", conversation);
            assertThat(String.valueOf(row.get("detail"))).contains(number);
        });
    }

    @Test
    @DisplayName("a support member refused by role on an admin endpoint is recorded, with the request they made")
    void roleRefusalIsRecorded() throws Exception {
        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);

        assertThat(alice.get("/admin/api/staff").statusCode()).isEqualTo(403);

        assertThat(jdbc.queryForMap("SELECT actor, action, target FROM admin_audit"))
                .containsEntry("actor", "alice").containsEntry("action", "refused")
                .containsEntry("target", "GET /admin/api/staff");
    }
}
