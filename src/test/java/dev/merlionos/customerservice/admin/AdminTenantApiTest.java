package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.tenancy.Tenant;
import dev.merlionos.customerservice.tenancy.TenantApiKeys;
import dev.merlionos.customerservice.tenancy.TestTenant;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenants over the admin API, and the key it hands out working on the public API: the whole
 * onboarding path, a customer's key issued by an admin and revoked by one. Same context
 * configuration as {@code AdminTicketApiTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rag.import-mode=startup")
@AutoConfigureObservability
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AdminTenantApiTest {

    static final String PASSWORD = "a-long-enough-password";
    static final Pattern KEY = Pattern.compile("\"key\":\"(cs_[A-Za-z0-9]+)\"");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffAccounts accounts;

    @Autowired
    TenantApiKeys keys;

    @BeforeEach
    void freshStaff() {
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM admin_audit");
        jdbc.update("DELETE FROM staff_account");
        accounts.create("root", PASSWORD, StaffRole.ADMIN, "seed");
        accounts.create("alice", PASSWORD, StaffRole.SUPPORT, "root");
    }

    private int audits(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit WHERE action = ?", Integer.class, action);
    }

    @Test
    @DisplayName("an admin creates a tenant, issues a key that works on the public API, and revokes it")
    void onboarding() throws Exception {
        AdminBrowser root = AdminBrowser.signedIn(port, "root", PASSWORD);
        String id = "acme-" + UUID.randomUUID().toString().substring(0, 6);

        HttpResponse<String> created = root.postJson("/admin/api/tenants", "{\"id\":\"" + id + "\",\"name\":\"Acme Ltd\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"id\":\"" + id + "\"", "\"name\":\"Acme Ltd\"", "\"enabled\":true");
        assertThat(root.postJson("/admin/api/tenants", "{\"id\":\"" + id + "\",\"name\":\"Again\"}").statusCode())
                .as("the id is the identity").isEqualTo(409);
        assertThat(root.get("/admin/api/tenants").body()).contains("\"id\":\"" + Tenant.DEFAULT + "\"", "\"id\":\"" + id + "\"");

        HttpResponse<String> issued = root.postJson("/admin/api/tenants/" + id + "/keys", "{\"label\":\"widget\"}");
        assertThat(issued.statusCode()).isEqualTo(201);
        Matcher key = KEY.matcher(issued.body());
        assertThat(key.find()).as("the key, once: %s", issued.body()).isTrue();
        String apiKey = key.group(1);
        String keyId = TenantApiKeys.keyId(apiKey);

        HttpResponse<String> detail = root.get("/admin/api/tenants/" + id);
        assertThat(detail.body()).contains("\"keyId\":\"" + keyId + "\"", "\"label\":\"widget\"", "\"revokedAt\":null")
                .as("the key itself is not readable back").doesNotContain(apiKey);

        assertThat(chat(apiKey)).as("the issued key opens the public API").isNotEqualTo(401);

        assertThat(root.postJson("/admin/api/tenants/" + id + "/keys/" + keyId + "/revoke", "{}").statusCode()).isEqualTo(204);
        assertThat(chat(apiKey)).as("and revoking it closes it").isEqualTo(401);
        assertThat(root.postJson("/admin/api/tenants/" + Tenant.DEFAULT + "/keys/" + keyId + "/revoke", "{}").statusCode())
                .as("a key is addressed under its own tenant").isEqualTo(404);

        assertThat(audits("tenant_created")).isEqualTo(1);
        assertThat(audits("key_issued")).isEqualTo(1);
        assertThat(audits("key_revoked")).isEqualTo(1);
    }

    @Test
    @DisplayName("disabling a tenant closes every key at once; the default tenant cannot be disabled")
    void disabling() throws Exception {
        AdminBrowser root = AdminBrowser.signedIn(port, "root", PASSWORD);
        String id = "beta-" + UUID.randomUUID().toString().substring(0, 6);
        root.postJson("/admin/api/tenants", "{\"id\":\"" + id + "\",\"name\":\"Beta\"}");
        Matcher key = KEY.matcher(root.postJson("/admin/api/tenants/" + id + "/keys", "{}").body());
        assertThat(key.find()).isTrue();

        assertThat(root.postJson("/admin/api/tenants/" + id + "/enabled", "{\"enabled\":false}").statusCode()).isEqualTo(200);
        assertThat(chat(key.group(1))).isEqualTo(401);
        assertThat(root.postJson("/admin/api/tenants/" + id + "/enabled", "{\"enabled\":true}").statusCode()).isEqualTo(200);
        assertThat(chat(key.group(1))).isNotEqualTo(401);

        HttpResponse<String> refused = root.postJson("/admin/api/tenants/" + Tenant.DEFAULT + "/enabled", "{\"enabled\":false}");
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(chat(TestTenant.API_KEY)).isNotEqualTo(401);
        assertThat(audits("refused")).isEqualTo(1);
    }

    @Test
    @DisplayName("support staff cannot see tenants or keys; an invalid id is refused")
    void permissionsAndValidation() throws Exception {
        AdminBrowser alice = AdminBrowser.signedIn(port, "alice", PASSWORD);
        assertThat(alice.get("/admin/api/tenants").statusCode()).isEqualTo(403);
        assertThat(alice.postJson("/admin/api/tenants/" + Tenant.DEFAULT + "/keys", "{}").statusCode()).isEqualTo(403);
        assertThat(new AdminBrowser(port).get("/admin/api/tenants").statusCode()).isEqualTo(401);

        AdminBrowser root = AdminBrowser.signedIn(port, "root", PASSWORD);
        assertThat(root.postJson("/admin/api/tenants", "{\"id\":\"Bad Id\",\"name\":\"x\"}").statusCode()).isEqualTo(422);
        assertThat(root.postJson("/admin/api/tenants", "{\"id\":\"fine-id\",\"name\":\" \"}").statusCode()).isEqualTo(422);
        assertThat(root.get("/admin/api/tenants/no-such-tenant").statusCode()).isEqualTo(404);
    }

    /** A turn on the public API with the key; the model is not stubbed here, so anything but 401 means the key was accepted. */
    private int chat(String apiKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/chat"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"\"}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
