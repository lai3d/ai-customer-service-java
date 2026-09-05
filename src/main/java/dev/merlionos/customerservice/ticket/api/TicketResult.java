package dev.merlionos.customerservice.ticket.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The outcome of asking for a ticket.
 *
 * <p>A refusal is a value, not an exception, for the same reason a missing order is: Spring
 * AI's default behaviour on a thrown tool exception is to hand the exception's message back to
 * the model, and this project's processor replaces that with a fixed instruction to offer a
 * support ticket -- exactly the wrong thing to say when the problem is that too many tickets
 * have already been raised. A result the model can read gives it something true to tell the
 * customer.
 *
 * <p>{@link Status#UNAVAILABLE} is the chat side's own outcome: the ticket service could not
 * be reached, retried, or recovered from. It never comes back over the wire.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TicketResult(Status status, boolean created, SupportTicket ticket, String explanation) {

    public enum Status { CREATED, EXISTING, REFUSED, UNAVAILABLE }

    public static TicketResult created(SupportTicket ticket) {
        return new TicketResult(Status.CREATED, true, ticket, null);
    }

    public static TicketResult existing(SupportTicket ticket) {
        return new TicketResult(Status.EXISTING, false, ticket,
                "A ticket for this was already raised in this conversation.");
    }

    public static TicketResult refused(String explanation) {
        return new TicketResult(Status.REFUSED, false, null, explanation);
    }

    public static TicketResult unavailable() {
        return new TicketResult(Status.UNAVAILABLE, false, null,
                "The ticketing system could not be reached just now. Apologise, and tell the "
                        + "customer a human agent will follow up; do not try again in this turn.");
    }
}
