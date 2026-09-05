package dev.merlionos.customerservice.config;

import dev.merlionos.customerservice.PostgresTestcontainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lock exists because {@code CREATE ... IF NOT EXISTS} is not concurrency-safe in Postgres,
 * which is a claim about the database rather than about this code -- so these tests make the
 * database say it, rather than restating it.
 *
 * <p>Including the claim that the bug is real. Running the DDL from several threads and hoping
 * to catch a failure is timing-dependent, and an intermittently red test is worse than an
 * absent one -- but the race can be made deterministic, because what makes it possible is
 * transaction visibility rather than luck. An uncommitted {@code CREATE EXTENSION} is invisible
 * to another session, so its {@code IF NOT EXISTS} finds nothing, proceeds, and collides on the
 * catalogue's unique index. That is {@link #theRaceIsRealAndDeterministic()}, and it is the
 * only test here that would still pass if the lock were deleted -- which is the point of it.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class SchemaInitializationLockTest {

    @Autowired DataSource dataSource;
    @Autowired VectorStore vectorStore;
    @Autowired PostgreSQLContainer<?> postgres;

    /**
     * A physical connection, not one from the pool. Every property this class checks is a
     * property of a Postgres <em>session</em>, and a pooled {@code close()} ends no session:
     * it hands the same one back for reuse, advisory locks included.
     *
     * <p>The first version of this test used the pool and passed while proving the opposite.
     * {@code aDroppedConnectionReleasesTheLock} got its own connection back on the next
     * borrow, where the lock is re-entrant, so {@code pg_try_advisory_lock} returned true
     * without Postgres having released anything -- and the leaked lock then blocked
     * {@code holdersAreSerialised} for 1,479 seconds until Hikari retired the connection at
     * {@code maxLifetime}. Both tests were green. One took twenty-five minutes.
     */
    private Connection session() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** A session on a named database, for the one test that needs a scratch one. */
    private Connection session(String database) throws Exception {
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    @Test
    @DisplayName("the bean that creates the vector schema is still recognised by name")
    void theVectorStoreIsStillRecognised() {
        // The lock matches its targets by class name, so a rename in Spring AI would stop it
        // silently: no error, no log, just the race back. This test is the thing that turns
        // that into a build failure -- it is the whole reason the matching is testable at all.
        assertThat(recognises(vectorStore))
                .as("SchemaInitializationLock no longer recognises %s. If Spring AI moved or "
                    + "renamed it, update SCHEMA_INITIALISING_BEANS -- the lock is a no-op until "
                    + "you do, and nothing else will say so.", vectorStore.getClass().getName())
                .isTrue();
    }

    @Test
    @DisplayName("a bean that creates no schema is left alone")
    void anUnrelatedBeanIsNotLocked() {
        assertThat(recognises("just a string")).isFalse();
    }

    @Test
    @DisplayName("the race this lock exists for is real, shown without relying on timing")
    void theRaceIsRealAndDeterministic() throws Exception {
        // Its own database. `public` belongs to the shared application context -- dropping it
        // here would delete vector_store and spring_ai_chat_memory out from under whichever
        // test class the context cache hands it to next, and the damage would surface as an
        // unrelated failure somewhere else. Extensions are per-database, so a scratch one
        // isolates this completely.
        try (Connection admin = session(); Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS race_probe");
            statement.execute("CREATE DATABASE race_probe");
        }

        try (Connection a = session("race_probe"); Connection b = session("race_probe")) {

            // A creates the extension but does not commit. Nothing else can see the catalogue
            // row yet -- which is exactly the state two replicas are in when they both look
            // and both find nothing.
            a.setAutoCommit(false);
            try (Statement create = a.createStatement()) {
                create.execute("CREATE EXTENSION vector");
            }

            // B's IF NOT EXISTS therefore finds nothing and proceeds. It then blocks on the
            // unique index rather than failing immediately, so it has to run on its own thread
            // and be woken by A's commit.
            var loser = Executors.newSingleThreadExecutor();
            try {
                Future<String> outcome = loser.submit(() -> {
                    try (Statement create = b.createStatement()) {
                        create.execute("CREATE EXTENSION IF NOT EXISTS vector");
                        return "no error";
                    }
                    catch (Exception e) {
                        return e.getMessage();
                    }
                });
                Thread.sleep(250);
                a.commit();

                assertThat(outcome.get())
                        .as("CREATE EXTENSION IF NOT EXISTS is supposed to be unsafe under "
                            + "concurrency; if this now succeeds, Postgres changed and the "
                            + "lock may no longer be needed")
                        .contains("pg_extension_name_index");
            }
            finally {
                loser.shutdownNow();
                a.setAutoCommit(true);
            }
        }
        finally {
            try (Connection admin = session(); Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS race_probe");
            }
        }
    }

    @Test
    @DisplayName("only one replica holds the lock at a time")
    void holdersAreSerialised() throws Exception {
        int threads = 8;
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();

        runConcurrently(threads, () -> {
            try (Connection connection = session()) {
                advisory(connection, "pg_advisory_lock");
                mostAtOnce.accumulateAndGet(inside.incrementAndGet(), Math::max);
                Thread.sleep(20);
                inside.decrementAndGet();
                advisory(connection, "pg_advisory_unlock");
            }
            return null;
        });

        assertThat(mostAtOnce.get())
                .as("the advisory lock let %d sessions into the schema window at once", mostAtOnce.get())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the racy DDL run concurrently under the lock does not fail")
    void concurrentSchemaCreationSucceeds() throws Exception {
        // The exact statements PgVectorStore.afterPropertiesSet issues, minus the parts that
        // depend on its configuration. Unlocked and concurrent, this is what produced
        // `duplicate key value violates unique constraint "pg_extension_name_index"` on a
        // two-replica Kubernetes rollout.
        List<String> ddl = List.of(
                "CREATE EXTENSION IF NOT EXISTS vector",
                "CREATE EXTENSION IF NOT EXISTS hstore",
                "CREATE TABLE IF NOT EXISTS lock_test_store (id text PRIMARY KEY, embedding vector(3))",
                "CREATE INDEX IF NOT EXISTS lock_test_store_idx ON lock_test_store (id)");

        List<Object> outcomes = runConcurrently(8, () -> {
            try (Connection connection = session()) {
                advisory(connection, "pg_advisory_lock");
                try (Statement statement = connection.createStatement()) {
                    for (String sql : ddl) {
                        statement.execute(sql);
                    }
                }
                advisory(connection, "pg_advisory_unlock");
            }
            return null;
        });

        assertThat(outcomes).hasSize(8);
    }

    @Test
    @DisplayName("a session that dies mid-schema releases the lock instead of wedging the others")
    void aDroppedConnectionReleasesTheLock() throws Exception {
        // The safety property the class documents. A replica OOM-killed while holding this
        // lock must not stop every other replica from ever starting, and the reason it does
        // not is a guarantee of Postgres rather than of any code here -- so it is verified
        // here rather than asserted in a comment.
        Connection dying = session();
        advisory(dying, "pg_advisory_lock");

        // The negative half. Without it this test can only ever pass: "another session can
        // take the lock" is also what you see if the lock was never held. Held first, then
        // released by the disconnect -- both directions, or it proves nothing.
        assertThat(tryLock()).as("another session took a lock that was supposed to be held").isFalse();

        dying.close();

        try (Connection next = session();
             PreparedStatement tryLock = next.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            tryLock.setLong(1, SchemaInitializationLock.LOCK_KEY);
            try (ResultSet rs = tryLock.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1))
                        .as("the lock survived the session that held it")
                        .isTrue();
            }
            advisory(next, "pg_advisory_unlock");
        }
    }

    private boolean tryLock() throws Exception {
        try (Connection probe = session();
             PreparedStatement statement = probe.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, SchemaInitializationLock.LOCK_KEY);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                boolean taken = rs.getBoolean(1);
                if (taken) {
                    advisory(probe, "pg_advisory_unlock");
                }
                return taken;
            }
        }
    }

    private static void advisory(Connection connection, String function) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT " + function + "(?)")) {
            statement.setLong(1, SchemaInitializationLock.LOCK_KEY);
            statement.execute();
        }
    }

    private static List<Object> runConcurrently(int threads, Callable<Object> task) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Object>> futures = pool.invokeAll(java.util.Collections.nCopies(threads,
                    (Callable<Object>) () -> {
                        startTogether.await();
                        return task.call();
                    }));
            // .get() rethrows whatever a worker threw, which is the assertion: none of them
            // may have failed.
            return futures.stream().map(f -> {
                try {
                    return f.get();
                }
                catch (Exception e) {
                    throw new AssertionError("a concurrent schema creation failed", e);
                }
            }).toList();
        }
    }

    private boolean recognises(Object bean) {
        try {
            var method = SchemaInitializationLock.class
                    .getDeclaredMethod("initialisesSchema", Object.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, bean);
        }
        catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
