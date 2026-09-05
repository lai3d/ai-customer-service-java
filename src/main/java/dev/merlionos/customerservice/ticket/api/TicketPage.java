package dev.merlionos.customerservice.ticket.api;

import java.util.List;

/** One page of a ticket list, with the total so the page can say how many there are. */
public record TicketPage(List<TicketRecord> tickets, long total, int page, int size) {
}
