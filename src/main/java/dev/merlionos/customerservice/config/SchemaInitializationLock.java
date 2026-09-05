package dev.merlionos.customerservice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

/**
 * Serialises startup schema creation across replicas with a Postgres advisory lock.
 *
 * <p>Every schema statement this application's dependencies run is written {@code IF NOT
 * EXISTS}, which reads as concurrency-safe and is not. In Postgres those statements check for
 * the object and then insert a catalogue row, and nothing holds the gap between the two: two
 * replicas starting together both see "absent" and the loser gets
 * {@code duplicate key value violates unique constraint "pg_extension_name_index"}. Its Spring
 * context fails, the pod restarts, and the retry succeeds because by then the object exists --
 * so the symptom is not a broken deployment but a slower one, with a CrashLoopBackOff-shaped
 * event on every rollout against a fresh database.
 *
 * <p>It was found by running the Kubernetes manifests on a real cluster: two replicas, cold
 * database, exactly one loser, every time. Nothing else in this repository could have found
 * it. The Compose stack runs one instance and the Testcontainers suite runs one context, so
 * both have a concurrency of one, and a bug that needs two starters is invisible to them.
 *
 * <p>{@code PgVectorStore.afterPropertiesSet} issues three {@code CREATE EXTENSION}, a
 * {@code CREATE SCHEMA}, a {@code CREATE TABLE} and a {@code CREATE INDEX};
 * {@code JdbcChatMemoryRepositorySchemaInitializer} runs a script with a
 * {@code CREATE TABLE IF NOT EXISTS} and a {@code CREATE INDEX IF NOT EXISTS}. Only the
 * extension was ever observed failing, because the loser dies there and never reaches the rest
 * -- the table and index are the same bug one statement later. Both beans are covered rather
 * than only the one with a reproduction.
 *
 * <p>A {@link BeanPostProcessor} is the seam because the DDL runs inside those beans'
 * initialisation, which this application does not own and should not reimplement. The lock is
 * taken before initialisation and released after, so replicas take turns through exactly the
 * window that races and run the rest of their startup -- the ONNX session, the corpus ingest --
 * in parallel, as they should.
 *
 * <p>The lock is held on one connection borrowed from the application's pool, and the beans
 * it protects borrow their own from the same pool while it is held. With
 * {@code spring.datasource.hikari.maximum-pool-size} at its configured 20 that is
 * unremarkable; at 1 it would deadlock on startup. Worth knowing before anyone shrinks the
 * pool to save memory on a small deployment. The borrowed connection is evicted afterwards,
 * so the pool re-opens one; that is a single reconnect per process start.
 *
 * <p><strong>A crashed replica cannot wedge the others.</strong> Session advisory locks are
 * released by Postgres when the connection ends, so a pod that is OOM-killed mid-DDL releases
 * on the way out. That is the property that makes this safe to hold across another bean's
 * initialisation; a lock table would need a lease and a janitor to match it.
 *
 * <p>{@code app.schema.serialize-initialization=false} removes it, for a deployment that
 * provisions the schema out of band and would rather not have every replica take a lock it
 * does not need. It also exists so the Kubernetes harness can prove its own check: an
 * assertion that has only ever been seen green is a claim, and turning this off is how
 * {@code k8s/kind/verify.sh} is shown to go red.
 *
 * <p><strong>The lock connection is evicted rather than returned to the pool.</strong>
 * {@code Connection.close()} on a pooled connection ends nothing -- it hands the same session
 * back for reuse, advisory locks and all. That distinction is not academic: a test here took
 * a lock on a pooled connection, closed it, and blocked the next test for twenty-five minutes
 * until Hikari retired the connection at {@code maxLifetime} and the session finally ended.
 * Evicting makes "closed" mean "disconnected", which is what the rest of this class's
 * reasoning assumes, and it costs one physical connection per startup.
 */
