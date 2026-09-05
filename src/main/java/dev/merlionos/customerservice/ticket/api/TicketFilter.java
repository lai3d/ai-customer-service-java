package dev.merlionos.customerservice.ticket.api;

import java.time.Instant;

/**
 * What the ticket list is asked for. Every field is optional except the page; {@code owner}
 * of {@link #UNASSIGNED} means "tickets nobody has", which is the queue.
 *
 * @param from tickets created at or after this instant
 * @param to   tickets created before this instant
 * @param page zero-based
 * @param size at most {@link #MAX_SIZE}; larger is clamped, smaller than one becomes one
 */
public record TicketFilter(TicketState state, String owner, Instant from, Instant to, int page, int size) {

    public static final String UNASSIGNED = "-";
    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_SIZE = 25;

    public TicketFilter {
        page = Math.max(page, 0);
        size = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        owner = owner == null || owner.isBlank() ? null : owner.strip();
    }

    public static TicketFilter all() {
        return new TicketFilter(null, null, null, null, 0, DEFAULT_SIZE);
    }
}
