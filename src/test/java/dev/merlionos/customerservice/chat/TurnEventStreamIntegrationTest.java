package dev.merlionos.customerservice.chat;

import dev.merlionos.customerservice.PostgresTestcontainer;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * The inspection channel, end to end: real retrieval against real pgvector and the real
 * embedding model, with only the chat model stubbed.
 *
 * <p>Retrieved passages and token usage are the two things a token-only stream cannot express,
 * and both come from places that are easy to break silently -- the passages live in the advisor
 * context rather than the model's output, and usage only appears on the final chunk. A
 * regression here would not fail any other test; the stream would simply go quiet and the demo
 * UI would show empty panels.
 */
@SpringBootTest(properties = "app.rag.import-mode=startup")
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class TurnEventStreamIntegrationTest {

    @Autowired ChatService chatService;

    @Autowired JdbcTemplate jdbc;

    @MockitoBean AnthropicChatModel chatModel;

    @BeforeEach
    void stubModel() {
        ChatResponse first = new ChatResponse(List.of(new Generation(new AssistantMessage("运费"))));
        ChatResponse last = new ChatResponse(
                List.of(new Generation(new AssistantMessage("满 50 美元免运费。"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1204, 87)).build());

        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(first, last));
    }

    @Test
    @DisplayName("a turn reports what it retrieved, what it answered, and what it cost")
    void streamsRetrievalTokensAndUsage() {
        List<TurnEvent> events = chatService.stream("conversation-events", "运费多少钱")
                .collectList().block();

        assertThat(events).isNotNull();

        TurnEvent.Retrieval retrieval = events.stream()
                .filter(TurnEvent.Retrieval.class::isInstance)
                .map(TurnEvent.Retrieval.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no retrieval event: " + events));

        assertThat(retrieval.passages())
                .as("the passages the answer was grounded in, with their scores")
                .isNotEmpty()
                .allSatisfy(passage -> assertThat(passage.score()).isPositive());
        assertThat(retrieval.passages())
                .extracting(TurnEvent.Passage::entryId)
                .contains("shipping-cost");

        assertThat(events)
                .filteredOn(TurnEvent.Token.class::isInstance)
                .extracting(event -> ((TurnEvent.Token) event).text())
                .containsExactly("运费", "满 50 美元免运费。");

        TurnEvent.Usage usage = events.stream()
                .filter(TurnEvent.Usage.class::isInstance)
                .map(TurnEvent.Usage.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no usage event: " + events));

        assertThat(usage.inputTokens()).isEqualTo(1204);
        assertThat(usage.outputTokens()).isEqualTo(87);
        assertThat(usage.millis()).isNotNull();
    }

    private Map<String, Object> turnRow(String conversationId) {
        return jdbc.queryForMap("SELECT * FROM conversation_turn WHERE conversation_id = ? ORDER BY started_at DESC LIMIT 1",
                conversationId);
    }

    @Test
    @DisplayName("a streamed turn leaves a completed record: question, answer, model usage, and what retrieval found")
    void streamedTurnIsRecorded() {
        chatService.stream("conversation-recorded", "运费多少钱").collectList().block();

        Map<String, Object> turn = turnRow("conversation-recorded");
        assertThat(turn).containsEntry("path", "stream").containsEntry("outcome", "completed")
                .containsEntry("question", "运费多少钱").containsEntry("answer", "运费满 50 美元免运费。")
                .containsEntry("input_tokens", 1204).containsEntry("output_tokens", 87);
        assertThat(turn.get("ended_at")).isNotNull();
        assertThat(jdbc.queryForList("SELECT entry_id FROM turn_retrieval WHERE turn_id = ? ORDER BY rank",
                String.class, turn.get("turn_id")))
                .as("the passages, in rank order, written before the model answered")
                .isNotEmpty().contains("shipping-cost");
    }

    @Test
    @DisplayName("the blocking path leaves the same record, with the same retrieval evidence")
    void blockingTurnIsRecorded() {
        given(chatModel.call(any(Prompt.class))).willReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("满 50 美元免运费。"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(900, 40)).model("claude-opus-5").build()));

        String answer = chatService.ask("conversation-blocking", "运费多少钱");

        assertThat(answer).isEqualTo("满 50 美元免运费。");
        Map<String, Object> turn = turnRow("conversation-blocking");
        assertThat(turn).containsEntry("path", "blocking").containsEntry("outcome", "completed")
                .containsEntry("answer", "满 50 美元免运费。").containsEntry("model", "claude-opus-5")
                .containsEntry("input_tokens", 900).containsEntry("output_tokens", 40);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_retrieval WHERE turn_id = ?", Integer.class,
                turn.get("turn_id"))).isPositive();
    }

    @Test
    @DisplayName("a failed model call is a failed record with the reason, and its retrieval is still there")
    void failedTurnIsRecorded() {
        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.error(new IllegalStateException("provider down")));

        assertThatThrownBy(() -> chatService.stream("conversation-failed", "运费多少钱").collectList().block())
                .isInstanceOf(RuntimeException.class);

        Map<String, Object> turn = turnRow("conversation-failed");
        assertThat(turn).containsEntry("outcome", "failed").containsEntry("answer", null);
        assertThat((String) turn.get("failure"))
                .as("Spring AI wraps the stream failure; the record keeps the provider's reason too")
                .contains("Stream processing failed").contains("provider down");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_retrieval WHERE turn_id = ?", Integer.class,
                turn.get("turn_id"))).as("retrieval happened before the call failed").isPositive();
    }

    @Test
    @DisplayName("a client that stops listening leaves an interrupted record, not a completed one")
    void cancelledTurnIsRecorded() {
        chatService.stream("conversation-cancelled", "运费多少钱").take(1).blockLast();

        assertThat(turnRow("conversation-cancelled")).containsEntry("outcome", "interrupted");
    }

    @Test
    @DisplayName("retrieval is reported once, not on every chunk")
    void reportsRetrievalOnce() {
        List<TurnEvent> events = chatService.stream("conversation-once", "运费多少钱")
                .collectList().block();

        assertThat(events).filteredOn(TurnEvent.Retrieval.class::isInstance).hasSize(1);
    }
}
