package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.evaluation.EvaluationRun;
import dev.merlionos.customerservice.evaluation.Evaluations;
import dev.merlionos.customerservice.evaluation.GoldenCase;
import dev.merlionos.customerservice.evaluation.GoldenCases;
import dev.merlionos.customerservice.evaluation.GoldenSeeder;
import dev.merlionos.customerservice.evaluation.QualityMetrics;
import dev.merlionos.customerservice.chat.ChatService;
import dev.merlionos.customerservice.tenancy.Conversations;
import dev.merlionos.customerservice.tenancy.Tenant;
import dev.merlionos.customerservice.tenancy.Tenants;
import dev.merlionos.customerservice.ticket.api.TicketOperations;
import dev.merlionos.customerservice.ticket.api.TicketRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * The evaluation end to end with the model stubbed: the seeded golden set, a run over the
 * admin API scored case by case, the gauges it feeds, and deflection counting customers
 * and never evaluation conversations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.rag.import-mode=startup")
@AutoConfigureObservability
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AdminEvaluationApiTest {

    static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StaffAccounts accounts;
    @Autowired Tenants tenants;
    @Autowired GoldenCases goldenCases;
    @Autowired Evaluations evaluations;
    @Autowired GoldenSeeder seeder;
    @Autowired QualityMetrics quality;
    @Autowired ChatService chatService;
    @Autowired Conversations conversations;
    @Autowired TicketOperations tickets;
    @Autowired TestRestTemplate rest;
    @MockitoBean AnthropicChatModel chatModel;

    @BeforeEach
    void staffAndAModel() {
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM admin_audit");
        jdbc.update("DELETE FROM staff_account");
        accounts.create("root", PASSWORD, StaffRole.ADMIN, null, "seed");
        accounts.create("alice", PASSWORD, StaffRole.SUPPORT, Tenant.DEFAULT, "root");
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("You can return it within 30 days; we email a prepaid label."))),
                ChatResponseMetadata.builder().model("stub-model").usage(new DefaultUsage(100, 20)).build());
        given(chatModel.call(any(Prompt.class))).willReturn(response);
        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(response));
    }

    @Test
    @DisplayName("the bundled golden set is seeded once for the default tenant, and never again")
    void seeded() {
        assertThat(goldenCases.of(Tenant.DEFAULT)).hasSizeGreaterThanOrEqualTo(40)
                .allSatisfy(c -> assertThat(c.createdBy()).isEqualTo(GoldenSeeder.BUNDLED_ACTOR));
        assertThat(seeder.seedBundledIfEmpty()).isZero();
        assertThat(goldenCases.of(Tenant.DEFAULT)).filteredOn(c -> c.expectTool() != null).hasSize(3);
        assertThat(goldenCases.of(Tenant.DEFAULT)).filteredOn(GoldenCase::expectRefusal).hasSize(2);
    }

    @Test
    @DisplayName("a run over the admin API scores every case, records tokens, feeds the gauges, and is audited; refusals are 422")
    void aRun() throws Exception {
        String tenant = "eval-" + UUID.randomUUID().toString().substring(0, 6);
        tenants.create(tenant, "Eval");
        AdminBrowser root = AdminBrowser.signedIn(port, "root", PASSWORD);
        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);

        assertThat(root.postJson("/admin/api/evaluation/runs?tenant=" + tenant, "{}").statusCode()).as("no cases yet").isEqualTo(422);
        HttpResponse<String> bad = root.postJson("/admin/api/evaluation/cases?tenant=" + tenant, "{\"question\":\"q\",\"language\":\"en\"}");
        assertThat(bad.statusCode()).as("nothing to check").isEqualTo(422);
        HttpResponse<String> passing = root.postJson("/admin/api/evaluation/cases?tenant=" + tenant,
                "{\"question\":\"Can I return it?\",\"language\":\"en\",\"mustContain\":[\"30 days\"],\"anyOf\":[\"prepaid\",\"label\"]}");
        assertThat(passing.statusCode()).isEqualTo(201);
        HttpResponse<String> failing = root.postJson("/admin/api/evaluation/cases?tenant=" + tenant,
                "{\"question\":\"Where is ORD-1?\",\"language\":\"en\",\"mustContain\":[\"in transit\"],\"expectTool\":\"lookup_order_status\"}");
        assertThat(failing.statusCode()).isEqualTo(201);
        assertThat(alice.postJson("/admin/api/evaluation/cases?tenant=" + tenant, "{\"question\":\"x\",\"language\":\"en\",\"mustContain\":[\"y\"]}").statusCode())
                .as("support may read the set, not write it").isEqualTo(403);
        assertThat(alice.get("/admin/api/evaluation/cases?tenant=" + tenant).statusCode())
                .as("a default-tenant member naming another tenant is refused").isEqualTo(403);
        assertThat(root.get("/admin/api/evaluation/cases?tenant=" + tenant).body()).contains("Can I return it?", "Where is ORD-1?");

        HttpResponse<String> started = root.postJson("/admin/api/evaluation/runs?tenant=" + tenant, "{\"note\":\"first\"}");
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        assertThat(started.body()).contains("\"state\":\"running\"", "\"cases\":2", "\"note\":\"first\"");
        long id = Long.parseLong(started.body().replaceAll(".*?\"id\":(\\d+).*", "$1"));
        String detail = awaitRun(root, tenant, id);
        assertThat(detail).contains("\"state\":\"done\"", "\"cases\":2", "\"passed\":1", "\"retrievalHits\":2", "\"answerPasses\":1",
                "\"toolPasses\":1", "\"inputTokens\":200", "\"outputTokens\":40", "\"model\":\"stub-model\"");
        assertThat(detail).contains("answer lacks \\\"in transit\\\"; tool lookup_order_status did not run");
        assertThat(root.get("/admin/api/evaluation/runs?tenant=" + tenant).body()).contains("\"id\":" + id);

        EvaluationRun run = evaluations.find(tenant, id).orElseThrow();
        assertThat(run.passRatio()).isEqualTo(0.5);
        assertThat(evaluations.resultsOf(id)).hasSize(2).allSatisfy(r -> {
            assertThat(r.answer()).contains("30 days");
            assertThat(r.conversationId()).isNotBlank();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation WHERE tenant_id = ? AND kind = 'evaluation'", Integer.class, tenant))
                .as("one evaluation conversation per case").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit WHERE action = 'evaluated' AND target = ?", Integer.class, tenant)).isEqualTo(1);

        String exposition = rest.getForObject("/actuator/prometheus", String.class);
        assertThat(gauge(exposition, "evaluation_pass_ratio", tenant)).isEqualTo(0.5);
        assertThat(gauge(exposition, "evaluation_cases", tenant)).isEqualTo(2.0);
        assertThat(gauge(exposition, "evaluation_retrieval_hit_ratio", tenant)).isEqualTo(1.0);
        assertThat(gauge(exposition, "evaluation_tool_pass_ratio", tenant)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("deflection counts customer conversations in the window and never evaluation ones; a ticket is an escalation")
    void deflection() throws Exception {
        String tenant = "defl-" + UUID.randomUUID().toString().substring(0, 6);
        tenants.create(tenant, "Deflection");
        AdminBrowser root = AdminBrowser.signedIn(port, "root", PASSWORD);
        assertThat(root.get("/admin/api/evaluation/deflection?tenant=" + tenant).body()).contains("\"conversations\":0", "\"deflectionRate\":0.0");

        // Three customers; one of them raises a ticket. An evaluation conversation alongside.
        String c1 = conversations.resolve(tenant, "web-1");
        String c2 = conversations.resolve(tenant, "web-2");
        String c3 = conversations.resolve(tenant, "web-3");
        for (String c : List.of(c1, c2, c3)) {
            chatService.stream(tenant, c, "how long do I have to return?").blockLast();
        }
        tickets.create(new TicketRequest(tenant, UUID.randomUUID().toString(), c2, "wants a person", "other", null));
        String eval = conversations.createEvaluation(tenant, "eval-x");
        // A fourth customer whose ticket went to the tenant's panel: no row of ours, only the tool call that raised it.
        String c4 = conversations.resolve(tenant, "web-4");
        chatService.stream(tenant, c4, "please get me a person").blockLast();
        String turn4 = jdbc.queryForObject("SELECT turn_id FROM conversation_turn WHERE conversation_id = ?", String.class, c4);
        jdbc.update("INSERT INTO turn_tool_call (turn_id, tool, outcome, occurred_at) VALUES (?, 'create_support_ticket', 'created', now())", turn4);
        chatService.stream(tenant, eval, "how long do I have to return?").blockLast();

        String view = root.get("/admin/api/evaluation/deflection?tenant=" + tenant + "&days=7").body();
        assertThat(view).contains("\"conversations\":4", "\"escalated\":2", "\"flagged\":0", "\"days\":7");
        assertThat(view).contains("\"deflectionRate\":0.5");
        quality.sample();
        String exposition = rest.getForObject("/actuator/prometheus", String.class);
        assertThat(gauge(exposition, "chat_window_conversations", tenant)).isEqualTo(4.0);
        assertThat(gauge(exposition, "chat_escalation_rate", tenant)).isEqualTo(0.5);
        assertThat(gauge(exposition, "chat_deflection_rate", tenant)).isEqualTo(0.5);
        assertThat(gauge(exposition, "chat_deflection_rate", Tenant.DEFAULT)).as("the default tenant is pre-registered").isNotNull();
    }

    /** The value of a gauge line for a tenant, whatever other labels the registry adds. */
    private static Double gauge(String exposition, String name, String tenant) {
        return exposition.lines()
                .filter(line -> line.startsWith(name + "{") && line.contains("tenant=\"" + tenant + "\""))
                .map(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .findFirst().orElse(null);
    }

    private String awaitRun(AdminBrowser browser, String tenant, long id) throws Exception {
        for (int i = 0; i < 240; i++) {
            String body = browser.get("/admin/api/evaluation/runs/" + id + "?tenant=" + tenant).body();
            if (!body.contains("\"state\":\"running\"")) {
                return body;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("run " + id + " still running after 60 s");
    }
}
