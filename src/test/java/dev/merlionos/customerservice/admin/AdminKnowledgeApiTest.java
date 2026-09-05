package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.chat.TurnRecorder;
import dev.merlionos.customerservice.rag.api.KnowledgeAdmin;
import dev.merlionos.customerservice.rag.api.KnowledgeVersion;
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

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The knowledge base over the admin API: drafts for support, retire, publish and rollback for
 * admins, the publication as a job to poll, the preview, and the audit rows. Shares
 * {@code CustomerServiceApplicationTests}' context; nothing in that context measures
 * retrieval, so publishing here changes nothing another test asserts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rag.import-mode=startup")
@AutoConfigureObservability
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AdminKnowledgeApiTest {

    static final String PASSWORD = "a-long-enough-password";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffAccounts accounts;

    @Autowired
    KnowledgeAdmin knowledge;

    @Autowired
    TurnRecorder recorder;

    @BeforeEach
    void staff() {
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM admin_audit");
        jdbc.update("DELETE FROM staff_account");
        accounts.create("root", PASSWORD, StaffRole.ADMIN, "seed");
        accounts.create("alice", PASSWORD, StaffRole.SUPPORT, "root");
    }

    private static HttpResponse<String> put(AdminBrowser browser, String path, String json) throws Exception {
        return browser.client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + browser.port + path))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", browser.csrf())
                .PUT(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String activeVersion() {
        return knowledge.activeVersion().orElseThrow();
    }

    /**
     * The publication runs off the request thread; wait for its version row to reach a
     * terminal state. {@code ready} is not one on this path: the build marks the row ready
     * and the switch marks it active a moment later, and CI once read it in between. A row
     * that stays ready because the switch lost a race is recorded as a refusal, which is the
     * one case ready is final.
     */
    private KnowledgeVersion awaitPublication(String createdBy, String note) throws InterruptedException {
        for (int i = 0; i < 240; i++) {
            Optional<KnowledgeVersion> version = knowledge.versions().stream()
                    .filter(v -> createdBy.equals(v.createdBy()) && note.equals(v.note()))
                    .filter(v -> v.state().equals("active") || v.state().equals("failed")
                            || (v.state().equals("ready") && jdbc.queryForObject(
                            "SELECT count(*) FROM admin_audit WHERE action = 'refused' AND target = 'knowledge'", Integer.class) > 0))
                    .findFirst();
            if (version.isPresent()) {
                return version.get();
            }
            Thread.sleep(250);
        }
        throw new AssertionError("no finished publication by " + createdBy + " after 60 s: " + knowledge.versions());
    }

    @Test
    @DisplayName("support drafts, an admin publishes as a job, the new version is active, retrieval and preview follow, and both are recorded")
    void draftPublishPreview() throws Exception {
        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);
        AdminBrowser root = AdminBrowser.signedIn(port, "root", PASSWORD);
        String entry = "test-" + UUID.randomUUID().toString().substring(0, 8);
        String before = activeVersion();

        HttpResponse<String> created = alice.postJson("/admin/api/knowledge/entries/" + entry, "{\"category\":\"orders\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        HttpResponse<String> draft = put(alice, "/admin/api/knowledge/entries/" + entry + "/drafts/en",
                "{\"question\":\"Can I get gift wrapping?\",\"answer\":\"Yes, at checkout, for 3 dollars an item.\",\"note\":\"new\"}");
        assertThat(draft.statusCode()).isEqualTo(200);
        assertThat(draft.body()).contains("\"state\":\"draft\"", "\"createdBy\":\"alice\"");
        assertThat(alice.get("/admin/api/knowledge/entries/" + entry).body()).contains("\"revisions\":[{");
        assertThat(alice.get("/admin/api/knowledge/entries").body()).contains("\"entryId\":\"shipping-cost\"");

        assertThat(alice.postJson("/admin/api/knowledge/publish", "{\"note\":\"x\",\"expectedActive\":null}").statusCode())
                .as("publishing is an admin operation").isEqualTo(403);
        HttpResponse<String> stale = root.postJson("/admin/api/knowledge/publish", "{\"note\":\"x\",\"expectedActive\":\"nope\"}");
        assertThat(stale.statusCode()).isEqualTo(409);

        HttpResponse<String> started = root.postJson("/admin/api/knowledge/publish",
                "{\"note\":\"gift wrapping\",\"expectedActive\":\"" + before + "\"}");
        assertThat(started.statusCode()).isEqualTo(202);
        KnowledgeVersion published = awaitPublication("root", "gift wrapping");
        assertThat(published.state()).as(String.valueOf(published.error())).isEqualTo("active");
        String after = activeVersion();
        assertThat(after).isEqualTo(published.version()).isNotEqualTo(before);
        assertThat(root.get("/admin/api/knowledge/versions").body()).contains("\"active\":\"" + after + "\"", "\"note\":\"gift wrapping\"");
        assertThat(root.get("/admin/api/knowledge/versions/" + after).body()).contains("\"state\":\"active\"", "\"createdBy\":\"root\"");

        HttpResponse<String> preview = alice.postJson("/admin/api/knowledge/preview",
                "{\"text\":\"gift wrapping\",\"version\":null,\"topK\":3}");
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.body()).contains("\"entry_id\":\"" + entry + "\"");
        HttpResponse<String> old = alice.postJson("/admin/api/knowledge/preview",
                "{\"text\":\"gift wrapping\",\"version\":\"" + before + "\",\"topK\":3}");
        assertThat(old.body()).doesNotContain("\"entry_id\":\"" + entry + "\"");

        HttpResponse<String> back = root.postJson("/admin/api/knowledge/rollback",
                "{\"version\":\"" + before + "\",\"expectedActive\":\"" + after + "\"}");
        assertThat(back.statusCode()).isEqualTo(200);
        assertThat(activeVersion()).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT action || ' ' || target FROM admin_audit WHERE action IN ('published', 'rolled_back') ORDER BY id", String.class))
                .containsExactly("published " + after, "rolled_back " + before);
        assertThat(root.postJson("/admin/api/knowledge/entries/" + entry + "/retire", "{\"retired\":true}").body()).contains("\"retired\":true");
        assertThat(alice.postJson("/admin/api/knowledge/entries/" + entry + "/retire", "{\"retired\":false}").statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("refusals are 422 and recorded; unknowns are 404; a handled flag can name the revision that fixed it")
    void rulesAndTheFeedbackLink() throws Exception {
        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);

        assertThat(alice.postJson("/admin/api/knowledge/entries/bad_id", "{\"category\":\"x\"}").statusCode()).isEqualTo(422);
        assertThat(put(alice, "/admin/api/knowledge/entries/shipping-cost/drafts/en", "{\"question\":\"\",\"answer\":\"a\",\"note\":null}").statusCode())
                .isEqualTo(422);
        assertThat(alice.get("/admin/api/knowledge/entries/nope").statusCode()).isEqualTo(404);
        assertThat(alice.get("/admin/api/knowledge/versions/nope").statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit WHERE action = 'refused' AND target = 'knowledge'", Integer.class))
                .isEqualTo(2);

        HttpResponse<String> draft = put(alice, "/admin/api/knowledge/entries/shipping-cost/drafts/en",
                "{\"question\":\"How much is shipping?\",\"answer\":\"Free over 50 dollars; 5 dollars below.\",\"note\":\"threshold\"}");
        long revisionId = Long.parseLong(draft.body().replaceAll(".*\"id\":(\\d+).*", "$1"));
        String turn = UUID.randomUUID().toString();
        recorder.start(turn, UUID.randomUUID().toString(), TurnRecorder.Path.STREAM, "shipping?");
        recorder.finish(turn, TurnRecorder.Outcome.COMPLETED, "free", null, null, null, null, null);
        long flag = Long.parseLong(alice.postJson("/admin/api/feedback", "{\"turnId\":\"" + turn + "\",\"issue\":\"incomplete\",\"note\":null}")
                .body().replaceAll(".*?\"id\":(\\d+).*", "$1"));
        HttpResponse<String> handled = alice.postJson("/admin/api/feedback/" + flag + "/handle",
                "{\"state\":\"handled\",\"conclusion\":\"Draft names the threshold.\",\"revisionId\":" + revisionId + ",\"expectedVersion\":0}");
        assertThat(handled.statusCode()).isEqualTo(200);
        assertThat(handled.body()).contains("\"revisionId\":" + revisionId);
        assertThat(alice.postJson("/admin/api/feedback/" + flag + "/handle",
                "{\"state\":\"handled\",\"conclusion\":\"x\",\"revisionId\":999999,\"expectedVersion\":1}").statusCode()).isEqualTo(422);
        alice.client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/api/knowledge/entries/shipping-cost/drafts/en"))
                .header("X-XSRF-TOKEN", alice.csrf()).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }
}
