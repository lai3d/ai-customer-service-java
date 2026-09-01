package dev.merlionos.customerservice.chat;

import dev.merlionos.customerservice.cost.ConversationBudget;
import dev.merlionos.customerservice.tools.SupportTicketTools;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final TurnEventBus turnEventBus;
    private final ConversationBudget budget;
    private final ObjectProvider<Tracer> tracer;
    private final Counter streamsCompleted;
    private final Counter streamsCancelled;
    private final Counter streamsFailed;

    ChatService(ChatClient chatClient, ChatMemory chatMemory, TurnEventBus turnEventBus,
                ConversationBudget budget, ObjectProvider<Tracer> tracer,
                MeterRegistry meterRegistry) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.turnEventBus = turnEventBus;
        this.budget = budget;
        this.tracer = tracer;
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
        budget.checkRemaining(conversationId);

        // Deliberately not `.content()`. That discards the response metadata, and with it the
        // token usage -- so this path would spend money that the budget and the cost meters
        // never saw. Found by a test asserting the second request over budget was refused; it
        // was not, because the first request's tokens were never counted.
        ChatResponse response = chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .toolContext(toolContext(conversationId))
                .call()
                .chatResponse();

        recordUsage(conversationId, response);

        return response == null || response.getResult() == null
                ? ""
                : response.getResult().getOutput().getText();
    }

    private void recordUsage(String conversationId, ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return;
        }
        String model = response.getMetadata().getModel();
        budget.record(conversationId,
                model == null || model.isBlank() ? "unknown" : model,
                response.getMetadata().getUsage());
    }

    /**
     * Streams the turn as typed events rather than bare tokens: what was retrieved, which
     * tools ran, the answer itself, and what it cost.
     *
     * <p>Tool events arrive through {@link TurnEventBus} because tools execute inside the
     * model call with no other way back to the caller. The bus is closed when the model stream
     * terminates -- closing it from the merged stream's own completion would deadlock, since
     * the merge cannot complete until the tool flux does.
     */
    public Flux<TurnEvent> stream(String conversationId, String message) {
        // Checked before the Flux is built, so an exhausted budget is an HTTP status rather
        // than an error event buried in a stream that has already been committed as 200.
        budget.checkRemaining(conversationId);

        return Flux.defer(() -> {
            Flux<TurnEvent> toolEvents = turnEventBus.open(conversationId);
            Flux<TurnEvent> modelEvents = modelEvents(conversationId, message)
                    .doFinally(signal -> turnEventBus.close(conversationId));

            return Flux.merge(modelEvents, toolEvents);
        });
    }

    private Flux<TurnEvent> modelEvents(String conversationId, String message) {
        long started = System.currentTimeMillis();
        AtomicReference<org.springframework.ai.chat.metadata.Usage> usage = new AtomicReference<>();
        AtomicReference<String> model = new AtomicReference<>("unknown");

        Flux<TurnEvent> events = chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .toolContext(toolContext(conversationId))
                .stream()
                .chatClientResponse()
                // Retrieval is reported by RetrievalReportingAdvisor, which publishes to the
                // bus before the model is called -- so it arrives even when the call fails.
                .flatMap(response -> {
                    captureUsage(response, usage, model);
                    return tokenEvent(response);
                });

        return recordAssistantReplyOnInterruption(conversationId, events)
                .concatWith(Mono.fromSupplier(() -> {
                    budget.record(conversationId, model.get(), usage.get());
                    return usageEvent(usage.get(), started);
                }));
    }

    private static Mono<TurnEvent> tokenEvent(ChatClientResponse response) {
        String text = response.chatResponse() == null || response.chatResponse().getResult() == null
                ? null
                : response.chatResponse().getResult().getOutput().getText();

        return text == null || text.isEmpty() ? Mono.empty() : Mono.just(new TurnEvent.Token(text));
    }

    private static void captureUsage(ChatClientResponse response,
                                     AtomicReference<org.springframework.ai.chat.metadata.Usage> holder,
                                     AtomicReference<String> model) {
        if (response.chatResponse() != null && response.chatResponse().getMetadata() != null) {
            var metadata = response.chatResponse().getMetadata();
            var current = metadata.getUsage();
            if (current != null && current.getTotalTokens() != null && current.getTotalTokens() > 0) {
                holder.set(current);
            }
            if (metadata.getModel() != null && !metadata.getModel().isBlank()) {
                model.set(metadata.getModel());
            }
        }
    }

    private TurnEvent usageEvent(org.springframework.ai.chat.metadata.Usage usage, long started) {
        String traceId = currentTraceId();
        return new TurnEvent.Usage(
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                System.currentTimeMillis() - started,
                traceId);
    }

    private String currentTraceId() {
        Tracer available = tracer.getIfAvailable();
        if (available == null || available.currentSpan() == null) {
            return null;
        }
        return available.currentSpan().context().traceId();
    }

    /**
     * Keeps conversation history well-formed when a stream does not run to completion.
     *
     * <p>{@code MessageChatMemoryAdvisor} writes the user message in its {@code before()} hook
     * but writes the assistant reply from {@code MessageAggregator}, which only hooks
     * {@code doOnComplete}. A client that disconnects mid-answer would therefore leave an
     * orphaned user message behind and send two consecutive user turns on the next request.
     *
     * <p>So on cancellation or error we persist whatever was streamed. A truncated assistant
     * message is a far better history than a missing one.
     *
     * <p>Package-private and taking the event flux as a parameter so the interruption paths can
     * be exercised without a live model. See {@code ChatServiceStreamTest}.
     */
    Flux<TurnEvent> recordAssistantReplyOnInterruption(String conversationId, Flux<TurnEvent> events) {
        // StringBuffer, not StringBuilder: onNext and the doFinally callback are not
        // guaranteed to run on the same thread.
        StringBuffer streamed = new StringBuffer();
        AtomicBoolean completedNormally = new AtomicBoolean(false);

        return events
                .doOnNext(event -> {
                    if (event instanceof TurnEvent.Token token) {
                        streamed.append(token.text());
                    }
                })
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
                        log.debug("Stream for conversation {} ended as {} before any token arrived",
                                conversationId, signal);
                        return;
                    }
                    log.info("Stream for conversation {} ended as {}; persisting {} chars of partial reply",
                            conversationId, signal, streamed.length());
                    chatMemory.add(conversationId, new AssistantMessage(streamed.toString()));
                });
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
}
