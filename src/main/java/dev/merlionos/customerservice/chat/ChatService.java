package dev.merlionos.customerservice.chat;

import dev.merlionos.customerservice.clients.HttpKnowledgeSearch;
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
    private final ConversationLease lease;
    private final TurnRecorder recorder;
    private final ObjectProvider<Tracer> tracer;
    private final Counter streamsCompleted;
    private final Counter streamsCancelled;
    private final Counter streamsFailed;

    ChatService(ChatClient chatClient, ChatMemory chatMemory, TurnEventBus turnEventBus,
                ConversationBudget budget, ConversationLease lease, TurnRecorder recorder,
                ObjectProvider<Tracer> tracer, MeterRegistry meterRegistry) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.turnEventBus = turnEventBus;
        this.budget = budget;
        this.lease = lease;
        this.recorder = recorder;
        this.tracer = tracer;
        this.streamsCompleted = terminationCounter(meterRegistry, "completed");
        this.streamsCancelled = terminationCounter(meterRegistry, "cancelled");
        this.streamsFailed = terminationCounter(meterRegistry, "failed");
        // Incremented by HttpKnowledgeSearch, which only a chat process has. Registered here
        // as well because this service is in every topology that answers turns, so the panel
        // and the alert on it have a series at zero in an `all` process too, where it cannot
        // rise; DashboardMetricsTest runs as `all` and would otherwise find it missing.
        HttpKnowledgeSearch.unavailableCounter(meterRegistry);
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

        // No client stream is listening, but the record is: the channel is opened so the
        // retrieval and tool events the advisor and the tools publish reach the turn's row,
        // the same as on the streaming path.
        String turnId = UUID.randomUUID().toString();
        lease.acquire(conversationId, turnId);
        try {
            return askHoldingLease(conversationId, turnId, message);
        }
        finally {
            lease.release(conversationId, turnId);
        }
    }

    private String askHoldingLease(String conversationId, String turnId, String message) {
        // The first row, before the model. If this throws, the model is never called.
        recorder.start(turnId, conversationId, TurnRecorder.Path.BLOCKING, message);
        TurnEventBus.Channel channel = turnEventBus.open(turnId);
        channel.events().subscribe(event -> recordEvent(turnId, event));
        String traceId = currentTraceId();
        try {
            String answer = callHoldingLease(conversationId, turnId, message);
            return answer;
        }
        catch (RuntimeException e) {
            recorder.finish(turnId, TurnRecorder.Outcome.FAILED, null, null, null, null, traceId, e);
            throw e;
        }
        finally {
            turnEventBus.close(turnId);
        }
    }

    private String callHoldingLease(String conversationId, String turnId, String message) {
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

        String answer = response == null || response.getResult() == null
                ? ""
                : response.getResult().getOutput().getText();
        recordUsage(conversationId, turnId, response, answer);
        return answer;
    }

    private void recordUsage(String conversationId, String turnId, ChatResponse response, String answer) {
        if (response == null || response.getMetadata() == null) {
            recorder.finish(turnId, TurnRecorder.Outcome.COMPLETED, answer, null, null, null, currentTraceId(), null);
            return;
        }
        String reported = response.getMetadata().getModel();
        String model = reported == null || reported.isBlank() ? "unknown" : reported;
        TurnUsage usage = new TurnUsage();
        usage.record(response.getMetadata().getUsage());
        budget.record(conversationId, model, usage.inputTokens(), usage.outputTokens());
        recorder.finish(turnId, TurnRecorder.Outcome.COMPLETED, answer, model,
                usage.isEmpty() ? null : usage.inputTokens(), usage.isEmpty() ? null : usage.outputTokens(),
                currentTraceId(), null);
    }

    /** Retrieval and tool events, from either path, into the turn's record. */
    private void recordEvent(String turnId, TurnEvent event) {
        if (event instanceof TurnEvent.Retrieval retrieval) {
            recorder.retrieved(turnId, retrieval.passages());
        }
        else if (event instanceof TurnEvent.ToolCall call) {
            recorder.toolCalled(turnId, call.tool(), call.outcome());
        }
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

        // Also before the Flux: a conversation with a turn in flight is a 409, not an error
        // event on a stream that has already been committed as 200. The turn id is chosen here
        // so the lease and the event channel name the same turn.
        String turnId = UUID.randomUUID().toString();
        lease.acquire(conversationId, turnId);

        // The first row, before the model and before the response is committed: a turn that
        // cannot be recorded is refused here as a status, not started and lost.
        try {
            recorder.start(turnId, conversationId, TurnRecorder.Path.STREAM, message);
        }
        catch (RuntimeException e) {
            lease.release(conversationId, turnId);
            throw e;
        }
        Recording recording = new Recording();

        return Flux.defer(() -> {
            // The channel is per turn. Closing by conversation id used to complete whichever
            // turn registered last and orphan the other one's stream forever.
            TurnEventBus.Channel channel = turnEventBus.open(turnId);
            Flux<TurnEvent> modelEvents = modelEvents(conversationId, channel.turnId(), traceId, message, recording)
                    .doFinally(signal -> turnEventBus.close(channel.turnId()));

            // Finished on the signal itself, not in doFinally: doFinally runs after the terminal
            // signal has been handed downstream, so a client that blocks for the end of the
            // stream could read the record before it was written. These three run before.
            return Flux.merge(modelEvents, channel.events())
                    .doOnNext(event -> {
                        recordEvent(turnId, event);
                        recording.saw(event);
                    })
                    .doOnComplete(() -> finish(turnId, TurnRecorder.Outcome.COMPLETED, recording, traceId, null))
                    .doOnError(error -> finish(turnId, TurnRecorder.Outcome.FAILED, recording, traceId, error))
                    .doOnCancel(() -> finish(turnId, TurnRecorder.Outcome.INTERRUPTED, recording, traceId, null));
        }).doFinally(signal -> lease.release(conversationId, turnId));
    }

    private void finish(String turnId, TurnRecorder.Outcome outcome, Recording recording, String traceId,
                        Throwable failure) {
        recorder.finish(turnId, outcome, recording.answer(), recording.model.get(),
                recording.usage.isEmpty() ? null : recording.usage.inputTokens(),
                recording.usage.isEmpty() ? null : recording.usage.outputTokens(),
                traceId, failure);
    }

    /**
     * What one streamed turn accumulates for its record: the answer as the customer saw it
     * (with the same break at a tool boundary that {@link #recordAssistantReplyOnInterruption}
     * makes), the usage and model as the provider reported them, and the failure if any.
     */
    private static final class Recording {
        final StringBuffer answer = new StringBuffer();
        final AtomicBoolean textSinceTool = new AtomicBoolean(true);
        final TurnUsage usage = new TurnUsage();
        final AtomicReference<String> model = new AtomicReference<>("unknown");

        void saw(TurnEvent event) {
            if (event instanceof TurnEvent.ToolCall) {
                textSinceTool.set(false);
            }
            else if (event instanceof TurnEvent.Token token && !token.text().isEmpty()) {
                if (!textSinceTool.getAndSet(true) && !answer.isEmpty()) {
                    answer.append("\n\n");
                }
                answer.append(token.text());
            }
        }

        String answer() {
            return answer.isEmpty() ? null : answer.toString();
        }
    }

    private Flux<TurnEvent> modelEvents(String conversationId, String turnId, String traceId,
                                        String message, Recording recording) {
        long started = System.currentTimeMillis();
        TurnUsage usage = recording.usage;
        AtomicReference<String> model = recording.model;

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
