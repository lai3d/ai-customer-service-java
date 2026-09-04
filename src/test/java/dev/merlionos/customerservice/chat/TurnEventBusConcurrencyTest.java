package dev.merlionos.customerservice.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two turns can be in flight on one conversation -- two API clients sharing an id, a browser
 * tab left open, a retry that overlaps the original. Keying channels by conversation meant the
 * second {@code open} replaced the first's sink: the first stream could then never be completed
 * by anything and hung until the client gave up, and tool events went to whichever turn
 * happened to be registered. These tests were written to reproduce that and kept to prevent it.
 */
class TurnEventBusConcurrencyTest {

    @Test
    @DisplayName("overlapping turns on one conversation each complete on their own")
    void overlappingTurnsCompleteIndependently() {
        TurnEventBus bus = new TurnEventBus();

        TurnEventBus.Channel first = bus.open();
        TurnEventBus.Channel second = bus.open();

        assertThat(first.turnId()).isNotEqualTo(second.turnId());

        StepVerifier.create(first.events()).then(() -> bus.close(first.turnId())).verifyComplete();
        StepVerifier.create(second.events()).then(() -> bus.close(second.turnId())).verifyComplete();
    }

    @Test
    @DisplayName("a tool event reaches its own turn and not the other one")
    void toolEventsDoNotCrossWire() {
        TurnEventBus bus = new TurnEventBus();

        TurnEventBus.Channel first = bus.open();
        TurnEventBus.Channel second = bus.open();

        TurnEvent forSecond = new TurnEvent.ToolCall("lookup_order_status", "found");

        StepVerifier.create(first.events())
                .then(() -> {
                    bus.publish(second.turnId(), forSecond);
                    bus.close(first.turnId());
                })
                .verifyComplete();   // first completes having seen nothing

        // The sink buffers before its first subscriber, so the event published above is still
        // waiting -- and it is the only one, which is the point: it went to exactly one turn.
        StepVerifier.create(second.events())
                .then(() -> bus.close(second.turnId()))
                .expectNext(forSecond)
                .verifyComplete();
    }

    @Test
    @DisplayName("closing a turn leaves nothing behind")
    void closingReleasesTheChannel() {
        TurnEventBus bus = new TurnEventBus();

        TurnEventBus.Channel channel = bus.open();
        assertThat(bus.openChannels()).isEqualTo(1);

        bus.close(channel.turnId());
        assertThat(bus.openChannels())
                .as("a leaked channel is a leaked subscription and a stream that never ends")
                .isZero();
    }

    @Test
    @DisplayName("publishing to a closed or unknown turn is a no-op, not a failure")
    void publishingToAnUnknownTurnIsHarmless() {
        TurnEventBus bus = new TurnEventBus();

        bus.publish("never-opened", new TurnEvent.ToolCall("lookup_order_status", "found"));
        bus.close("never-opened");

        assertThat(bus.openChannels()).isZero();
    }
}
