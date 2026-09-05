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
import java.util.UUID;
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

        // No stream is listening, so nothing consumes the events; the id still has to exist
        // because tools and the retrieval advisor read it unconditionally.
        String turnId = UUID.randomUUID().toString();

        // Deliberately not `.content()`. That discards the response metadata, and with it the
        // token usage -- so this path would spend money that the budget and the cost meters
        // never saw. Found by a test asserting the second request over budget was refused; it
        // was not, because the first request's tokens were never counted.
        ChatResponse response = chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor
                        .param(ChatMemory.CONVERSATION_ID, conversationId)
                        .param(TurnEventBus.TURN_ID_KEY, turnId))
                .toolContext(toolContext(conversationId, turnId))
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
        TurnUsage usage = new TurnUsage();
        usage.record(response.getMetadata().getUsage());
        budget.record(conversationId,
                model == null || model.isBlank() ? "unknown" : model,
                usage.inputTokens(), usage.outputTokens());
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

        // Read here, on the request thread, while the HTTP span is still current. Reading it
        // where the usage event is built instead returned null every time: that runs in a
        // Mono.fromSupplier on a Reactor thread, outside the observation scope. The spans
        // themselves were always fine -- only this id was missing, so the demo UI could not
        // link a turn to its trace.
        String traceId = currentTraceId();

        return Flux.defer(() -> {
            // The channel is per turn. Closing by conversation id used to complete whichever
            // turn registered last and orphan the other one's stream forever.
            TurnEventBus.Channel channel = turnEventBus.open();
            Flux<TurnEvent> modelEvents = modelEvents(conversationId, channel.turnId(), traceId, message)
                    .doFinally(signal -> turnEventBus.close(channel.turnId()));

            return Flux.merge(modelEvents, channel.events());
        });
    }

    private Flux<TurnEvent> modelEvents(String conversationId, String turnId, String traceId,
                                        String message) {
        long started = System.currentTimeMillis();
        TurnUsage usage = new TurnUsage();
        AtomicReference<String> model = new AtomicReference<>("unknown");

        Flux<TurnEvent> events = chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor
                        .param(ChatMemory.CONVERSATION_ID, conversationId)
                        .param(TurnEventBus.TURN_ID_KEY, turnId))
                .toolContext(toolContext(conversationId, turnId))
                .stream()
                .chatClientResponse()
                // Retrieval is reported by RetrievalReportingAdvisor, which publishes to the
                // bus before the model is called -- so it arrives even when the call fails.
                .flatMap(response -> {
                    captureUsage(response, usage, model);
                    return tokenEvent(response);
                });

        // Accounting runs on every terminal signal, not only on completion. A cancelled or
        // failed turn still consumed whatever the provider had already reported, and recording
        // it only on success meant repeatedly aborted requests slipped the conversation budget
        // and under-reported the global cost meters. The guard makes it exactly once: the
        // completion path below and doFinally both call it.
        AtomicBoolean recorded = new AtomicBoolean();
        Runnable recordOnce = () -> {
            if (recorded.compareAndSet(false, true)) {
                budget.record(conversationId, model.get(), usage.inputTokens(), usage.outputTokens());
            }
        };

        return recordAssistantReplyOnInterruption(conversationId, events)
                .concatWith(Mono.fromSupplier(() -> {
                    recordOnce.run();
                    return usageEvent(usage, started, traceId);
                }))
                .doFinally(signal -> recordOnce.run());
    }

    private static Mono<TurnEvent> tokenEvent(ChatClientResponse response) {
        String text = response.chatResponse() == null || response.chatResponse().getResult() == null
                ? null
                : response.chatResponse().getResult().getOutput().getText();

        return text == null || text.isEmpty() ? Mono.empty() : Mono.just(new TurnEvent.Token(text));
    }

    /**
     * Usage arrives on the provider's final chunk, so a turn cancelled early often has none to
     * capture and is accounted as zero. That is a real limitation rather than an oversight: the
     * alternative is reserving an estimate up front and reconciling, which is worth doing when
     * the budget has to be exact and is not worth it here.
     */
    private static void captureUsage(ChatClientResponse response, TurnUsage usage,
                                     AtomicReference<String> model) {
        if (response.chatResponse() != null && response.chatResponse().getMetadata() != null) {
            var metadata = response.chatResponse().getMetadata();
            usage.record(metadata.getUsage());
            if (metadata.getModel() != null && !metadata.getModel().isBlank()) {
                model.set(metadata.getModel());
            }
        }
    }

    private static TurnEvent usageEvent(TurnUsage usage, long started, String traceId) {
        return new TurnEvent.Usage(
                usage.isEmpty() ? null : usage.inputTokens(),
                usage.isEmpty() ? null : usage.outputTokens(),
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
        // A tool-calling turn is two model calls, and the second one's text is a new message
        // rather than a continuation of the first. Appended raw they run together -- a real
        // turn persisted "I'll look that up for you.Your order ORD-10042". A break at the seam
        // is what the customer saw and what the next turn should be re-sent.
        AtomicBoolean textSinceTool = new AtomicBoolean(true);

        return events
                .doOnNext(event -> {
                    if (event instanceof TurnEvent.ToolCall) {
                        textSinceTool.set(false);
                    }
                    else if (event instanceof TurnEvent.Token token && !token.text().isEmpty()) {
                        if (!textSinceTool.getAndSet(true) && !streamed.isEmpty()) {
                            streamed.append("\n\n");
                        }
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
    private static Map<String, Object> toolContext(String conversationId, String turnId) {
        return Map.of(SupportTicketTools.CONVERSATION_ID_KEY, conversationId,
                TurnEventBus.TURN_ID_KEY, turnId);
    }
}
