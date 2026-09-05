package dev.merlionos.customerservice.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One active turn per conversation, across replicas.
 *
 * <p>Isolating SSE channels by turn ({@link TurnEventBus}) stopped two overlapping turns on one
 * conversation from cross-wiring each other's events. It did nothing about their histories:
 * both write a user message up front and an assistant message at the end, so the conversation
 * ends up as user, user, assistant, assistant and the next request sends that to the model.
 * Two API clients sharing a conversation id is enough to cause it.
 *
 * <p>So admission takes a lease. A second request while the first is in flight gets a
 * {@code 409}, on both endpoints, before anything is written. The lease is a row with an
 * expiry rather than a lock, because a replica that dies mid-turn must not hold its
 * conversation forever; it expires after {@code app.chat.turn-lease}, which is longer than the
 * HTTP read timeout, so by the time a lease can be taken over the turn holding it has already
 * failed. What is deliberately not here is fencing on history writes by a turn whose lease has
 * expired -- ADR 001 defers it until an expiry is seen in practice.
 */
@Component
public class ConversationLease {

    private final JdbcTemplate jdbc;
    private final Duration ttl;

    ConversationLease(JdbcTemplate jdbc, ChatProperties properties) {
        this.jdbc = jdbc;
        this.ttl = properties.turnLease() == null ? Duration.ofSeconds(150) : properties.turnLease();
    }

    /**
     * @throws ConversationBusyException if another turn holds an unexpired lease
     */
    public void acquire(String conversationId, String turnId) {
        Instant now = Instant.now();
        // Insert, or take over an expired lease, in one statement: two replicas admitting the
        // same conversation at once both hit the primary key and exactly one row wins.
        List<String> holder = jdbc.query("""
                INSERT INTO conversation_lease (conversation_id, turn_id, expires_at)
                VALUES (?, ?, ?)
                ON CONFLICT (conversation_id) DO UPDATE
                    SET turn_id = EXCLUDED.turn_id, expires_at = EXCLUDED.expires_at
                    WHERE conversation_lease.expires_at < ?
                RETURNING turn_id
                """, (rs, i) -> rs.getString(1),
                conversationId, turnId, Timestamp.from(now.plus(ttl)), Timestamp.from(now));
        if (holder.isEmpty()) {
            throw new ConversationBusyException(conversationId);
        }
    }

    /** Releases only if this turn still holds it; a lease taken over after expiry is left alone. */
    public void release(String conversationId, String turnId) {
        jdbc.update("DELETE FROM conversation_lease WHERE conversation_id = ? AND turn_id = ?",
                conversationId, turnId);
    }

    Duration ttl() {
        return ttl;
    }
}
