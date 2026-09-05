package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.PostgresTestcontainer;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staff sign-in as the separated UI does it, over real HTTP with a real cookie jar: a CSRF
 * cookie first, then a JSON login. The claims: a stranger gets {@code 401} and no session;
 * every mutation, the login included, needs the CSRF token; a signed-in support member is
 * not an admin; signing out ends the session in Postgres; the public side never sees any of
 * it; and nothing under {@code /admin} but the API exists here any more.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rag.import-mode=startup")
@AutoConfigureObservability
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AdminLoginTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffAccounts accounts;

    @Autowired
    StaffSeeder seeder;

    @BeforeEach
    void oneAdminAndNoSessions() {
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM admin_audit");
        jdbc.update("DELETE FROM staff_account");
        accounts.create("root", "first-admin-password", StaffRole.ADMIN, StaffSeeder.CREATED_BY);
    }

    @Test
    @DisplayName("an anonymous call is 401 with no session and no redirect, and the pages are gone")
    void anonymousIsTurnedAway() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);

        HttpResponse<String> api = browser.get("/admin/api/me");
        assertThat(api.statusCode()).isEqualTo(401);
        assertThat(api.headers().firstValue("Location")).isEmpty();
        assertThat(browser.cookie("SESSION")).as("a refused call opens no session").isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
        assertThat(browser.get("/admin").statusCode()).as("the UI is deployed on its own now").isEqualTo(404);
        assertThat(browser.get("/admin/login").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("the CSRF cookie comes from /csrf, and a login without the token is refused")
    void loginNeedsTheCsrfToken() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);

        HttpResponse<String> csrf = browser.get("/admin/api/csrf");
        assertThat(csrf.statusCode()).isEqualTo(204);
        assertThat(browser.cookie("XSRF-TOKEN")).isPresent();

        HttpResponse<String> without = browser.postJson("/admin/api/login",
                "{\"username\":\"root\",\"password\":\"first-admin-password\"}", false);
        assertThat(without.statusCode()).isEqualTo(403);
        assertThat(browser.get("/admin/api/me").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a wrong password is 401 with one sentence for every cause, and stays signed out")
    void wrongPassword() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        browser.get("/admin/api/csrf");

        HttpResponse<String> attempt = browser.login("root", "not-the-password");
        assertThat(attempt.statusCode()).isEqualTo(401);
        assertThat(attempt.body()).contains("Wrong username or password");
        HttpResponse<String> unknown = browser.login("nobody", "not-the-password");
        assertThat(unknown.body()).as("the same sentence, so accounts cannot be enumerated").isEqualTo(attempt.body());
        assertThat(browser.get("/admin/api/me").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("the seed runner is wired, and with the table populated it changes nothing")
    void seedIsWiredAndIdle() {
        assertThat(seeder.seed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_account", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("the admin signs in with JSON, gets a rotated session and token, holds a row in Postgres, and signs out of it")
    void adminSignsInAndOut() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        browser.get("/admin/api/csrf");
        String tokenBefore = browser.csrf();

        HttpResponse<String> login = browser.login("Root", "first-admin-password");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).isEqualTo("{\"username\":\"root\",\"role\":\"admin\"}");
        assertThat(browser.cookie("SESSION")).isPresent();
        assertThat(browser.csrf()).as("signing in rotates the CSRF token").isNotEqualTo(tokenBefore);

        HttpResponse<String> me = browser.get("/admin/api/me");
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).isEqualTo("{\"username\":\"root\",\"role\":\"admin\"}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = 'root'", Integer.class))
                .as("the session is a row, so a second replica would honour it")
                .isEqualTo(1);

        HttpResponse<String> logout = browser.postJson("/admin/api/logout", "{}");
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(browser.get("/admin/api/me").statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = 'root'", Integer.class))
                .as("signing out deletes the row, so no replica honours the old cookie")
                .isZero();
    }

    @Test
    @DisplayName("an admin creates a support account; support can sign in but is not an admin")
    void rolesAreEnforcedOnTheServer() throws Exception {
        AdminBrowser admin = AdminBrowser.signedIn(port, "root", "first-admin-password");

        HttpResponse<String> withoutToken = admin.postJson("/admin/api/staff",
                "{\"username\":\"sam\",\"password\":\"support-password-1\",\"role\":\"support\"}", false);
        assertThat(withoutToken.statusCode()).as("a mutation without the CSRF header is refused").isEqualTo(403);

        HttpResponse<String> created = admin.postJson("/admin/api/staff",
                "{\"username\":\"sam\",\"password\":\"support-password-1\",\"role\":\"support\"}", true);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"username\":\"sam\"", "\"role\":\"support\"", "\"createdBy\":\"root\"");

        HttpResponse<String> duplicate = admin.postJson("/admin/api/staff",
                "{\"username\":\"SAM\",\"password\":\"support-password-1\",\"role\":\"support\"}", true);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        HttpResponse<String> tooShort = admin.postJson("/admin/api/staff",
                "{\"username\":\"kim\",\"password\":\"short\",\"role\":\"support\"}", true);
        assertThat(tooShort.statusCode()).isEqualTo(400);

        AdminBrowser support = AdminBrowser.signedIn(port, "sam", "support-password-1");
        assertThat(support.get("/admin/api/me").body()).isEqualTo("{\"username\":\"sam\",\"role\":\"support\"}");
        assertThat(support.get("/admin/api/staff").statusCode()).as("listing accounts is an admin operation").isEqualTo(403);
        assertThat(jdbc.queryForMap("SELECT actor, action, target FROM admin_audit"))
                .containsEntry("actor", "sam").containsEntry("action", "refused").containsEntry("target", "GET /admin/api/staff");
        assertThat(admin.get("/admin/api/staff").body()).contains("\"username\":\"root\"", "\"username\":\"sam\"")
                .doesNotContain("password");
    }

    @Test
    @DisplayName("the public side is untouched: no login, no CSRF, no session on the demo page or the chat API")
    void publicEndpointsAreNotBehindTheLogin() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);

        assertThat(browser.get("/").statusCode()).isEqualTo(200);
        assertThat(browser.get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        HttpResponse<String> chat = browser.postJson("/api/v1/chat", "{}", false);
        assertThat(chat.statusCode()).isEqualTo(400);
        assertThat(browser.cookie("SESSION")).isEmpty();
        assertThat(browser.cookie("XSRF-TOKEN")).isEmpty();
        assertThat(Map.of()).isEmpty();
    }
}
