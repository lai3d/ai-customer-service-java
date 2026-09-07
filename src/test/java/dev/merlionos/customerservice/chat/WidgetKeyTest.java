package dev.merlionos.customerservice.chat;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.tenancy.TenantApiKeys;
import dev.merlionos.customerservice.tenancy.Tenants;
import dev.merlionos.customerservice.tenancy.TestTenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A widget key from a browser: the origin check and the CORS answer, the two things that
 * bound a key anyone can read off a web page. Same context configuration as
 * {@code ChatEndpointIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class WidgetKeyTest {

    static final String SHOP = "https://shop.example.com";

    @Autowired TestRestTemplate rest;
    @Autowired Tenants tenants;
    @Autowired TenantApiKeys keys;
    @MockitoBean ChatService chatService;

    String tenant;
    String widgetKey;

    @BeforeEach
    void aTenantWithAWidgetKey() {
        given(chatService.ask(any(), any(), any())).willReturn("Sure.");
        tenant = "shop-" + UUID.randomUUID().toString().substring(0, 6);
        tenants.create(tenant, "Shop");
        widgetKey = keys.issueWidget(tenant, "site", List.of("https://Shop.example.com:443", "http://localhost:5173"));
    }

    @Test
    @DisplayName("from an allowed origin the widget key works and the response carries the CORS answer for that origin")
    void allowedOrigin() {
        ResponseEntity<ChatReply> response = post(widgetKey, SHOP);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(SHOP);
        assertThat(response.getHeaders().getAccessControlExposeHeaders()).contains("X-Conversation-Id");
        assertThat(response.getHeaders().getVary()).contains("Origin");
        assertThat(response.getBody().content()).isEqualTo("Sure.");
        verify(chatService).ask(org.mockito.ArgumentMatchers.eq(tenant), any(), any());

        assertThat(post(widgetKey, "http://localhost:5173").getStatusCode()).as("a second listed origin, port and all").isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("from another origin, or with no origin at all, a widget key is a 403 and nothing is answered")
    void otherOrigin() {
        ResponseEntity<String> elsewhere = rest.exchange("/api/v1/chat", HttpMethod.POST,
                new HttpEntity<>(new ChatRequest(null, "hi"), headers(widgetKey, "https://evil.example.net")), String.class);
        ResponseEntity<String> nowhere = rest.exchange("/api/v1/chat", HttpMethod.POST,
                new HttpEntity<>(new ChatRequest(null, "hi"), headers(widgetKey, null)), String.class);
        ResponseEntity<String> subdomain = rest.exchange("/api/v1/chat", HttpMethod.POST,
                new HttpEntity<>(new ChatRequest(null, "hi"), headers(widgetKey, "https://www.shop.example.com")), String.class);

        for (ResponseEntity<String> response : List.of(elsewhere, nowhere, subdomain)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
            assertThat(response.getBody()).contains("widget key");
        }
        verify(chatService, never()).ask(any(), any(), any());
    }

    @Test
    @DisplayName("a secret key from a browser is answered but gets no CORS headers, which is the browser refusing it for the tenant")
    void secretKeyFromABrowser() {
        ResponseEntity<ChatReply> response = post(TestTenant.API_KEY, SHOP);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    @DisplayName("the preflight is answered for any origin without a key; it grants nothing by itself")
    void preflight() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://anyone.example.org");
        headers.setAccessControlRequestMethod(HttpMethod.POST);
        headers.setAccessControlRequestHeaders(List.of("authorization", "content-type"));
        ResponseEntity<Void> response = rest.exchange("/api/v1/chat/stream", HttpMethod.OPTIONS, new HttpEntity<>(headers), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("https://anyone.example.org");
        assertThat(response.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.POST);
        assertThat(response.getHeaders().getAccessControlAllowHeaders()).contains("Authorization");
        assertThat(response.getHeaders().getAccessControlMaxAge()).isEqualTo(3600);
        verify(chatService, never()).ask(any(), any(), any());
    }

    @Test
    @DisplayName("the widget script and its demo page are served from the app, and the script wants a key")
    void widgetIsServed() {
        ResponseEntity<String> script = rest.getForEntity("/widget.js", String.class);
        assertThat(script.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(script.getHeaders().getContentType()).isNotNull()
                .satisfies(type -> assertThat(type.toString()).contains("javascript"));
        assertThat(script.getBody()).contains("data-key").contains("/api/v1/chat/stream").contains("attachShadow");
        assertThat(rest.getForEntity("/widget-demo.html", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<ChatReply> post(String apiKey, String origin) {
        return rest.exchange("/api/v1/chat", HttpMethod.POST, new HttpEntity<>(new ChatRequest(null, "hi"), headers(apiKey, origin)), ChatReply.class);
    }

    private static HttpHeaders headers(String apiKey, String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        if (origin != null) {
            headers.setOrigin(origin);
        }
        return headers;
    }
}
