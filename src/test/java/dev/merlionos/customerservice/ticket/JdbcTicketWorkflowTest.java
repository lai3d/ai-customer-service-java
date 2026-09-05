package dev.merlionos.customerservice.ticket;

import dev.merlionos.customerservice.MigratedPostgres;
import dev.merlionos.customerservice.ticket.api.TicketActor;
import dev.merlionos.customerservice.ticket.api.TicketConflictException;
import dev.merlionos.customerservice.ticket.api.TicketEvent;
import dev.merlionos.customerservice.ticket.api.TicketFilter;
import dev.merlionos.customerservice.ticket.api.TicketNotFoundException;
import dev.merlionos.customerservice.ticket.api.TicketOperations;
import dev.merlionos.customerservice.ticket.api.TicketPage;
import dev.merlionos.customerservice.ticket.api.TicketRecord;
import dev.merlionos.customerservice.ticket.api.TicketRequest;
import dev.merlionos.customerservice.ticket.api.TicketRuleException;
import dev.merlionos.customerservice.ticket.api.TicketState;
import dev.merlionos.customerservice.ticket.api.TicketWorkflow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The state machine, ownership and history, against the rows. Tickets are created the way
 * the AI creates them, through {@link JdbcTicketOperations}, so the defaults V5 gives a
 * tool-created ticket are what is tested, not a fixture's idea of them. The claim race uses
 * two instances over one database, as {@link JdbcTicketOperationsTest} does: the lock is in
 * Postgres, not in either process.
 */
class JdbcTicketWorkflowTest {

    static final TicketActor ALICE = TicketActor.staff("alice");
    static final TicketActor BOB = TicketActor.staff("bob");
    static final TicketActor ROOT = TicketActor.admin("root");

    static MigratedPostgres postgres;
    static TicketOperations creator;
    static TicketWorkflow replicaA;
    static TicketWorkflow replicaB;

    @BeforeAll
    static void start() {
        postgres = MigratedPostgres.start();
        creator = new JdbcTicketOperations(postgres.jdbc, postgres.transactionManager);
        replicaA = new JdbcTicketWorkflow(postgres.jdbc, postgres.transactionManager);
        replicaB = new JdbcTicketWorkflow(postgres.jdbc, postgres.transactionManager);
    }

    @AfterAll
    static void stop() {
        postgres.close();
    }

