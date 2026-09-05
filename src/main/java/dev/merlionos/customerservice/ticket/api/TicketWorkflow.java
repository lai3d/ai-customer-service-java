package dev.merlionos.customerservice.ticket.api;

import java.util.List;
import java.util.Optional;

/**
 * The human side of tickets: the queue, ownership, the state machine and the record of every
 * change. The seam the operations admin reaches tickets through, as
 * {@link TicketOperations} is the seam the tool reaches them through.
 *
 * <h2>The state machine</h2>
 *
 * <pre>
 *   open ──claim/assign──▶ claimed ──resolve──▶ resolved ──close──▶ closed
 *     ▲                       │  │                  │                 │
 *     └───────release─────────┘  └──────close───────┘                 │
 *     ▲                                                               │
 *     └──────────────────────────reopen───────────────────────────────┘ (also from resolved)
 * </pre>
 *
 * <p>Claiming is first come, first served and atomic across replicas: two people who click
 * "claim" on the same open ticket get one owner and one {@link TicketConflictException}.
 * Every other change on a claimed ticket is the owner's to make, or an actor's with
 * {@link TicketActor#override()}. Reopening clears the owner: a reopened ticket goes back
 * to the queue, it does not go back to whoever had it.
 *
 * <h2>Versions</h2>
 *
 * <p>Every mutation takes the {@link TicketRecord#version()} the caller read. A stale one is
 * a {@link TicketConflictException} and nothing is written -- which is also what makes a
 * double-submitted request harmless: the second copy carries the version the first one
 * already moved past, so history gets one row, not two.
 */
public interface TicketWorkflow {

    Optional<TicketRecord> find(String ticketNumber);

    TicketPage search(TicketFilter filter);

    /** Every event on a ticket, oldest first. Empty for a ticket nobody has touched. */
    List<TicketEvent> history(String ticketNumber);

    /** {@code open} to {@code claimed}, owned by the actor. Only an unowned open ticket can be claimed. */
    TicketRecord claim(String ticketNumber, TicketActor actor, int expectedVersion);

    /**
     * {@code open} or {@code claimed} to {@code claimed}, owned by {@code assignee}. On an
     * already claimed ticket, the current owner or an overriding actor may reassign it.
     */
    TicketRecord assign(String ticketNumber, String assignee, TicketActor actor, int expectedVersion);

    /** {@code claimed} back to {@code open} and unowned. Owner or override. */
    TicketRecord release(String ticketNumber, TicketActor actor, int expectedVersion);

    /** {@code claimed} to {@code resolved}. Owner or override. */
    TicketRecord resolve(String ticketNumber, TicketActor actor, int expectedVersion);

    /** {@code claimed} or {@code resolved} to {@code closed}. Owner or override. */
    TicketRecord close(String ticketNumber, TicketActor actor, int expectedVersion);

    /** {@code resolved} or {@code closed} back to {@code open} and unowned. Anyone. */
    TicketRecord reopen(String ticketNumber, TicketActor actor, int expectedVersion);

    /** An internal note, in any state, by anyone. Changes the version like every other write. */
    TicketRecord addNote(String ticketNumber, String note, TicketActor actor, int expectedVersion);
}
