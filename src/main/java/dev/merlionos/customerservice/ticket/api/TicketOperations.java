package dev.merlionos.customerservice.ticket.api;

import java.util.List;

/**
 * The seam between the {@code create_support_ticket} tool and the ticket business logic.
 *
 * <p>Implementations own the invariants -- one ticket per distinct request per conversation,
 * at most a fixed number per conversation -- and report every outcome as a value. They know
 * nothing about tools, tool contexts, turn events or meters; the tool adapter on the chat side
 * turns a result into those. That split is what lets the same implementation sit behind a
 * local call in one process and an HTTP endpoint in another.
 */
public interface TicketOperations {

    TicketResult create(TicketRequest request);

    /** Every ticket raised in a conversation, oldest first. Empty for an unknown conversation. */
    List<SupportTicket> ticketsFor(String conversationId);
}
