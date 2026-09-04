package dev.merlionos.customerservice.chat;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carries events from code that runs deep inside a model call back out to the response stream.
 *
 * <p>Tools are the reason this exists. Spring AI executes them inside the chat call, on its own
 * scheduler, with no return path to the controller other than the model's eventual answer -- so
 * a tool invocation is invisible to a client until the assistant happens to mention it.
 *
 * <p>Channels are keyed by <em>turn</em>, not by conversation. Keying by conversation was the
 * first design and it was wrong in a way that a comment tried to excuse: two overlapping turns
 * on one conversation meant the second {@code open} replaced the first's sink, so the first
 * stream could never be completed by anything and hung until the client gave up, while tool
 * events landed on whichever turn happened to be registered. Two API clients sharing a
 * conversation id is enough to trigger it. {@code TurnEventBusConcurrencyTest} pins the fixed
 * behaviour.
 *
 * <p>A turn id that has already been closed, or was never opened, swallows emissions rather
 * than failing -- the blocking endpoint and the unit tests call tools with no stream attached.
 */
@Component
public class TurnEventBus {

    /** Key under which {@code ChatService} puts the turn id into the tool and advisor contexts. */
    public static final String TURN_ID_KEY = "turnId";

    private final Map<String, Sinks.Many<TurnEvent>> sinksByTurn = new ConcurrentHashMap<>();

    /** One open channel. Closing it needs this handle, not a conversation id. */
    public record Channel(String turnId, Flux<TurnEvent> events) {
    }

    Channel open() {
        String turnId = UUID.randomUUID().toString();
        Sinks.Many<TurnEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        sinksByTurn.put(turnId, sink);
        return new Channel(turnId, sink.asFlux());
    }

    void close(String turnId) {
        Sinks.Many<TurnEvent> sink = sinksByTurn.remove(turnId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    /** Called from tool and advisor code. A turn with no open stream is a no-op. */
    public void publish(String turnId, TurnEvent event) {
        Sinks.Many<TurnEvent> sink = sinksByTurn.get(turnId);
        if (sink != null) {
            sink.tryEmitNext(event);
        }
    }

    /** Visible for tests: no channel should outlive the turn that opened it. */
    int openChannels() {
        return sinksByTurn.size();
    }
}
