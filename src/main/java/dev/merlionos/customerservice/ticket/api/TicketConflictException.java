package dev.merlionos.customerservice.ticket.api;

/**
 * The ticket changed under the caller: the version presented is not the current one, or
 * someone else claimed it first. Not a rule violation -- the same request against a fresh
 * read might well succeed -- so the right response is to reload and look again. A {@code 409}.
 */
public class TicketConflictException extends RuntimeException {

    public TicketConflictException(String ticketNumber, String what) {
        super("Ticket " + ticketNumber + " " + what);
    }
}
