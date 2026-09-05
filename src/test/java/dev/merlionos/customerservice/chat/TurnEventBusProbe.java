package dev.merlionos.customerservice.chat;

/**
 * Lets tests outside this package open and close a channel. {@code open} and {@code close}
 * are package-private on purpose -- only {@code ChatService} owns a turn's lifecycle -- and
 * this keeps that true in production code while a tool adapter's test can still listen.
 */
public final class TurnEventBusProbe {

    private TurnEventBusProbe() {
    }

    public static TurnEventBus.Channel open(TurnEventBus bus) {
        return bus.open();
    }

    public static void close(TurnEventBus bus, String turnId) {
        bus.close(turnId);
    }
}