@Component
@ConditionalOnProperty(name = "app.schema.serialize-initialization", matchIfMissing = true)
class SchemaInitializationLock implements BeanPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializationLock.class);

    /**
     * Any 64-bit constant works as long as every replica uses the same one. Advisory locks
     * share one namespace per database, so this is also a claim on that number for anything
     * else pointed at the same database -- picked to be recognisable in
     * {@code pg_locks.objid} rather than to look random.
     */
    static final long LOCK_KEY = 8_524_101_020_260_905L;

    /**
     * Bean types whose initialisation issues schema DDL. Matched by class name because these
     * are the dependencies' classes: importing them to write {@code instanceof} would pull
     * two autoconfiguration modules into this file's compile-time surface for no benefit, and
     * one of them is not on the classpath in every profile.
     */
    private static final String[] SCHEMA_INITIALISING_BEANS = {
            "org.springframework.ai.vectorstore.pgvector.PgVectorStore",
            "org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure"
                    + ".JdbcChatMemoryRepositorySchemaInitializer",
    };

    private final ObjectProvider<DataSource> dataSource;

    /** Held for as long as the lock is: an advisory lock belongs to a session, not a query. */
    private Connection connection;
    private int depth;

    SchemaInitializationLock(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Runs before the property-binding post-processors so nothing initialises schema behind
     * this one's back.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (initialisesSchema(bean)) {
            acquire(beanName);
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (initialisesSchema(bean)) {
            release(beanName);
        }
        return bean;
    }

    private static boolean initialisesSchema(Object bean) {
        for (Class<?> type = bean.getClass(); type != null; type = type.getSuperclass()) {
            for (String name : SCHEMA_INITIALISING_BEANS) {
                if (type.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private synchronized void acquire(String beanName) {
        // Re-entrant on purpose: pg_advisory_lock stacks per session, and one release per
        // acquire is what unlocks it. Counting here keeps a single connection open across
        // nested initialisations instead of opening one per bean.
        if (depth++ > 0) {
            return;
        }
        Instant started = Instant.now();
        try {
            connection = dataSource.getObject().getConnection();
            try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
                lock.setLong(1, LOCK_KEY);
                lock.execute();
            }
        }
        catch (SQLException e) {
            depth = 0;
            discard();
            // Failing loudly is right. Starting anyway would restore the race this exists to
            // remove, and would do it silently.
            throw new IllegalStateException(
                    "Could not take the schema initialization lock before creating " + beanName, e);
        }
        Duration waited = Duration.between(started, Instant.now());
        if (waited.toMillis() > 100) {
            log.info("Waited {} ms for another replica to finish creating the schema", waited.toMillis());
        }
    }

    private synchronized void release(String beanName) {
        if (depth == 0 || --depth > 0) {
            return;
        }
        try (PreparedStatement unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            unlock.setLong(1, LOCK_KEY);
            unlock.execute();
        }
        catch (SQLException e) {
            // Not fatal: the schema is created, and the eviction below ends the session,
            // which releases the lock anyway. Worth a line because a lock that needed the
            // session to drop is a lock that was held longer than intended.
            log.warn("Could not release the schema initialization lock after creating {}; "
                     + "ending the session releases it", beanName, e);
        }
        finally {
            discard();
        }
    }

    /**
     * Ends the session rather than returning it to the pool, so that "closed" means
     * "disconnected" and the advisory lock is gone either way.
     */
    private void discard() {
        if (connection == null) {
            return;
        }
        try {
            DataSource source = dataSource.getObject();
            if (source instanceof HikariDataSource hikari) {
                hikari.evictConnection(connection);
            }
            else {
                // Some other pool, or a plain DataSource. close() may only return it, so the
                // unlock above is doing the work and this is best effort.
                connection.close();
            }
        }
        catch (Exception e) {
            log.warn("Could not discard the schema initialization lock connection", e);
        }
        connection = null;
    }
}
