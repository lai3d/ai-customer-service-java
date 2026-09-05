package dev.merlionos.customerservice.ticket.api;

import java.time.Instant;

/**
 * A ticket as the people handling it see it. Distinct from {@link SupportTicket}, which is
 * what the tool hands the model: the model has no business knowing who owns a ticket, and
 * the wire format between roles should not change because the admin learnt a column.
 *
 * @param version bumped by every change; a mutation must present the version it read
 */
public record TicketRecord(
        String ticketNumber,
        String conversationId,
        String category,
        String summary,
        String orderNumber,
        TicketState state,
        String owner,
        Instant createdAt,
        Instant updatedAt,
        int version) {
}
