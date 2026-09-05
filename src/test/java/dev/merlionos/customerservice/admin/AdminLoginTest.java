package dev.merlionos.customerservice.admin;

import dev.merlionos.customerservice.PostgresTestcontainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staff login end to end, over real HTTP with a real cookie jar, against the seeded first
 * admin. The claims: a stranger is turned away (a redirect for the page, a {@code 401} for
 * the API); a signed-in support member is not an admin; every mutation needs the CSRF
 * token; signing out ends the session in Postgres, not just in the browser; and the public
 * chat side never sees any of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.admin.seed.username=Root",
        "app.admin.seed.password=first-admin-password"})
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class AdminLoginTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void onlyTheSeededAdminAndNoSessions() {
        jdbc.update("DELETE FROM staff_account WHERE username <> 'root'");
        jdbc.update("DELETE FROM spring_session");
    }

    @Test
    @DisplayName("an anonymous visitor is sent to the login page, and an anonymous API call gets 401 with no session")
    void anonymousIsTurnedAway() throws Exception {
        Browser browser = new Browser();

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
        Browser browser = new Browser();

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
        Browser browser = new Browser();
        browser.get("/admin/login");

        HttpResponse<String> attempt = browser.login("root", "not-the-password");
        assertThat(attempt.statusCode()).isEqualTo(302);
        assertThat(attempt.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith("/admin/login?error"));
        assertThat(browser.get("/admin/api/me").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("the seeded admin signs in, holds a session in Postgres, and signs out of it")
    void adminSignsInAndOut() throws Exception {
        Browser browser = new Browser();
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
        Browser admin = new Browser();
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

        Browser support = new Browser();
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
        Browser browser = new Browser();

        assertThat(browser.get("/").statusCode()).isEqualTo(200);
        assertThat(browser.get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        // An empty body fails validation with 400. It would be 401 or 403 if Spring Security
        // had a chain on this path, which is the regression this guards against.
        HttpResponse<String> chat = browser.postJson("/api/v1/chat", "{}", false);
        assertThat(chat.statusCode()).isEqualTo(400);
        assertThat(browser.cookie("SESSION")).isEmpty();
        assertThat(browser.cookie("XSRF-TOKEN")).isEmpty();
    }

    /** A cookie jar over the JDK client; redirects are left alone so the test sees them. */
    class Browser {
        final CookieManager cookies = new CookieManager();
        final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();

        HttpResponse<String> get(String path) throws IOException, InterruptedException {
            return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> login(String username, String password) throws IOException, InterruptedException {
            return postForm("/admin/login", Map.of("username", username, "password", password, "_csrf", csrf()));
        }

        HttpResponse<String> postForm(String path, Map<String, String> fields) throws IOException, InterruptedException {
            String body = fields.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .reduce((a, b) -> a + "&" + b).orElse("");
            return client.send(HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> postJson(String path, String json, boolean withCsrfHeader) throws IOException, InterruptedException {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (withCsrfHeader) {
                request.header("X-XSRF-TOKEN", csrf());
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        String csrf() {
            return cookie("XSRF-TOKEN").orElseThrow(() -> new AssertionError("no XSRF-TOKEN cookie yet"));
        }

        Optional<String> cookie(String name) {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals(name))
                    .map(HttpCookie::getValue).findFirst();
        }

        private URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }
}
