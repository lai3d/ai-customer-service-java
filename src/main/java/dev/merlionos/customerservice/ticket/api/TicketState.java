package dev.merlionos.customerservice.ticket.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Where a ticket is in its life. The AI creates it {@link #OPEN}; a person takes it to
 * {@link #CLAIMED}, finishes it as {@link #RESOLVED}, and {@link #CLOSED} is the end. Any
 * closed or resolved ticket can be reopened, which is a new start, not a resumption: it is
 * unowned again.
 */
public enum TicketState {
    OPEN, CLAIMED, RESOLVED, CLOSED;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static TicketState fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("state is required: one of open, claimed, resolved, closed");
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown state '" + value + "': one of open, claimed, resolved, closed", e);
        }
    }
}
