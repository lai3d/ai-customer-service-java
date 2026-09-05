package dev.merlionos.customerservice.admin;

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

/**
 * A cookie jar over the JDK client, for driving the admin the way a browser does: a real
 * session cookie, the CSRF cookie copied into a header, redirects left alone so the test
 * sees them.
 */
class AdminBrowser {

    final int port;
    final CookieManager cookies = new CookieManager();
    final HttpClient client = HttpClient.newBuilder()
            .cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();

    AdminBrowser(int port) {
        this.port = port;
    }

    /** A browser that has signed in as the given account. */
    static AdminBrowser signedIn(int port, String username, String password) throws IOException, InterruptedException {
        AdminBrowser browser = new AdminBrowser(port);
        browser.get("/admin/login");
        HttpResponse<String> login = browser.login(username, password);
        if (login.statusCode() != 302 || !login.headers().firstValue("Location").orElse("").endsWith("/admin")) {
            throw new AssertionError("login as " + username + " failed: " + login.statusCode() + " "
                    + login.headers().firstValue("Location").orElse(""));
        }
        // Signing in rotates the CSRF token (the old cookie is cleared); a browser's next page
        // load fetches the new one, and so does this.
        browser.get("/admin/api/me");
        return browser;
    }

    HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Accept", "application/json, text/html")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
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

    HttpResponse<String> postJson(String path, String json) throws IOException, InterruptedException {
        return postJson(path, json, true);
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
