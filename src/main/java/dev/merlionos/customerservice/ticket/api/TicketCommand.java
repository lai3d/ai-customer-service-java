package dev.merlionos.customerservice.ticket.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One mutation on the wire between a {@code chat} process and a {@code ticket} process:
 * who is acting, the version they read, and the one extra field some actions take. The
 * action itself is the path, so a body is never ambiguous about what it asks for.
 *
 * @param assignee for {@code assign}
 * @param text     the note for {@code note}, the conclusion for {@code resolve}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TicketCommand(TicketActor actor, int expectedVersion, String assignee, String text) {

    public static TicketCommand of(TicketActor actor, int expectedVersion) {
        return new TicketCommand(actor, expectedVersion, null, null);
    }
}
