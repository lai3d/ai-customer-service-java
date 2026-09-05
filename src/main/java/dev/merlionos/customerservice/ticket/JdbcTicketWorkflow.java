package dev.merlionos.customerservice.ticket;

import dev.merlionos.customerservice.ticket.api.TicketActor;
import dev.merlionos.customerservice.ticket.api.TicketConflictException;
import dev.merlionos.customerservice.ticket.api.TicketEvent;
import dev.merlionos.customerservice.ticket.api.TicketFilter;
import dev.merlionos.customerservice.ticket.api.TicketNotFoundException;
import dev.merlionos.customerservice.ticket.api.TicketPage;
import dev.merlionos.customerservice.ticket.api.TicketRecord;
import dev.merlionos.customerservice.ticket.api.TicketRuleException;
import dev.merlionos.customerservice.ticket.api.TicketState;
import dev.merlionos.customerservice.ticket.api.TicketWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link TicketWorkflow} over the {@code support_ticket} and {@code ticket_event} tables.
 *
 * <h2>Why one shape for every change</h2>
 *
 * <p>Every mutation is the same transaction: lock the ticket's row, compare the version the
 * caller read with the one in the row, decide the new state and owner from the current row,
 * write the row and the event, read the row back. The row lock is what makes the claim
 * atomic across replicas -- the second claimer waits on the first's lock, then reads a row
 * whose version has moved and stops -- and the version check is what makes a stale page and a
 * double-submitted form harmless. The rules live in small functions of the current row so
 * the transaction skeleton is written once and the state machine is readable in one place.
 *
 * <p>A rule violation and a lost race are different exceptions on purpose: reloading fixes
 * one and not the other, and the page should say which.
 */
public class JdbcTicketWorkflow implements TicketWorkflow {

    private static final Logger log = LoggerFactory.getLogger(JdbcTicketWorkflow.class);

    private static final String COLUMNS = "ticket_number, conversation_id, category, summary, order_number, "
            + "state, owner, created_at, updated_at, version";

