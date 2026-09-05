package dev.merlionos.customerservice.ticket.api;

/**
 * The change is not allowed from where the ticket is or by who is asking: closing an open
 * ticket, resolving someone else's. Reloading will not help. A {@code 422}.
 */
public class TicketRuleException extends RuntimeException {

    public TicketRuleException(String ticketNumber, String what) {
        super("Ticket " + ticketNumber + " " + what);
    }
}
