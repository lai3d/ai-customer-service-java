package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.chat.TurnRecorder;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flagging a recorded answer and handling the flag, over the admin API. Same context
 * configuration as {@code CustomerServiceApplicationTests}; data through beans.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rag.import-mode=startup")
@AutoConfigureObservability
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AdminFeedbackApiTest {

    static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffAccounts accounts;

    @Autowired
    TurnRecorder recorder;

    String conversation;
    String turn;

    @BeforeEach
    void oneRecordedTurn() {
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM admin_audit");
        jdbc.update("DELETE FROM staff_account");
        jdbc.update("DELETE FROM answer_feedback");
        jdbc.update("DELETE FROM turn_retrieval");
        jdbc.update("DELETE FROM turn_tool_call");
        jdbc.update("DELETE FROM conversation_turn");
        accounts.create("alice", PASSWORD, StaffRole.SUPPORT, "root");
        accounts.create("bob", PASSWORD, StaffRole.SUPPORT, "root");
        conversation = UUID.randomUUID().toString();
        turn = UUID.randomUUID().toString();
        recorder.start(turn, conversation, TurnRecorder.Path.STREAM, "运费多少钱");
        recorder.finish(turn, TurnRecorder.Outcome.COMPLETED, "免运费。", "claude-opus-5", 10, 5, null, null);
    }

    private static long id(String body) {
        Matcher m = Pattern.compile("\"id\":(\\d+)").matcher(body);
        assertThat(m.find()).as("an id in " + body).isTrue();
        return Long.parseLong(m.group(1));
    }

    @Test
    @DisplayName("a flag on a recorded turn is created, listed as open, shown on the conversation, and closed once with a conclusion")
    void flagAndHandle() throws Exception {
        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);
        AdminBrowser bob = AdminBrowser.signedIn(port, "bob", PASSWORD);

        HttpResponse<String> created = alice.postJson("/admin/api/feedback",
                "{\"turnId\":\"" + turn + "\",\"issue\":\"Incomplete\",\"note\":\"Does not mention the 50 dollar threshold\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"issue\":\"incomplete\"", "\"state\":\"open\"", "\"reportedBy\":\"alice\"",
                "\"conversationId\":\"" + conversation + "\"", "\"version\":0");
        long id = id(created.body());

        assertThat(alice.get("/admin/api/feedback?state=open").body()).contains("\"total\":1", "\"id\":" + id);
        assertThat(alice.get("/admin/api/feedback?state=handled").body()).contains("\"total\":0");
        assertThat(alice.get("/admin/api/feedback?state=bogus").statusCode()).isEqualTo(400);
        assertThat(alice.get("/admin/api/conversations/" + conversation).body())
                .as("the conversation detail carries its flags").contains("\"feedback\":[{\"id\":" + id);

        HttpResponse<String> noConclusion = bob.postJson("/admin/api/feedback/" + id + "/handle",
                "{\"state\":\"handled\",\"conclusion\":\"\",\"expectedVersion\":0}");
        assertThat(noConclusion.statusCode()).isEqualTo(422);
        assertThat(noConclusion.body()).contains("needs a conclusion");

        HttpResponse<String> handled = bob.postJson("/admin/api/feedback/" + id + "/handle",
                "{\"state\":\"handled\",\"conclusion\":\"FAQ entry shipping-cost revised to name the threshold.\",\"expectedVersion\":0}");
        assertThat(handled.statusCode()).isEqualTo(200);
        assertThat(handled.body()).contains("\"state\":\"handled\"", "\"handledBy\":\"bob\"", "\"version\":1");

        HttpResponse<String> stale = alice.postJson("/admin/api/feedback/" + id + "/handle",
                "{\"state\":\"dismissed\",\"conclusion\":null,\"expectedVersion\":0}");
        assertThat(stale.statusCode()).isEqualTo(409);
        HttpResponse<String> again = alice.postJson("/admin/api/feedback/" + id + "/handle",
                "{\"state\":\"dismissed\",\"conclusion\":null,\"expectedVersion\":1}");
        assertThat(again.statusCode()).as("closing is final").isEqualTo(422);
        assertThat(again.body()).contains("already handled");

        assertThat(alice.get("/admin/api/feedback?state=handled").body()).contains("\"total\":1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit WHERE action = 'refused'", Integer.class))
                .as("two refusals, no conflict, recorded").isEqualTo(2);
    }

    @Test
    @DisplayName("a flag needs a recorded turn and a known issue; a dismissal needs no conclusion")
    void rulesAndDismissal() throws Exception {
        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);

        HttpResponse<String> unknownTurn = alice.postJson("/admin/api/feedback",
                "{\"turnId\":\"" + UUID.randomUUID() + "\",\"issue\":\"incorrect\",\"note\":null}");
        assertThat(unknownTurn.statusCode()).isEqualTo(422);
        assertThat(unknownTurn.body()).contains("no recorded turn");
        HttpResponse<String> badIssue = alice.postJson("/admin/api/feedback",
                "{\"turnId\":\"" + turn + "\",\"issue\":\"meh\",\"note\":null}");
        assertThat(badIssue.statusCode()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_feedback", Integer.class)).isZero();

        long id = id(alice.postJson("/admin/api/feedback",
                "{\"turnId\":\"" + turn + "\",\"issue\":\"other\",\"note\":\"tone\"}").body());
        HttpResponse<String> dismissed = alice.postJson("/admin/api/feedback/" + id + "/handle",
                "{\"state\":\"dismissed\",\"conclusion\":\"\",\"expectedVersion\":0}");
        assertThat(dismissed.statusCode()).isEqualTo(200);
        assertThat(dismissed.body()).contains("\"state\":\"dismissed\"", "\"conclusion\":null");
        assertThat(alice.get("/admin/api/feedback/" + id).body()).contains("\"handledBy\":\"alice\"");
        assertThat(alice.get("/admin/api/feedback/999999").statusCode()).isEqualTo(404);
        assertThat(new AdminBrowser(port).get("/admin/api/feedback").statusCode()).isEqualTo(401);
    }
}