    private static final RowMapper<TicketRecord> RECORD = (rs, i) -> new TicketRecord(
            rs.getString("ticket_number"), rs.getString("conversation_id"), rs.getString("category"),
            rs.getString("summary"), rs.getString("order_number"), TicketState.fromValue(rs.getString("state")),
            rs.getString("owner"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(), rs.getInt("version"));

    private static final RowMapper<TicketEvent> EVENT = (rs, i) -> new TicketEvent(
            rs.getLong("id"), rs.getString("ticket_number"), TicketEvent.Kind.fromValue(rs.getString("kind")),
            rs.getString("actor"), state(rs.getString("from_state")), state(rs.getString("to_state")),
            rs.getString("from_owner"), rs.getString("to_owner"), rs.getString("note"),
            rs.getTimestamp("occurred_at").toInstant());

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcTicketWorkflow(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // --- reads -------------------------------------------------------------------------------

    @Override
    public Optional<TicketRecord> find(String ticketNumber) {
        return jdbc.query("SELECT " + COLUMNS + " FROM support_ticket WHERE ticket_number = ?", RECORD, ticketNumber)
                .stream().findFirst();
    }

    @Override
    public TicketPage search(TicketFilter filter) {
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (filter.state() != null) {
            where.add("state = ?");
            args.add(filter.state().value());
        }
        if (TicketFilter.UNASSIGNED.equals(filter.owner())) {
            where.add("owner IS NULL");
        }
        else if (filter.owner() != null) {
            where.add("owner = ?");
            args.add(filter.owner().toLowerCase(Locale.ROOT));
        }
        if (filter.from() != null) {
            where.add("created_at >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            where.add("created_at < ?");
            args.add(Timestamp.from(filter.to()));
        }
        String clause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);

        long total = jdbc.queryForObject("SELECT count(*) FROM support_ticket" + clause, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(filter.size());
        pageArgs.add((long) filter.page() * filter.size());
        List<TicketRecord> tickets = jdbc.query("SELECT " + COLUMNS + " FROM support_ticket" + clause
                        + " ORDER BY updated_at DESC, ticket_number DESC LIMIT ? OFFSET ?",
                RECORD, pageArgs.toArray());
        return new TicketPage(tickets, total, filter.page(), filter.size());
    }

    @Override
    public List<TicketEvent> history(String ticketNumber) {
        return jdbc.query("SELECT * FROM ticket_event WHERE ticket_number = ? ORDER BY id", EVENT, ticketNumber);
    }

    // --- the state machine ------------------------------------------------------------------

    /** What a change decides: the ticket's next state and owner. */
    private record Target(TicketState state, String owner) {
    }

    @Override
    public TicketRecord claim(String ticketNumber, TicketActor actor, int expectedVersion) {
        return change(ticketNumber, actor, expectedVersion, TicketEvent.Kind.CLAIMED, null, current -> {
            requireState(current, EnumSet.of(TicketState.OPEN), "claimed");
            if (current.owner() != null) {
                throw new TicketRuleException(ticketNumber, "is already owned by " + current.owner());
            }
            return new Target(TicketState.CLAIMED, actor.username());
        });
    }

    @Override
    public TicketRecord assign(String ticketNumber, String assignee, TicketActor actor, int expectedVersion) {
        String owner = assignee == null ? "" : assignee.strip().toLowerCase(Locale.ROOT);
        if (owner.isEmpty()) {
            throw new TicketRuleException(ticketNumber, "cannot be assigned to nobody; release it instead");
        }
        return change(ticketNumber, actor, expectedVersion, TicketEvent.Kind.ASSIGNED, null, current -> {
            requireState(current, EnumSet.of(TicketState.OPEN, TicketState.CLAIMED), "assigned");
            requireOwnerOrOverride(current, actor);
            if (owner.equals(current.owner())) {
                throw new TicketRuleException(ticketNumber, "is already owned by " + owner);
            }
            return new Target(TicketState.CLAIMED, owner);
        });
    }

    @Override
    public TicketRecord release(String ticketNumber, TicketActor actor, int expectedVersion) {
        return change(ticketNumber, actor, expectedVersion, TicketEvent.Kind.RELEASED, null, current -> {
            requireState(current, EnumSet.of(TicketState.CLAIMED), "released");
            requireOwnerOrOverride(current, actor);
            return new Target(TicketState.OPEN, null);
        });
    }

    @Override
    public TicketRecord resolve(String ticketNumber, TicketActor actor, int expectedVersion) {
        return change(ticketNumber, actor, expectedVersion, TicketEvent.Kind.RESOLVED, null, current -> {
            requireState(current, EnumSet.of(TicketState.CLAIMED), "resolved");
            requireOwnerOrOverride(current, actor);
            return new Target(TicketState.RESOLVED, current.owner());
        });
    }

    @Override
    public TicketRecord close(String ticketNumber, TicketActor actor, int expectedVersion) {
        return change(ticketNumber, actor, expectedVersion, TicketEvent.Kind.CLOSED, null, current -> {
            requireState(current, EnumSet.of(TicketState.CLAIMED, TicketState.RESOLVED), "closed");
            requireOwnerOrOverride(current, actor);
            return new Target(TicketState.CLOSED, current.owner());
        });
    }

    @Override
    public TicketRecord reopen(String ticketNumber, TicketActor actor, int expectedVersion) {
        return change(ticketNumber, actor, expectedVersion, TicketEvent.Kind.REOPENED, null, current -> {
            requireState(current, EnumSet.of(TicketState.RESOLVED, TicketState.CLOSED), "reopened");
            return new Target(TicketState.OPEN, null);
        });
    }

    @Override
    public TicketRecord addNote(String ticketNumber, String note, TicketActor actor, int expectedVersion) {
        String text = note == null ? "" : note.strip();
        if (text.isEmpty()) {
            throw new TicketRuleException(ticketNumber, "cannot take an empty note");
        }
        return change(ticketNumber, actor, expectedVersion, TicketEvent.Kind.NOTE, text,
                current -> new Target(current.state(), current.owner()));
    }

    private static void requireState(TicketRecord current, Set<TicketState> allowed, String verb) {
        if (!allowed.contains(current.state())) {
            throw new TicketRuleException(current.ticketNumber(),
                    "is " + current.state().value() + " and cannot be " + verb);
        }
    }

    private static void requireOwnerOrOverride(TicketRecord current, TicketActor actor) {
        if (current.owner() != null && !current.owner().equals(actor.username()) && !actor.override()) {
            throw new TicketRuleException(current.ticketNumber(), "is owned by " + current.owner()
                    + "; only the owner or an admin can change it");
        }
    }

    // --- the one transaction every change is ----------------------------------------------

    private TicketRecord change(String ticketNumber, TicketActor actor, int expectedVersion,
                                TicketEvent.Kind kind, String note, Function<TicketRecord, Target> decide) {
        TicketRecord changed = transaction.execute(status -> {
            TicketRecord current = jdbc.query(
                            "SELECT " + COLUMNS + " FROM support_ticket WHERE ticket_number = ? FOR UPDATE",
                            RECORD, ticketNumber)
                    .stream().findFirst().orElseThrow(() -> new TicketNotFoundException(ticketNumber));
            if (current.version() != expectedVersion) {
                throw new TicketConflictException(ticketNumber, "changed since it was read (version "
                        + current.version() + ", not " + expectedVersion + "); reload and look again");
            }
            Target target = decide.apply(current);
            Instant now = Instant.now();
            int updated = jdbc.update(
                    "UPDATE support_ticket SET state = ?, owner = ?, updated_at = ?, version = version + 1 "
                            + "WHERE ticket_number = ? AND version = ?",
                    target.state().value(), target.owner(), Timestamp.from(now), ticketNumber, expectedVersion);
            if (updated != 1) {
                // Unreachable under the row lock above; kept so a future change to the read
                // cannot silently turn one change into two.
                throw new TicketConflictException(ticketNumber, "changed while being changed");
            }
            boolean transition = kind != TicketEvent.Kind.NOTE;
            jdbc.update("""
                    INSERT INTO ticket_event
                        (ticket_number, kind, actor, from_state, to_state, from_owner, to_owner, note, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, ticketNumber, kind.value(), actor.username(),
                    transition ? current.state().value() : null, transition ? target.state().value() : null,
                    transition ? current.owner() : null, transition ? target.owner() : null,
                    note, Timestamp.from(now));
            return new TicketRecord(current.ticketNumber(), current.conversationId(), current.category(),
                    current.summary(), current.orderNumber(), target.state(), target.owner(),
                    current.createdAt(), now, current.version() + 1);
        });
        log.info("Ticket {} {} by {}: now {}{}", ticketNumber, kind.value(), actor.username(),
                changed.state().value(), changed.owner() == null ? "" : ", owned by " + changed.owner());
        return changed;
    }

    private static TicketState state(String value) {
        return value == null ? null : TicketState.fromValue(value);
    }
}
