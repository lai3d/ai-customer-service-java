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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staff belong to a tenant (ADR 002 step 5): a tenant's admin sees and changes only their
 * tenant's rows, on every endpoint the admin has; platform staff see every tenant's, and
 * can narrow to one. Written as tenant B and read as tenant A: nothing. A row outside the
 * caller's tenant is missing, not forbidden; naming another tenant in {@code ?tenant=} is
 * refused and recorded. Same context as {@link AdminLoginTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rag.import-mode=startup")
@AutoConfigureObservability
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AdminTenantScopeTest {

    static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    int port;

    @Autowired JdbcTemplate jdbc;
    @Autowired StaffAccounts accounts;
    @Autowired Tenants tenants;
    @Autowired Conversations conversations;
    @Autowired TicketOperations tickets;
    @Autowired TurnRecorder recorder;
    @Autowired AnswerFeedback feedback;

    String defaultTicket;
    String acmeTicket;
    String defaultConversation;
    String acmeConversation;
    long defaultFlag;
    long acmeFlag;

    @BeforeEach
    void twoTenantsWithRows() {
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
        accounts.create("alice", PASSWORD, StaffRole.SUPPORT, Tenant.DEFAULT, "root");
        accounts.create("carol", PASSWORD, StaffRole.ADMIN, "acme", "root");

        defaultConversation = conversations.resolve(Tenant.DEFAULT, "web-" + UUID.randomUUID().toString().substring(0, 8));
        acmeConversation = conversations.resolve("acme", "acme-" + UUID.randomUUID().toString().substring(0, 8));
        defaultTicket = tickets.create(new TicketRequest(Tenant.DEFAULT, UUID.randomUUID().toString(), defaultConversation,
                "Default's parcel", "returns", "ORD-1")).ticket().ticketNumber();
        acmeTicket = tickets.create(new TicketRequest("acme", UUID.randomUUID().toString(), acmeConversation,
                "Acme's parcel", "returns", "ORD-2")).ticket().ticketNumber();
        String defaultTurn = UUID.randomUUID().toString();
        String acmeTurn = UUID.randomUUID().toString();
        recorder.start(defaultTurn, Tenant.DEFAULT, defaultConversation, TurnRecorder.Path.STREAM, "运费多少钱");
        recorder.finish(defaultTurn, TurnRecorder.Outcome.COMPLETED, "运费满 50 美元免运费。", "claude-opus-5", 10, 5, null, null);
        recorder.start(acmeTurn, "acme", acmeConversation, TurnRecorder.Path.STREAM, "hello acme");
        recorder.finish(acmeTurn, TurnRecorder.Outcome.COMPLETED, "hello", "claude-opus-5", 10, 5, null, null);
        defaultFlag = feedback.report(defaultTurn, "incorrect", "wrong", "alice").id();
        acmeFlag = feedback.report(acmeTurn, "incorrect", "wrong", "carol").id();
    }

    private List<Map<String, Object>> refusals() {
        return jdbc.queryForList("SELECT actor, target FROM admin_audit WHERE action = 'refused' ORDER BY id");
    }

    @Test
    @DisplayName("a tenant admin sees only their tenant: tickets, conversations, feedback, overview, knowledge, staff; the rest is missing or refused")
    void tenantAdminIsConfinedToTheirTenant() throws Exception {
        AdminBrowser carol = AdminBrowser.signedIn(port, "carol", PASSWORD);
        assertThat(carol.get("/admin/api/me").body())
                .isEqualTo("{\"username\":\"carol\",\"role\":\"admin\",\"tenant\":{\"id\":\"acme\",\"name\":\"Acme Retail\"}}");

        // Tickets
        HttpResponse<String> queue = carol.get("/admin/api/tickets");
        assertThat(queue.body()).contains("\"total\":1").contains(acmeTicket).doesNotContain(defaultTicket)
                .contains("\"tenantId\":\"acme\"");
        assertThat(carol.get("/admin/api/tickets/" + defaultTicket).statusCode()).as("another tenant's ticket is missing").isEqualTo(404);
        assertThat(carol.get("/admin/api/tickets/" + defaultTicket + "/conversation").statusCode()).isEqualTo(404);
        assertThat(carol.postJson("/admin/api/tickets/" + defaultTicket + "/claim", "{\"expectedVersion\":0}").statusCode())
                .as("and cannot be acted on").isEqualTo(404);
        assertThat(carol.postJson("/admin/api/tickets/" + acmeTicket + "/claim", "{\"expectedVersion\":0}").statusCode()).isEqualTo(200);
        assertThat(carol.postJson("/admin/api/tickets/" + acmeTicket + "/assign", "{\"expectedVersion\":1,\"assignee\":\"alice\"}").statusCode())
                .as("another tenant's staff cannot be assigned").isEqualTo(422);
        assertThat(carol.postJson("/admin/api/tickets/" + acmeTicket + "/assign", "{\"expectedVersion\":1,\"assignee\":\"root\"}").statusCode())
                .as("platform staff can").isEqualTo(200);
        assertThat(carol.get("/admin/api/tickets?tenant=default").statusCode()).as("naming another tenant is refused").isEqualTo(403);
        assertThat(carol.get("/admin/api/tickets?tenant=acme").statusCode()).as("naming your own is fine").isEqualTo(200);

        // Conversations
        assertThat(carol.get("/admin/api/conversations").body()).contains("\"total\":1").contains(acmeConversation)
                .doesNotContain(defaultConversation).contains("\"tenantId\":\"acme\"").contains("\"externalId\":\"acme-");
        assertThat(carol.get("/admin/api/conversations/" + defaultConversation).statusCode()).isEqualTo(404);
        assertThat(carol.get("/admin/api/conversations/" + acmeConversation).body())
                .contains("\"tenantId\":\"acme\"").contains("\"externalId\":\"acme-").contains("hello acme");
        assertThat(carol.get("/admin/api/conversations?tenant=default").statusCode()).isEqualTo(403);

        // Feedback
        assertThat(carol.get("/admin/api/feedback").body()).contains("\"total\":1").contains("\"id\":" + acmeFlag)
                .doesNotContain("\"id\":" + defaultFlag);
        assertThat(carol.get("/admin/api/feedback/" + defaultFlag).statusCode()).isEqualTo(404);
        assertThat(carol.postJson("/admin/api/feedback/" + defaultFlag + "/handle",
                "{\"state\":\"dismissed\",\"expectedVersion\":0}").statusCode()).isEqualTo(404);
        assertThat(carol.get("/admin/api/feedback/" + acmeFlag).statusCode()).isEqualTo(200);

        // Overview: acme's numbers, not everyone's
        HttpResponse<String> overview = carol.get("/admin/api/overview");
        assertThat(overview.statusCode()).isEqualTo(200);
        assertThat(overview.body()).contains("\"key\":\"turns\",\"label\":\"Turns\",\"value\":1")
                .contains("for tenant 'acme'");
        assertThat(carol.get("/admin/api/overview?tenant=default").statusCode()).isEqualTo(403);

        // Knowledge: the session's tenant wins over the parameter
        assertThat(carol.get("/admin/api/knowledge/versions").body()).contains("\"tenant\":\"acme\"");
        assertThat(carol.get("/admin/api/knowledge/versions?tenant=default").statusCode()).isEqualTo(403);
        assertThat(carol.get("/admin/api/knowledge/entries").body()).as("acme has no knowledge yet").isEqualTo("[]");

        // Tenants are platform's
        assertThat(carol.get("/admin/api/tenants").statusCode()).isEqualTo(403);
        assertThat(carol.postJson("/admin/api/tenants", "{\"id\":\"evil\",\"name\":\"x\"}").statusCode()).isEqualTo(403);

        // Staff: only acme's, created into acme, platform accounts invisible
        assertThat(carol.get("/admin/api/staff").body()).contains("\"username\":\"carol\"")
                .doesNotContain("\"username\":\"root\"").doesNotContain("\"username\":\"alice\"");
        HttpResponse<String> created = carol.postJson("/admin/api/staff",
                "{\"username\":\"dave\",\"password\":\"support-password-1\",\"role\":\"support\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"tenantId\":\"acme\"");
        assertThat(carol.postJson("/admin/api/staff",
                "{\"username\":\"eve\",\"password\":\"support-password-1\",\"role\":\"support\",\"tenantId\":\"default\"}").statusCode())
                .as("another tenant is refused, not redirected").isEqualTo(403);
        assertThat(carol.postJson("/admin/api/staff",
                "{\"username\":\"eve\",\"password\":\"support-password-1\",\"role\":\"admin\",\"tenantId\":\"platform\"}").statusCode())
                .as("a tenant admin cannot make platform staff").isEqualTo(201);
        assertThat(accounts.find("eve").orElseThrow().tenantId()).as("it lands in their own tenant").isEqualTo("acme");
        assertThat(carol.postJson("/admin/api/staff/root/enabled", "{\"enabled\":false}").statusCode()).isEqualTo(404);
        assertThat(carol.postJson("/admin/api/staff/alice/password", "{\"password\":\"twelve-characters\"}").statusCode()).isEqualTo(404);

        assertThat(refusals()).extracting(row -> row.get("target").toString())
                .containsExactly(acmeTicket, "GET /admin/api/tickets", "GET /admin/api/conversations", "GET /admin/api/overview",
                        "GET /admin/api/knowledge/versions", "GET /admin/api/tenants", "POST /admin/api/tenants", "POST /admin/api/staff");
    }

    @Test
    @DisplayName("an evaluation run's conversation, its ticket and its flag are a rehearsal: out of the list, the queue and the overview unless asked for by kind")
    void evaluationConversationsAreNotCustomers() throws Exception {
        String rehearsal = conversations.createEvaluation(Tenant.DEFAULT, "eval-" + UUID.randomUUID().toString().substring(0, 8));
        String turn = UUID.randomUUID().toString();
        recorder.start(turn, Tenant.DEFAULT, rehearsal, TurnRecorder.Path.BLOCKING, "rehearsal question");
        recorder.finish(turn, TurnRecorder.Outcome.COMPLETED, "rehearsal answer", "claude-opus-5", 10, 5, null, null);
        String rehearsalTicket = tickets.create(new TicketRequest(Tenant.DEFAULT, UUID.randomUUID().toString(), rehearsal,
                "Rehearsal parcel", "returns", "ORD-9")).ticket().ticketNumber();
        feedback.report(turn, "incorrect", "rehearsal", "alice");

        AdminBrowser root = AdminBrowser.signedIn(port, "root", PASSWORD);
        assertThat(root.get("/admin/api/conversations").body()).contains("\"total\":2").doesNotContain(rehearsal);
        assertThat(root.get("/admin/api/conversations?kind=evaluation").body()).contains("\"total\":1").contains(rehearsal);
        assertThat(root.get("/admin/api/conversations?kind=bogus").statusCode()).isEqualTo(400);
        assertThat(root.get("/admin/api/conversations/" + rehearsal).statusCode()).as("openable by id, for the run's record").isEqualTo(200);
        assertThat(root.get("/admin/api/tickets").body()).contains("\"total\":2").doesNotContain(rehearsalTicket);
        assertThat(root.get("/admin/api/tickets/" + rehearsalTicket).statusCode()).isEqualTo(200);
        String overview = root.get("/admin/api/overview?tenant=default").body();
        assertThat(overview).contains("\"key\":\"turns\",\"label\":\"Turns\",\"value\":1")
                .contains("\"key\":\"open\",\"label\":\"Open\",\"value\":1")
                .contains("\"key\":\"openFlags\",\"label\":\"Open flags\",\"value\":1");
    }

    @Test
    @DisplayName("platform staff see every tenant, narrow with ?tenant=, and the default tenant's support sees default only")
    void platformSeesAllAndSupportSeesTheirOwn() throws Exception {
        AdminBrowser root = AdminBrowser.signedIn(port, "root", PASSWORD);
        assertThat(root.get("/admin/api/tickets").body()).contains("\"total\":2");
        assertThat(root.get("/admin/api/tickets?tenant=acme").body()).contains("\"total\":1").contains(acmeTicket);
        assertThat(root.get("/admin/api/conversations").body()).contains("\"total\":2");
        assertThat(root.get("/admin/api/conversations?tenant=default").body()).contains("\"total\":1").contains(defaultConversation);
        assertThat(root.get("/admin/api/conversations?conversationId=" + conversations.externalIdOf(acmeConversation).orElseThrow()).body())
                .as("the customer's own id finds it too").contains("\"total\":1").contains(acmeConversation);
        assertThat(root.get("/admin/api/feedback").body()).contains("\"total\":2");
        assertThat(root.get("/admin/api/overview?tenant=acme").body()).contains("\"key\":\"turns\",\"label\":\"Turns\",\"value\":1");
        assertThat(root.get("/admin/api/overview").body()).contains("\"key\":\"turns\",\"label\":\"Turns\",\"value\":2")
                .as("everyone's numbers, the default tenant's knowledge").contains("for tenant 'default'");
        assertThat(root.get("/admin/api/staff").body()).contains("\"username\":\"root\"", "\"username\":\"alice\"", "\"username\":\"carol\"");
        assertThat(root.get("/admin/api/staff?tenant=platform").body()).contains("\"username\":\"root\"").doesNotContain("carol");
        assertThat(root.get("/admin/api/staff?tenant=acme").body()).contains("\"username\":\"carol\"").doesNotContain("alice");
        HttpResponse<String> platformAdmin = root.postJson("/admin/api/staff",
                "{\"username\":\"kim\",\"password\":\"second-admin-password\",\"role\":\"admin\",\"tenantId\":\"platform\"}");
        assertThat(platformAdmin.body()).contains("\"tenantId\":null");
        assertThat(root.postJson("/admin/api/staff",
                "{\"username\":\"pat\",\"password\":\"support-password-1\",\"role\":\"support\",\"tenantId\":\"platform\"}").statusCode())
                .as("platform support does not exist").isEqualTo(400);
        assertThat(root.get("/admin/api/tickets/" + acmeTicket).statusCode()).isEqualTo(200);
        assertThat(root.get("/admin/api/conversations/" + acmeConversation).body()).contains("\"externalId\":\"acme-");

        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);
        assertThat(alice.get("/admin/api/me").body()).contains("\"tenant\":{\"id\":\"default\"");
        assertThat(alice.get("/admin/api/tickets").body()).contains("\"total\":1").contains(defaultTicket);
        assertThat(alice.get("/admin/api/tickets/" + acmeTicket).statusCode()).isEqualTo(404);
        assertThat(alice.get("/admin/api/feedback").body()).contains("\"total\":1").contains("\"id\":" + defaultFlag);
        assertThat(refusals()).isEmpty();
    }
}
