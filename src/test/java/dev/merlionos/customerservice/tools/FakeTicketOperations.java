package dev.merlionos.customerservice.tools;

import dev.merlionos.customerservice.ticket.api.SupportTicket;
import dev.merlionos.customerservice.ticket.api.TicketOperations;
import dev.merlionos.customerservice.ticket.api.TicketRequest;
import dev.merlionos.customerservice.ticket.api.TicketResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A scripted seam for the adapter tests. It reproduces the three outcomes -- created,
 * duplicate, capped -- so the adapter's mapping of each can be asserted, and nothing else:
 * the real invariants are {@code JdbcTicketOperationsTest}'s, against Postgres.
 */
class FakeTicketOperations implements TicketOperations {

    private final List<SupportTicket> tickets = new ArrayList<>();
    private int sequence = 4700;

    @Override
    public TicketResult create(TicketRequest request) {
        String key = request.summary().strip().toLowerCase(Locale.ROOT);
        for (SupportTicket existing : tickets) {
            if (existing.conversationId().equals(request.conversationId())
                    && existing.summary().strip().toLowerCase(Locale.ROOT).equals(key)) {
                return TicketResult.existing(new SupportTicket(existing.ticketNumber(), existing.conversationId(),
                        existing.category(), existing.summary(), existing.orderNumber(), existing.createdAt(), true));
            }
        }
        if (ticketsFor(request.conversationId()).size() >= 3) {
            return TicketResult.refused("This conversation already has the maximum number of open tickets. "
                    + "A human agent is already involved; do not raise another.");
        }
        SupportTicket ticket = new SupportTicket("TKT-" + (++sequence), request.conversationId(),
                request.category(), request.summary(), request.orderNumber(), Instant.now(), false);
        tickets.add(ticket);
        return TicketResult.created(ticket);
    }

    @Override
    public List<SupportTicket> ticketsFor(String conversationId) {
        return tickets.stream().filter(t -> t.conversationId().equals(conversationId)).toList();
    }
}
