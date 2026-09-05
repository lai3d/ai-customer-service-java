package dev.merlionos.customerservice.ticket.api;

/**
 * Who is acting on a ticket. The workflow knows nothing about staff accounts or roles; it
 * knows an actor's name and whether that actor may act on tickets other people own. The
 * admin side decides the second from the role, which is where that knowledge lives.
 *
 * @param username the staff username, recorded on every event
 * @param override whether the actor may release, resolve, close or reassign a ticket owned
 *                 by someone else
 */
public record TicketActor(String username, boolean override) {

    public TicketActor {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("an actor needs a username");
        }
    }

    public static TicketActor staff(String username) {
        return new TicketActor(username, false);
    }

    public static TicketActor admin(String username) {
        return new TicketActor(username, true);
    }
}
