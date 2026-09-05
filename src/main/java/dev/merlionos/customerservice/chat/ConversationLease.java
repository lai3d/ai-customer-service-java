package dev.merlionos.customerservice.chat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final Counter conflicts;

    ConversationLease(JdbcTemplate jdbc, ChatProperties properties, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.ttl = properties.turnLease() == null ? Duration.ofSeconds(150) : properties.turnLease();
        // The refusal is also a 409 on the request timer, but that series appears with the
        // first refusal and reads as nothing happening until then. Registered at zero here so
        // the dashboard shows 0, and so the count is the lease's own rather than inferred from
        // a status code that other things could one day return.
        this.conflicts = Counter.builder("chat.lease.conflicts")
                .description("Turns refused because another turn held the conversation's lease")
                .register(meterRegistry);
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
            conflicts.increment();
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