    private static TicketRecord newTicket(String summary) {
        String number = creator.create(new TicketRequest(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), summary, "returns", null)).ticket().ticketNumber();
        return replicaA.find(number).orElseThrow();
    }

    private int events(String ticketNumber) {
        return postgres.jdbc.queryForObject("SELECT count(*) FROM ticket_event WHERE ticket_number = ?",
                Integer.class, ticketNumber);
    }

    @Test
    @DisplayName("a ticket the AI created is open, unowned, at version zero, and has no history")
    void toolCreatedTicketIsOpenAndUnowned() {
        TicketRecord ticket = newTicket("Parcel arrived crushed");

        assertThat(ticket.state()).isEqualTo(TicketState.OPEN);
        assertThat(ticket.owner()).isNull();
        assertThat(ticket.version()).isZero();
        assertThat(ticket.updatedAt()).isAfterOrEqualTo(ticket.createdAt());
        assertThat(replicaA.history(ticket.ticketNumber())).isEmpty();
        assertThat(replicaA.find("TKT-0")).isEmpty();
    }

    @Test
    @DisplayName("claim, resolve, close: each step is attributed, versioned, and in the history in order")
    void theHappyPath() {
        TicketRecord ticket = newTicket("Refund for a damaged lamp");
        String number = ticket.ticketNumber();

        TicketRecord claimed = replicaA.claim(number, ALICE, 0);
        assertThat(claimed.state()).isEqualTo(TicketState.CLAIMED);
        assertThat(claimed.owner()).isEqualTo("alice");
        assertThat(claimed.version()).isEqualTo(1);

        TicketRecord noted = replicaB.addNote(number, "Called the customer; photos requested.", ALICE, 1);
        assertThat(noted.state()).isEqualTo(TicketState.CLAIMED);
        assertThat(noted.version()).isEqualTo(2);

        TicketRecord resolved = replicaA.resolve(number, ALICE, 2);
        assertThat(resolved.state()).isEqualTo(TicketState.RESOLVED);
        assertThat(resolved.owner()).as("resolving keeps the owner; the record says who did the work").isEqualTo("alice");

        TicketRecord closed = replicaB.close(number, ALICE, 3);
        assertThat(closed.state()).isEqualTo(TicketState.CLOSED);
        assertThat(closed.version()).isEqualTo(4);

        List<TicketEvent> history = replicaA.history(number);
        assertThat(history).extracting(TicketEvent::kind).containsExactly(
                TicketEvent.Kind.CLAIMED, TicketEvent.Kind.NOTE, TicketEvent.Kind.RESOLVED, TicketEvent.Kind.CLOSED);
        assertThat(history).allSatisfy(event -> assertThat(event.actor()).isEqualTo("alice"));
        assertThat(history.getFirst().fromState()).isEqualTo(TicketState.OPEN);
        assertThat(history.getFirst().toState()).isEqualTo(TicketState.CLAIMED);
        assertThat(history.getFirst().fromOwner()).isNull();
        assertThat(history.getFirst().toOwner()).isEqualTo("alice");
        assertThat(history.get(1).note()).isEqualTo("Called the customer; photos requested.");
        assertThat(history.get(1).fromState()).as("a note is not a transition").isNull();
        assertThat(history).extracting(TicketEvent::occurredAt).isSorted();

        // The row agrees with the values that came back.
        assertThat(postgres.jdbc.queryForMap("SELECT state, owner, version FROM support_ticket WHERE ticket_number = ?", number))
                .containsEntry("state", "closed").containsEntry("owner", "alice").containsEntry("version", 4);
    }

    @Test
    @DisplayName("release puts a ticket back in the queue; reopen does too, from resolved or closed, and clears the owner")
    void releaseAndReopen() {
        TicketRecord ticket = newTicket("Wrong size delivered");
        String number = ticket.ticketNumber();

        replicaA.claim(number, ALICE, 0);
        TicketRecord released = replicaA.release(number, ALICE, 1);
        assertThat(released.state()).isEqualTo(TicketState.OPEN);
        assertThat(released.owner()).isNull();

        replicaA.claim(number, BOB, 2);
        replicaA.resolve(number, BOB, 3);
        TicketRecord reopened = replicaB.reopen(number, ALICE, 4);
        assertThat(reopened.state()).isEqualTo(TicketState.OPEN);
        assertThat(reopened.owner()).as("a reopened ticket is nobody's until claimed again").isNull();

        replicaA.claim(number, BOB, 5);
        replicaA.close(number, BOB, 6);
        TicketRecord reopenedFromClosed = replicaA.reopen(number, BOB, 7);
        assertThat(reopenedFromClosed.state()).isEqualTo(TicketState.OPEN);
        assertThat(replicaA.history(number)).extracting(TicketEvent::kind).containsExactly(
                TicketEvent.Kind.CLAIMED, TicketEvent.Kind.RELEASED, TicketEvent.Kind.CLAIMED, TicketEvent.Kind.RESOLVED,
                TicketEvent.Kind.REOPENED, TicketEvent.Kind.CLAIMED, TicketEvent.Kind.CLOSED, TicketEvent.Kind.REOPENED);
    }

    @Test
    @DisplayName("an admin assigns an open ticket; the owner may hand it on; another support member may not")
    void assignmentFollowsOwnershipAndOverride() {
        TicketRecord ticket = newTicket("Charged twice");
        String number = ticket.ticketNumber();

        TicketRecord assigned = replicaA.assign(number, "Alice", ROOT, 0);
        assertThat(assigned.state()).isEqualTo(TicketState.CLAIMED);
        assertThat(assigned.owner()).isEqualTo("alice");

        assertThatThrownBy(() -> replicaB.assign(number, "bob", BOB, 1))
                .isInstanceOf(TicketRuleException.class).hasMessageContaining("owned by alice");
        assertThatThrownBy(() -> replicaB.resolve(number, BOB, 1))
                .isInstanceOf(TicketRuleException.class).hasMessageContaining("owned by alice");
        assertThatThrownBy(() -> replicaB.release(number, BOB, 1))
                .isInstanceOf(TicketRuleException.class);
        assertThat(replicaA.find(number).orElseThrow().version()).as("a refused change writes nothing").isEqualTo(1);
        assertThat(events(number)).isEqualTo(1);

        TicketRecord handedOn = replicaA.assign(number, "bob", ALICE, 1);
        assertThat(handedOn.owner()).isEqualTo("bob");
        TicketRecord adminResolved = replicaB.resolve(number, ROOT, 2);
        assertThat(adminResolved.state()).isEqualTo(TicketState.RESOLVED);
        assertThat(adminResolved.owner()).isEqualTo("bob");

        List<TicketEvent> history = replicaA.history(number);
        assertThat(history.get(1).actor()).isEqualTo("alice");
        assertThat(history.get(1).fromOwner()).isEqualTo("alice");
        assertThat(history.get(1).toOwner()).isEqualTo("bob");
        assertThat(history.get(2).actor()).isEqualTo("root");
    }

    @Test
    @DisplayName("the state machine refuses what it does not allow, and writes nothing when it does")
    void illegalTransitionsAreRefused() {
        TicketRecord ticket = newTicket("Missing item");
        String number = ticket.ticketNumber();

        assertThatThrownBy(() -> replicaA.close(number, ALICE, 0))
                .isInstanceOf(TicketRuleException.class).hasMessageContaining("is open and cannot be closed");
        assertThatThrownBy(() -> replicaA.resolve(number, ALICE, 0)).isInstanceOf(TicketRuleException.class);
        assertThatThrownBy(() -> replicaA.release(number, ALICE, 0)).isInstanceOf(TicketRuleException.class);
        assertThatThrownBy(() -> replicaA.reopen(number, ALICE, 0)).isInstanceOf(TicketRuleException.class);
        assertThatThrownBy(() -> replicaA.assign(number, " ", ROOT, 0)).isInstanceOf(TicketRuleException.class);
        assertThatThrownBy(() -> replicaA.addNote(number, "  ", ALICE, 0)).isInstanceOf(TicketRuleException.class);

        replicaA.claim(number, ALICE, 0);
        assertThatThrownBy(() -> replicaA.claim(number, ALICE, 1))
                .isInstanceOf(TicketRuleException.class).hasMessageContaining("is claimed and cannot be claimed");
        assertThatThrownBy(() -> replicaA.assign(number, "alice", ROOT, 1))
                .isInstanceOf(TicketRuleException.class).hasMessageContaining("already owned by alice");

        assertThatThrownBy(() -> replicaA.claim("TKT-0", ALICE, 0)).isInstanceOf(TicketNotFoundException.class);

        assertThat(replicaA.find(number).orElseThrow().version()).isEqualTo(1);
        assertThat(events(number)).isEqualTo(1);
    }

    @Test
    @DisplayName("a stale version is a conflict, not a change: two people on one stale page cannot both win, and a double submit lands once")
    void staleVersionIsAConflict() {
        TicketRecord ticket = newTicket("Late delivery");
        String number = ticket.ticketNumber();

        replicaA.claim(number, ALICE, 0);
        assertThatThrownBy(() -> replicaB.claim(number, BOB, 0))
                .isInstanceOf(TicketConflictException.class).hasMessageContaining("changed since it was read");
        assertThatThrownBy(() -> replicaA.addNote(number, "again", ALICE, 0))
                .as("the same request, resubmitted, carries the version the first copy moved past")
                .isInstanceOf(TicketConflictException.class);

        assertThat(replicaA.find(number).orElseThrow()).satisfies(current -> {
            assertThat(current.owner()).isEqualTo("alice");
            assertThat(current.version()).isEqualTo(1);
        });
        assertThat(events(number)).isEqualTo(1);
    }

    @Test
    @DisplayName("ten people claim one open ticket across two replicas at once: one owner, one event, nine conflicts")
    void claimIsAtomicAcrossReplicas() throws Exception {
        TicketRecord ticket = newTicket("Everyone wants this one");
        String number = ticket.ticketNumber();

        List<Outcome> outcomes = race(10, i -> {
            TicketWorkflow replica = i % 2 == 0 ? replicaA : replicaB;
            try {
                return new Outcome(replica.claim(number, TicketActor.staff("agent" + i), 0), null);
            }
            catch (RuntimeException e) {
                return new Outcome(null, e);
            }
        });

        List<Outcome> won = outcomes.stream().filter(o -> o.ticket() != null).toList();
        assertThat(won).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o.failure() != null))
                .hasSize(9)
                .allSatisfy(o -> assertThat(o.failure()).isInstanceOf(TicketConflictException.class));
        TicketRecord current = replicaA.find(number).orElseThrow();
        assertThat(current.owner()).isEqualTo(won.getFirst().ticket().owner());
        assertThat(current.version()).isEqualTo(1);
        assertThat(events(number)).isEqualTo(1);
    }

    @Test
    @DisplayName("the list filters by state, owner and time, pages with a total, and puts the most recently changed first")
    void searchFiltersAndPages() throws Exception {
        String stamp = UUID.randomUUID().toString().substring(0, 8);
        Instant before = Instant.now();
        TicketRecord first = newTicket(stamp + " first");
        TicketRecord second = newTicket(stamp + " second");
        TicketRecord third = newTicket(stamp + " third");
        replicaA.claim(second.ticketNumber(), ALICE, 0);
        replicaA.claim(third.ticketNumber(), BOB, 0);
        replicaA.resolve(third.ticketNumber(), BOB, 1);
        Thread.sleep(5);
        replicaA.addNote(first.ticketNumber(), "bumped", ROOT, 0);
        Instant after = Instant.now();

        TicketPage inWindow = replicaA.search(new TicketFilter(null, null, before, after, 0, 2));
        assertThat(inWindow.total()).isEqualTo(3);
        assertThat(inWindow.tickets()).extracting(TicketRecord::ticketNumber)
                .as("most recently changed first, then the page boundary")
                .containsExactly(first.ticketNumber(), third.ticketNumber());
        TicketPage secondPage = replicaA.search(new TicketFilter(null, null, before, after, 1, 2));
        assertThat(secondPage.tickets()).extracting(TicketRecord::ticketNumber).containsExactly(second.ticketNumber());

        assertThat(replicaA.search(new TicketFilter(TicketState.CLAIMED, null, before, after, 0, 10)).tickets())
                .extracting(TicketRecord::ticketNumber).containsExactly(second.ticketNumber());
        assertThat(replicaA.search(new TicketFilter(null, "Alice", before, after, 0, 10)).tickets())
                .extracting(TicketRecord::ticketNumber).containsExactly(second.ticketNumber());
        assertThat(replicaA.search(new TicketFilter(null, TicketFilter.UNASSIGNED, before, after, 0, 10)).tickets())
                .extracting(TicketRecord::ticketNumber).containsExactly(first.ticketNumber());
        assertThat(replicaA.search(new TicketFilter(TicketState.RESOLVED, "bob", before, after, 0, 10)).total()).isEqualTo(1);
        assertThat(replicaA.search(new TicketFilter(null, null, after, null, 0, 10)).total()).isZero();

        TicketFilter clamped = new TicketFilter(null, null, null, null, -3, 1000);
        assertThat(clamped.page()).isZero();
        assertThat(clamped.size()).isEqualTo(TicketFilter.MAX_SIZE);
    }

    private record Outcome(TicketRecord ticket, RuntimeException failure) {
    }

    private interface Attempt {
        Outcome run(int index);
    }

    /** Starts every attempt on the same latch so they contend rather than queue by accident. */
    private static List<Outcome> race(int attempts, Attempt attempt) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            for (int i = 0; i < attempts; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    go.await();
                    return attempt.run(index);
                }));
            }
            go.countDown();
            List<Outcome> results = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
