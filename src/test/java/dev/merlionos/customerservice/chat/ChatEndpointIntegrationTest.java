package dev.merlionos.customerservice.chat;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.tenancy.Tenant;
import dev.merlionos.customerservice.tenancy.TenantApiKeys;
import dev.merlionos.customerservice.tenancy.Tenants;
import dev.merlionos.customerservice.tenancy.TestTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Drives the HTTP layer end to end -- real Spring MVC, real SSE encoding, real validation,
 * the real tenant key filter and conversation mapping -- with the model call itself stubbed
 * out so the suite needs no API key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class ChatEndpointIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    Tenants tenants;

    @Autowired
    TenantApiKeys apiKeys;

    @MockitoBean
    ChatService chatService;

    @Test
    @DisplayName("a turn on a conversation that already has one in flight is a 409, not a 500")
    void overlappingTurnIsAConflict() {
        given(chatService.ask(eq(Tenant.DEFAULT), any(), any(), any()))
                .willThrow(new ConversationBusyException("busy-conversation"));

        ResponseEntity<String> response = post("/api/v1/chat",
                new ChatRequest("busy-conversation", "Again?"), TestTenant.API_KEY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .as("a ProblemDetail body that says what to do, not a bare status")
                .contains("Conversation busy").contains("still answering");
    }

    @Test
    @DisplayName("a new conversation gets an id assigned and echoed back")
    void assignsConversationIdWhenAbsent() {
        given(chatService.ask(any(), any(), eq("Where is my order?"), any())).willReturn("It shipped on Monday.");

        ResponseEntity<ChatReply> response = post("/api/v1/chat",
                new ChatRequest(null, "Where is my order?"), TestTenant.API_KEY, ChatReply.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).isEqualTo("It shipped on Monday.");
        assertThat(response.getBody().conversationId()).isNotBlank();
        assertThat(response.getHeaders().getFirst(ChatController.CONVERSATION_ID_HEADER))
                .isEqualTo(response.getBody().conversationId());
    }

    @Test
    @DisplayName("a supplied conversation id is echoed back and maps to one internal id, turn after turn")
    void mapsSuppliedConversationIdToOneInternalId() {
        given(chatService.ask(any(), any(), any(), any())).willReturn("Sure.");
        String external = "existing-" + UUID.randomUUID().toString().substring(0, 8);

        ResponseEntity<ChatReply> first = post("/api/v1/chat",
                new ChatRequest(external, "And my second order?"), TestTenant.API_KEY, ChatReply.class);
        ResponseEntity<ChatReply> second = post("/api/v1/chat",
                new ChatRequest(external, "And the third?"), TestTenant.API_KEY, ChatReply.class);

        assertThat(first.getBody().conversationId()).isEqualTo(external);
        assertThat(second.getBody().conversationId()).isEqualTo(external);
        ArgumentCaptor<String> internal = ArgumentCaptor.forClass(String.class);
        verify(chatService, times(2)).ask(eq(Tenant.DEFAULT), internal.capture(), any(), any());
        assertThat(internal.getAllValues()).hasSize(2);
        assertThat(internal.getAllValues().get(0))
                .as("the id every table keys on is ours, not the client's")
                .isNotEqualTo(external)
                .isEqualTo(internal.getAllValues().get(1));
    }

    @Test
    @DisplayName("the customer's panel token travels as a header to the service, and its absence is null")
    void customerTokenHeader() {
        given(chatService.ask(any(), any(), any(), any())).willReturn("Sure.");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTenant.API_KEY);
        headers.set(ChatController.CUSTOMER_TOKEN_HEADER, "  panel-token-xyz ");
        rest.exchange("/api/v1/chat", HttpMethod.POST, new HttpEntity<>(new ChatRequest(null, "hi"), headers), ChatReply.class);
        verify(chatService).ask(eq(Tenant.DEFAULT), any(), eq("hi"), eq(dev.merlionos.customerservice.orders.CustomerRef.token("panel-token-xyz")));

        post("/api/v1/chat", new ChatRequest(null, "hello"), TestTenant.API_KEY, ChatReply.class);
        verify(chatService).ask(eq(Tenant.DEFAULT), any(), eq("hello"), eq(dev.merlionos.customerservice.orders.CustomerRef.none()));
    }

    @Test
    @DisplayName("the same client conversation id under two tenants is two conversations")
    void scopesConversationIdToTheTenant() {
        given(chatService.ask(any(), any(), any(), any())).willReturn("Sure.");
        String other = "acme-" + UUID.randomUUID().toString().substring(0, 8);
        tenants.create(other, "Acme");
        String otherKey = apiKeys.issue(other, "test");
        String external = "shared-" + UUID.randomUUID().toString().substring(0, 8);

        post("/api/v1/chat", new ChatRequest(external, "hello"), TestTenant.API_KEY, ChatReply.class);
        post("/api/v1/chat", new ChatRequest(external, "hello"), otherKey, ChatReply.class);

        ArgumentCaptor<String> tenant = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> internal = ArgumentCaptor.forClass(String.class);
        verify(chatService, times(2)).ask(tenant.capture(), internal.capture(), any(), any());
        assertThat(tenant.getAllValues()).containsExactly(Tenant.DEFAULT, other);
        assertThat(internal.getAllValues().get(0))
                .as("a guessed id from another tenant's client reaches a different conversation")
                .isNotEqualTo(internal.getAllValues().get(1));
    }

    @Test
    @DisplayName("without a tenant API key the public API is a 401 before any model call")
    void requiresAnApiKey() {
        ResponseEntity<String> missing = post("/api/v1/chat",
                new ChatRequest(null, "Where is my order?"), null, String.class);
        ResponseEntity<String> wrong = post("/api/v1/chat",
                new ChatRequest(null, "Where is my order?"), "cs_nosuchke-not-a-key", String.class);

        for (ResponseEntity<String> response : List.of(missing, wrong)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
            assertThat(response.getHeaders().getContentType())
                    .isNotNull()
                    .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
            assertThat(response.getBody()).contains("Authorization: Bearer");
        }
        verify(chatService, never()).ask(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a revoked key and a disabled tenant are both 401")
    void refusesRevokedKeysAndDisabledTenants() {
        given(chatService.ask(any(), any(), any(), any())).willReturn("Sure.");
        String tenant = "gone-" + UUID.randomUUID().toString().substring(0, 8);
        tenants.create(tenant, "Gone");
        String revoked = apiKeys.issue(tenant, "revoked");
        String kept = apiKeys.issue(tenant, "kept");
        apiKeys.revoke(TenantApiKeys.keyId(revoked));

        assertThat(post("/api/v1/chat", new ChatRequest(null, "hi"), revoked, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/api/v1/chat", new ChatRequest(null, "hi"), kept, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        tenants.setEnabled(tenant, false);
        assertThat(post("/api/v1/chat", new ChatRequest(null, "hi"), kept, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the streaming endpoint emits SSE events")
    void streamsAsServerSentEvents() {
        given(chatService.stream(any(), any(), any(), any())).willReturn(
                Flux.just("It ", "shipped ", "on Monday.").map(TurnEvent.Token::new));

        ResponseEntity<String> response = stream(new ChatRequest(null, "Where is my order?"), TestTenant.API_KEY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)).isTrue());
        assertThat(response.getHeaders().getFirst(ChatController.CONVERSATION_ID_HEADER)).isNotBlank();
        assertThat(response.getBody())
                .contains("event:" + ChatController.TOKEN_EVENT)
                .contains("\"text\":\"It \"")
                .contains("\"text\":\"on Monday.\"")
                .doesNotContain("event:" + ChatController.ERROR_EVENT);
    }

    @Test
    @DisplayName("the streaming endpoint needs the key too")
    void streamRequiresAnApiKey() {
        assertThat(stream(new ChatRequest(null, "Where is my order?"), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chatService, never()).stream(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a mid-stream failure arrives as a named error event, not as an answer")
    void reportsMidStreamFailureAsErrorEvent() {
        given(chatService.stream(any(), any(), any(), any())).willReturn(
                Flux.<TurnEvent>just(new TurnEvent.Token("It "))
                        .concatWith(Flux.error(new IllegalStateException("upstream died"))));

        ResponseEntity<String> response = stream(new ChatRequest(null, "Where is my order?"), TestTenant.API_KEY);

        // The status was already committed with the first byte, so the failure has to
        // travel in-band.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("event:" + ChatController.TOKEN_EVENT)
                .contains("\"text\":\"It \"")
                .contains("event:" + ChatController.ERROR_EVENT);
    }

    @Test
    @DisplayName("an over-long conversation id is a 400, not a 500 from the database")
    void rejectsOverLongConversationId() {
        // The conversation table declares external_id as varchar(36), as Spring AI's chat
        // memory schema did for the id before it. Without a bound here the insert fails and
        // the customer sees an internal error.
        ResponseEntity<String> response = post("/api/v1/chat",
                new ChatRequest("x".repeat(37), "Where is my order?"), TestTenant.API_KEY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a blank message is rejected before reaching the model")
    void rejectsBlankMessage() {
        ResponseEntity<String> response = post("/api/v1/chat",
                new ChatRequest(null, "   "), TestTenant.API_KEY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private <T> ResponseEntity<T> post(String path, ChatRequest body, String apiKey, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.setBearerAuth(apiKey);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private ResponseEntity<String> stream(ChatRequest body, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        if (apiKey != null) {
            headers.setBearerAuth(apiKey);
        }
        return rest.exchange("/api/v1/chat/stream", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
