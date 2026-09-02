package dev.merlionos.customerservice.tools;

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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TicketResult(boolean created, SupportTicket ticket, String explanation) {

    static TicketResult created(SupportTicket ticket) {
        return new TicketResult(true, ticket, null);
    }

    static TicketResult existing(SupportTicket ticket) {
        return new TicketResult(false, ticket,
                "A ticket for this was already raised in this conversation.");
    }

    static TicketResult refused(String explanation) {
        return new TicketResult(false, null, explanation);
    }
}
