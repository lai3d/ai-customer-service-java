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
 * Staff login end to end, over real HTTP with a real cookie jar. The claims: a stranger is
 * turned away (a redirect for the page, a {@code 401} for the API); a signed-in support
 * member is not an admin; every mutation needs the CSRF token; signing out ends the session
 * in Postgres, not just in the browser; and the public chat side never sees any of it.
 *
 * <p>The context configuration is deliberately identical to {@code CustomerServiceApplicationTests}'
 * (the one without a mocked chat model) so the two share one cached context. An {@code all}-target context holds an ONNX session,
 * and the CI runner survives fifteen of them and not sixteen -- see CLAUDE.md. The first
 * admin is therefore created through {@link StaffAccounts} here rather than by the seed
 * properties, whose decision logic {@code StaffAccountsTest} covers.
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
        jdbc.update("DELETE FROM staff_account");
        jdbc.update("DELETE FROM spring_session");
        accounts.create("root", "first-admin-password", StaffRole.ADMIN, StaffSeeder.CREATED_BY);
    }

    @Test
    @DisplayName("an anonymous visitor is sent to the login page, and an anonymous API call gets 401 with no session")
    void anonymousIsTurnedAway() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);

        HttpResponse<String> api = browser.get("/admin/api/me");
        assertThat(api.statusCode()).isEqualTo(401);
        assertThat(browser.cookie("SESSION")).as("a refused API call opens no session").isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();

        // A page request is remembered so the login can return to it; that is the one session
        // an anonymous visitor can open, and it holds nothing but the URL.
        HttpResponse<String> page = browser.get("/admin");
        assertThat(page.statusCode()).isEqualTo(302);
        assertThat(page.headers().firstValue("Location")).hasValueSatisfying(location -> assertThat(location).endsWith("/admin/login"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    @DisplayName("the login page hands out the CSRF cookie, and a login without the token is refused")
    void loginNeedsTheCsrfToken() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);

        HttpResponse<String> login = browser.get("/admin/login");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("<form method=\"post\" action=\"/admin/login\"");
        assertThat(browser.cookie("XSRF-TOKEN")).isPresent();

        HttpResponse<String> without = browser.postForm("/admin/login",
                Map.of("username", "root", "password", "first-admin-password"));
        assertThat(without.statusCode()).isEqualTo(403);
        assertThat(browser.get("/admin/api/me").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a wrong password lands back on the page with ?error, and stays signed out")
    void wrongPassword() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        browser.get("/admin/login");

        HttpResponse<String> attempt = browser.login("root", "not-the-password");
        assertThat(attempt.statusCode()).isEqualTo(302);
        assertThat(attempt.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith("/admin/login?error"));
        assertThat(browser.get("/admin/api/me").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("the seed runner is wired, and with the table populated it changes nothing")
    void seedIsWiredAndIdle() {
        assertThat(seeder.seed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_account", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("the admin signs in, holds a session in Postgres, and signs out of it")
    void adminSignsInAndOut() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        browser.get("/admin/login");

        HttpResponse<String> login = browser.login("Root", "first-admin-password");
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith("/admin"));
        assertThat(browser.cookie("SESSION")).isPresent();

        HttpResponse<String> me = browser.get("/admin/api/me");
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).isEqualTo("{\"username\":\"root\",\"role\":\"admin\"}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = 'root'", Integer.class))
                .as("the session is a row, so a second replica would honour it")
                .isEqualTo(1);
        assertThat(browser.get("/admin").statusCode()).isEqualTo(200);

        HttpResponse<String> logout = browser.postForm("/admin/logout", Map.of("_csrf", browser.csrf()));
        assertThat(logout.statusCode()).isEqualTo(302);
        assertThat(logout.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith("/admin/login?logout"));
        assertThat(browser.get("/admin/api/me").statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = 'root'", Integer.class))
                .as("signing out deletes the row, so no replica honours the old cookie")
                .isZero();
    }

    @Test
    @DisplayName("an admin creates a support account; support can sign in but is not an admin")
    void rolesAreEnforcedOnTheServer() throws Exception {
        AdminBrowser admin = new AdminBrowser(port);
        admin.get("/admin/login");
        admin.login("root", "first-admin-password");

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
        assertThat(tooShort.body()).contains("12 characters");

        AdminBrowser support = new AdminBrowser(port);
        support.get("/admin/login");
        support.login("sam", "support-password-1");
        assertThat(support.get("/admin/api/me").body()).isEqualTo("{\"username\":\"sam\",\"role\":\"support\"}");
        assertThat(support.get("/admin/api/staff").statusCode()).as("listing accounts is an admin operation").isEqualTo(403);
        HttpResponse<String> escalation = support.postJson("/admin/api/staff",
                "{\"username\":\"eve\",\"password\":\"another-password-1\",\"role\":\"admin\"}", true);
        assertThat(escalation.statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_account", Integer.class)).isEqualTo(2);

        assertThat(admin.get("/admin/api/staff").body()).contains("\"username\":\"root\"", "\"username\":\"sam\"")
                .doesNotContain("password");
    }

    @Test
    @DisplayName("the public side is untouched: no login, no CSRF, no session on the demo page or the chat API")
    void publicEndpointsAreNotBehindTheLogin() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);

        assertThat(browser.get("/").statusCode()).isEqualTo(200);
        assertThat(browser.get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        // An empty body fails validation with 400. It would be 401 or 403 if Spring Security
        // had a chain on this path, which is the regression this guards against.
        HttpResponse<String> chat = browser.postJson("/api/v1/chat", "{}", false);
        assertThat(chat.statusCode()).isEqualTo(400);
        assertThat(browser.cookie("SESSION")).isEmpty();
        assertThat(browser.cookie("XSRF-TOKEN")).isEmpty();
    }
}
