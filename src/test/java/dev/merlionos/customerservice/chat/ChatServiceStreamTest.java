package dev.merlionos.customerservice.chat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the interruption handling without a live model, by feeding a synthetic token
 * flux through the same decorator the streaming endpoint uses.
 */
class ChatServiceStreamTest {

    private static final String CONVERSATION_ID = "conversation-1";

    private RecordingChatMemory chatMemory;
    private MeterRegistry meterRegistry;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatMemory = new RecordingChatMemory();
        meterRegistry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.tracing.Tracer> noTracer = Mockito.mock(ObjectProvider.class);
        chatService = new ChatService(Mockito.mock(ChatClient.class), chatMemory,
                new TurnEventBus(), Mockito.mock(dev.merlionos.customerservice.cost.ConversationBudget.class),
                Mockito.mock(ConversationLease.class), Mockito.mock(TurnRecorder.class), noTracer, meterRegistry);
    }

    @Test
    @DisplayName("a stream that completes leaves persistence to the memory advisor")
    void completedStreamDoesNotDoubleWrite() {
        Flux<TurnEvent> tokens = tokens("Your ", "order ", "shipped.");

        StepVerifier.create(chatService.recordAssistantReplyOnInterruption(CONVERSATION_ID, tokens))
                .expectNext(new TurnEvent.Token("Your "), new TurnEvent.Token("order "),
                        new TurnEvent.Token("shipped."))
                .verifyComplete();

        assertThat(chatMemory.added)
                .as("the advisor already stores the reply on normal completion")
                .isEmpty();
        assertThat(terminations("completed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a cancelled stream persists the partial reply, so history stays well-formed")
    void cancelledStreamPersistsPartialReply() {
        Flux<TurnEvent> tokens = tokens("Your ", "order ", "shipped.");

        StepVerifier.create(chatService.recordAssistantReplyOnInterruption(CONVERSATION_ID, tokens), 2)
                .expectNext(new TurnEvent.Token("Your "), new TurnEvent.Token("order "))
                .thenCancel()
                .verify();

        assertThat(chatMemory.added).singleElement()
                .isInstanceOf(AssistantMessage.class)
                .extracting(Message::getText)
                .isEqualTo("Your order ");
        assertThat(chatMemory.conversationIds).containsExactly(CONVERSATION_ID);
        assertThat(terminations("cancelled")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a failed stream persists what was generated before the error")
    void failedStreamPersistsPartialReply() {
        Flux<TurnEvent> tokens = tokens("Your ", "order ")
                .concatWith(Flux.error(new IllegalStateException("upstream died")));

        StepVerifier.create(chatService.recordAssistantReplyOnInterruption(CONVERSATION_ID, tokens))
                .expectNext(new TurnEvent.Token("Your "), new TurnEvent.Token("order "))
                .verifyError(IllegalStateException.class);

        assertThat(chatMemory.added).singleElement()
                .extracting(Message::getText)
                .isEqualTo("Your order ");
        assertThat(chatMemory.conversationIds).containsExactly(CONVERSATION_ID);
        assertThat(terminations("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the two model calls of a tool-calling turn are not run together")
    void toolCallSeamBecomesAParagraphBreak() {
        Flux<TurnEvent> events = Flux.concat(
                tokens("I'll look ", "that up for you."),
                Flux.just(new TurnEvent.ToolCall("lookup_order_status", "found")),
                tokens("Your order ", "is in transit."),
                Flux.error(new IllegalStateException("upstream died")));

        StepVerifier.create(chatService.recordAssistantReplyOnInterruption(CONVERSATION_ID, events))
                .expectNextCount(5)
                .verifyError(IllegalStateException.class);

        assertThat(chatMemory.added).singleElement()
                .extracting(Message::getText)
                .isEqualTo("I'll look that up for you.\n\nYour order is in transit.");
    }

    @Test
    @DisplayName("a turn whose first model call produced no text gains no leading break")
    void aToolCallBeforeAnyTextAddsNoLeadingBreak() {
        Flux<TurnEvent> events = Flux.concat(
                Flux.just(new TurnEvent.ToolCall("lookup_order_status", "found")),
                tokens("Your order ", "is in transit."),
                Flux.error(new IllegalStateException("upstream died")));

        StepVerifier.create(chatService.recordAssistantReplyOnInterruption(CONVERSATION_ID, events))
                .expectNextCount(3)
                .verifyError(IllegalStateException.class);

        assertThat(chatMemory.added).singleElement()
                .extracting(Message::getText)
                .isEqualTo("Your order is in transit.");
    }

    @Test
    @DisplayName("an interruption before the first token writes nothing")
    void interruptionBeforeAnyTokenWritesNothing() {
        StepVerifier.create(chatService.recordAssistantReplyOnInterruption(CONVERSATION_ID, Flux.never()), 1)
                .expectSubscription()
                .thenAwait()
                .thenCancel()
                .verify();

        assertThat(chatMemory.added).isEmpty();
        assertThat(terminations("cancelled")).isEqualTo(1.0);
    }

    private static Flux<TurnEvent> tokens(String... texts) {
        return Flux.fromArray(texts).map(TurnEvent.Token::new);
    }

    private double terminations(String outcome) {
        return meterRegistry.get("chat.stream.terminations").tag("outcome", outcome).counter().count();
    }

    /**
     * Minimal ChatMemory that records what the service writes.
     *
     * <p>Note: an unqualified {@code CONVERSATION_ID} inside this class would resolve to the
     * inherited {@link ChatMemory#CONVERSATION_ID} constant, not the test's own field --
     * inherited members shadow the enclosing class. Assertions therefore live in the tests.
     */
    private static final class RecordingChatMemory implements ChatMemory {

        private final List<Message> added = new ArrayList<>();
        private final List<String> conversationIds = new ArrayList<>();

        @Override
        public void add(String conversationId, List<Message> messages) {
            conversationIds.add(conversationId);
            added.addAll(messages);
        }

        @Override
        public List<Message> get(String conversationId) {
            return List.copyOf(added);
        }

        @Override
        public void clear(String conversationId) {
            added.clear();
        }
    }
}
