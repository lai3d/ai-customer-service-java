package dev.merlionos.customerservice.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SseHeartbeatTest {

    @Test
    @DisplayName("the turn is subscribed exactly once, however the heartbeat is merged in")
    void subscribesUpstreamOnlyOnce() {
        // The heartbeat needs the upstream twice: once to merge, once to know when to stop. Get
        // that wrong and the whole turn runs twice -- two model calls, two bills, two sets of
        // tokens written to memory -- while the response still looks correct.
        AtomicInteger subscriptions = new AtomicInteger();
        Flux<ServerSentEvent<TurnEvent>> events = Flux
                .just(ServerSentEvent.<TurnEvent>builder(new TurnEvent.Token("hi")).build())
                .doOnSubscribe(subscription -> subscriptions.incrementAndGet());

        StepVerifier.create(ChatController.withHeartbeat(events))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(subscriptions).hasValue(1);
    }

    @Test
    @DisplayName("the heartbeat stops when the answer does, rather than holding the connection")
    void completesWithTheAnswer() {
        StepVerifier.create(ChatController.withHeartbeat(Flux.empty()))
                .verifyComplete();
    }
}
