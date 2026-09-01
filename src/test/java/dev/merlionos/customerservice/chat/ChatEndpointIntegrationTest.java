package dev.merlionos.customerservice.chat;

import dev.merlionos.customerservice.PostgresTestcontainer;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Drives the HTTP layer end to end -- real Spring MVC, real SSE encoding, real validation --
 * with the model call itself stubbed out so the suite needs no API key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class ChatEndpointIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @MockitoBean
    ChatService chatService;

    @Test
    @DisplayName("a new conversation gets an id assigned and echoed back")
    void assignsConversationIdWhenAbsent() {
        given(chatService.ask(any(), eq("Where is my order?"))).willReturn("It shipped on Monday.");

        ResponseEntity<ChatReply> response = rest.postForEntity(
                "/api/v1/chat", new ChatRequest(null, "Where is my order?"), ChatReply.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).isEqualTo("It shipped on Monday.");
        assertThat(response.getBody().conversationId()).isNotBlank();
        assertThat(response.getHeaders().getFirst(ChatController.CONVERSATION_ID_HEADER))
                .isEqualTo(response.getBody().conversationId());
    }

    @Test
    @DisplayName("a supplied conversation id is carried through to the service unchanged")
    void honoursSuppliedConversationId() {
        given(chatService.ask(eq("existing-conversation"), any())).willReturn("Sure.");

        rest.postForEntity("/api/v1/chat",
                new ChatRequest("existing-conversation", "And my second order?"), ChatReply.class);

        ArgumentCaptor<String> conversationId = ArgumentCaptor.forClass(String.class);
        verify(chatService).ask(conversationId.capture(), eq("And my second order?"));
        assertThat(conversationId.getValue()).isEqualTo("existing-conversation");
    }

    @Test
    @DisplayName("the streaming endpoint emits SSE events")
    void streamsAsServerSentEvents() {
        given(chatService.stream(any(), any())).willReturn(
                Flux.just("It ", "shipped ", "on Monday.").map(TurnEvent.Token::new));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));

        ResponseEntity<String> response = rest.exchange("/api/v1/chat/stream", HttpMethod.POST,
                new HttpEntity<>(new ChatRequest(null, "Where is my order?"), headers), String.class);

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
    @DisplayName("a mid-stream failure arrives as a named error event, not as an answer")
    void reportsMidStreamFailureAsErrorEvent() {
        given(chatService.stream(any(), any())).willReturn(
                Flux.<TurnEvent>just(new TurnEvent.Token("It "))
                        .concatWith(Flux.error(new IllegalStateException("upstream died"))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));

        ResponseEntity<String> response = rest.exchange("/api/v1/chat/stream", HttpMethod.POST,
                new HttpEntity<>(new ChatRequest(null, "Where is my order?"), headers), String.class);

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
        // Spring AI's chat memory schema declares conversation_id as varchar(36). Without a
        // bound here the insert fails and the customer sees an internal error.
        ResponseEntity<String> response = rest.postForEntity("/api/v1/chat",
                new ChatRequest("x".repeat(37), "Where is my order?"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a blank message is rejected before reaching the model")
    void rejectsBlankMessage() {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/chat", new ChatRequest(null, "   "), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
