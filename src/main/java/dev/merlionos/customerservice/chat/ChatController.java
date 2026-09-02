package dev.merlionos.customerservice.chat;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
class ChatController {

    static final String CONVERSATION_ID_HEADER = "X-Conversation-Id";
    static final String TOKEN_EVENT = "message";
    static final String ERROR_EVENT = "error";
    static final String RETRIEVAL_EVENT = "retrieval";
    static final String USAGE_EVENT = "usage";

    /**
     * Comment-only frames keep the connection alive. Proxies and load balancers close idle
     * connections, and this stream is legitimately idle between the request and the first
     * token -- which, with retrieval and a slow model, can be several seconds. A comment is
     * invisible to EventSource and to any correct SSE parser, so no client needs to know.
     */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ChatReply> chat(@Valid @RequestBody ChatRequest request) {
        String conversationId = resolveConversationId(request);

        return ResponseEntity.ok()
                .header(CONVERSATION_ID_HEADER, conversationId)
                .body(new ChatReply(conversationId, chatService.ask(conversationId, request.message())));
    }

    /**
     * {@code Flux} appears here and nowhere else: it is the return type Spring MVC needs to
     * write an SSE stream. The request itself is still served on a virtual thread.
     *
     * <p>Every event carries a JSON body and a name -- {@code retrieval}, {@code tool},
     * {@code message}, {@code usage}, {@code error} -- so a client can decide what to render.
     * A chat widget would use {@code message} and {@code error} and ignore the rest; the demo
     * UI shows all of them, because the point of this project is the part a widget hides.
     *
     * <p>Once the first byte is written the status code is settled, so a mid-stream failure
     * cannot be reported the way {@link ChatExceptionHandler} reports one on the blocking
     * endpoint. It is emitted as a terminal {@code error} event instead -- without a named
     * event type a client cannot tell an apology from an answer.
     */
    @PostMapping(path = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<Flux<ServerSentEvent<TurnEvent>>> stream(@Valid @RequestBody ChatRequest request) {
        String conversationId = resolveConversationId(request);

        Flux<ServerSentEvent<TurnEvent>> events = chatService.stream(conversationId, request.message())
                .map(ChatController::toServerSentEvent)
                .onErrorResume(error -> {
                    log.error("Streamed chat failed for conversation {}", conversationId, error);
                    return Flux.just(toServerSentEvent(new TurnEvent.Failure(
                            "The assistant was interrupted. Please try again.")));
                });

        return ResponseEntity.ok()
                .header(CONVERSATION_ID_HEADER, conversationId)
                .body(withHeartbeat(events));
    }

    private static ServerSentEvent<TurnEvent> toServerSentEvent(TurnEvent event) {
        return ServerSentEvent.builder(event).event(event.name()).build();
    }

    /**
     * {@code publish} is what makes this correct: the heartbeat has to stop when the answer
     * does, and both the merge and the stop condition need the same single subscription to the
     * upstream. Subscribing twice would run the whole turn twice.
     */
    static Flux<ServerSentEvent<TurnEvent>> withHeartbeat(
            Flux<ServerSentEvent<TurnEvent>> events) {

        Flux<ServerSentEvent<TurnEvent>> heartbeats = Flux.interval(HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<TurnEvent>builder().comment("keep-alive").build());

        return events.publish(shared ->
                Flux.merge(shared, heartbeats.takeUntilOther(shared.ignoreElements())));
    }

    private static String resolveConversationId(ChatRequest request) {
        return StringUtils.hasText(request.conversationId())
                ? request.conversationId()
                : UUID.randomUUID().toString();
    }
}
