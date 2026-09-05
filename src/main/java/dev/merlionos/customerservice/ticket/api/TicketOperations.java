package dev.merlionos.customerservice.ticket.api;

import java.util.List;
import java.util.Optional;

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

    /**
     * Idempotent on {@link TicketRequest#operationId()}: the same operation asked again
     * returns what it returned the first time. The same operation id with different input is
     * a programming error and throws {@link OperationConflictException}.
     */
    TicketResult create(TicketRequest request);

    /** What an operation returned, if it completed; empty if it never did. For recovery after an ambiguous timeout. */
    Optional<TicketResult> recorded(String operationId);

    /** Every ticket raised in a conversation, oldest first. Empty for an unknown conversation. */
    List<SupportTicket> ticketsFor(String conversationId);
}
