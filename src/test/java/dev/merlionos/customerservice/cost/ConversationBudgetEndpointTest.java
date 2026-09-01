package dev.merlionos.customerservice.cost;

import dev.merlionos.customerservice.PostgresTestcontainer;
import dev.merlionos.customerservice.chat.ChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * What a customer sees when a conversation runs out of budget: a 429 with an explanation and a
 * route to a human, not a silent extra charge and not a 500.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.cost.conversation-token-budget=100")
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class ConversationBudgetEndpointTest {

    // Budget state lives in a process-wide singleton by design, so each test needs its own
    // conversation or the first to run spends the second's budget.
    private String conversation;

    @Autowired TestRestTemplate rest;

    @MockitoBean AnthropicChatModel chatModel;

    @BeforeEach
    void stubModel(org.junit.jupiter.api.TestInfo testInfo) {
        // Ids stay inside the 36-character limit the chat memory schema imposes.
        conversation = java.util.UUID.randomUUID().toString();

        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("Sure."))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(400, 50)).build());

        given(chatModel.call(any(Prompt.class))).willReturn(response);
        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(response));
    }

    @Test
    @DisplayName("the turn that exhausts the budget is answered; the next one is refused")
    void refusesOnceTheBudgetIsSpent() {
        ResponseEntity<String> first = rest.postForEntity("/api/v1/chat",
                new ChatRequest(conversation, "Where is my order?"), String.class);
        assertThat(first.getStatusCode())
                .as("the customer gets the answer they were already owed")
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = rest.postForEntity("/api/v1/chat",
                new ChatRequest(conversation, "And the next one?"), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(second.getBody())
                .as("a ProblemDetail body that points at a human, not a bare status")
                .contains("human agent");
    }

    @Test
    @DisplayName("an untouched conversation is unaffected")
    void otherConversationsAreUnaffected() {
        rest.postForEntity("/api/v1/chat",
                new ChatRequest(conversation, "Where is my order?"), String.class);

        ResponseEntity<String> other = rest.postForEntity("/api/v1/chat",
                new ChatRequest(java.util.UUID.randomUUID().toString(), "Hello"), String.class);

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
