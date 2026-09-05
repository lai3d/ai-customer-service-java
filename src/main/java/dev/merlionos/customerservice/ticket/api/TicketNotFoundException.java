package dev.merlionos.customerservice.ticket.api;

/** No ticket with that number. A {@code 404} on the API. */
public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(String ticketNumber) {
        super("No ticket " + ticketNumber);
    }
}
