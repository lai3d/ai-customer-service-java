package dev.merlionos.customerservice.chat;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carries events from code that runs deep inside a model call back out to the response stream.
 *
 * <p>Tools are the reason this exists. Spring AI executes them inside the chat call, on its own
 * scheduler, with no return path to the controller other than the model's eventual answer -- so
 * a tool invocation is invisible to a client until the assistant happens to mention it. Keying
 * a sink by conversation id gives tools somewhere to publish, using the conversation id they
 * already receive through {@code ToolContext}.
 *
 * <p>The keying assumes one in-flight turn per conversation, which is what a chat UI does: the
 * customer is waiting for the answer. A second concurrent turn on the same conversation would
 * have its tool events attributed to the first. That is acceptable for an inspection channel
 * and would not be for anything the answer depended on.
 *
 * <p>A sink that is never opened swallows emissions rather than failing, so the blocking
 * endpoint and the tests can call tools with no stream attached.
 */
@Component
public class TurnEventBus {

    private final Map<String, Sinks.Many<TurnEvent>> sinks = new ConcurrentHashMap<>();

    Flux<TurnEvent> open(String conversationId) {
        Sinks.Many<TurnEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        sinks.put(conversationId, sink);
        return sink.asFlux();
    }

    void close(String conversationId) {
        Sinks.Many<TurnEvent> sink = sinks.remove(conversationId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    /** Called from tool code. A conversation with no open stream is a no-op. */
    public void publish(String conversationId, TurnEvent event) {
        Sinks.Many<TurnEvent> sink = sinks.get(conversationId);
        if (sink != null) {
            sink.tryEmitNext(event);
        }
    }
}
