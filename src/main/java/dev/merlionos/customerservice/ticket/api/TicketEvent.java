package dev.merlionos.customerservice.ticket.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Instant;
import java.util.Locale;

/**
 * One thing done to a ticket by a person: a change of state or owner, or a note. Every event
 * says who and when; the from/to pairs say what changed, and are null for a note.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TicketEvent(
        long id,
        String ticketNumber,
        Kind kind,
        String actor,
        TicketState fromState,
        TicketState toState,
        String fromOwner,
        String toOwner,
        String note,
        Instant occurredAt) {

    public enum Kind {
        CLAIMED, ASSIGNED, RELEASED, RESOLVED, CLOSED, REOPENED, NOTE;

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        public static Kind fromValue(String value) {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        }
    }
}
