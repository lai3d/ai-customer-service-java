package dev.merlionos.customerservice.chat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import dev.merlionos.customerservice.tools.SupportTicketTools;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final Counter streamsCompleted;
    private final Counter streamsCancelled;
    private final Counter streamsFailed;

    ChatService(ChatClient chatClient, ChatMemory chatMemory, MeterRegistry meterRegistry) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.streamsCompleted = terminationCounter(meterRegistry, "completed");
        this.streamsCancelled = terminationCounter(meterRegistry, "cancelled");
        this.streamsFailed = terminationCounter(meterRegistry, "failed");
    }

    private static Counter terminationCounter(MeterRegistry registry, String outcome) {
        return Counter.builder("chat.stream.terminations")
                .description("How streamed chat responses ended")
                .tag("outcome", outcome)
                .register(registry);
    }

    /**
     * Blocking single-shot completion. Useful for clients that cannot consume SSE, and for
     * tests that would otherwise have to parse an event stream.
     */
    public String ask(String conversationId, String message) {
        return chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .toolContext(toolContext(conversationId))
                .call()
                .content();
    }

    /**
     * Tools that take a {@code ToolContext} parameter fail outright when the context is absent
     * or empty -- Spring AI raises {@code IllegalArgumentException} before the tool body runs.
     * Every path that reaches the model therefore has to supply this, which is what
     * {@code ChatServiceToolContextTest} checks.
     */
    private static Map<String, Object> toolContext(String conversationId) {
        return Map.of(SupportTicketTools.CONVERSATION_ID_KEY, conversationId);
    }

    public Flux<String> stream(String conversationId, String message) {
        Flux<String> tokens = chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .toolContext(toolContext(conversationId))
                .stream()
                .content();

        return recordAssistantReplyOnInterruption(conversationId, tokens);
    }

    /**
     * Keeps conversation history well-formed when a stream does not run to completion.
     *
     * <p>{@code MessageChatMemoryAdvisor} writes the user message to memory in its
     * {@code before()} hook, but writes the assistant reply from {@code MessageAggregator},
     * which only hooks {@code doOnComplete}. If the client disconnects mid-stream the
     * assistant message is therefore never stored, leaving an orphaned user message behind
     * and sending two consecutive user turns on the next request.
     *
     * <p>So on cancellation or error we persist whatever was streamed. A truncated
     * assistant message is a far better history than a missing one.
     *
     * <p>Package-private and taking the token flux as a parameter so the interruption
     * paths can be exercised without a live model. See {@code ChatServiceStreamTest}.
     */
    Flux<String> recordAssistantReplyOnInterruption(String conversationId, Flux<String> tokens) {
        // StringBuffer, not StringBuilder: onNext and the doFinally callback are not
        // guaranteed to run on the same thread.
        StringBuffer streamed = new StringBuffer();
        AtomicBoolean completedNormally = new AtomicBoolean(false);

        return tokens
                .doOnNext(streamed::append)
                // Fires after the advisor's own aggregation, which sits upstream.
                .doOnComplete(() -> completedNormally.set(true))
                .doFinally(signal -> {
                    if (completedNormally.get()) {
                        streamsCompleted.increment();
                        return;
                    }
                    switch (signal) {
                        case ON_ERROR -> streamsFailed.increment();
                        case CANCEL -> streamsCancelled.increment();
                        default -> { /* ON_COMPLETE is handled above */ }
                    }
                    if (streamed.isEmpty()) {
                        // Nothing was generated, so the advisor's user message is the only
                        // record of this turn. Left in place: it is a real thing the
                        // customer said, and the next turn will be answered against it.
                        log.debug("Stream for conversation {} ended as {} before any token arrived",
                                conversationId, signal);
                        return;
                    }
                    log.info("Stream for conversation {} ended as {}; persisting {} chars of partial reply",
                            conversationId, signal, streamed.length());
                    chatMemory.add(conversationId, new AssistantMessage(streamed.toString()));
                });
    }
}
