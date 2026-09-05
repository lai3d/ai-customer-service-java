package dev.merlionos.customerservice;

import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Supplies a pgvector-enabled Postgres to the test context: one container per JVM, and a
 * fresh database inside it per context.
 *
 * <p>It used to be one container per context. Spring caches every distinct test context
 * for the life of the JVM, and with it the container the context started, so a run of the
 * suite held fifteen Postgres servers at once next to fifteen ONNX sessions -- and the CI
 * runner killed the job at the fifteenth or sixteenth context with every test green and
 * "The operation was canceled" as the only word on it (PR #22, PR #30). A database is what
 * a context needs to be alone in; a server is not. {@code CREATE DATABASE} in a running
 * container costs a few milliseconds, and V1 creates the {@code vector} extension in
 * whichever database it migrates.
 *
 * <p>Imported rather than extended so tests stay free to pick their own base class. The
 * tests that run without a Spring context ({@link MigratedPostgres}) and the ones that start
 * roles by hand share the container the same way, through {@link #freshDatabase()}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:pg17")
            .asCompatibleSubstituteFor("postgres");

    /**
     * Started on first use; Testcontainers' reaper removes it when the JVM exits. Postgres
     * admits 100 clients by default, and every cached context keeps a pool open; the first
     * full run against one container stopped at "sorry, too many clients already". The
     * test profile also keeps each pool's idle floor at one connection.
     */
    private static final PostgreSQLContainer<?> SHARED = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
            .withCommand("postgres", "-c", "max_connections=500");
    private static final AtomicInteger DATABASES = new AtomicInteger();

    /** Where one test context's database is. */
    public record Database(String jdbcUrl, String username, String password) {
    }

    static synchronized PostgreSQLContainer<?> container() {
        if (!SHARED.isRunning()) {
            SHARED.start();
        }
        return SHARED;
    }

    /** A new, empty database in the shared container, migrated by whoever connects with Flyway. */
    public static Database freshDatabase() {
        PostgreSQLContainer<?> container = container();
        String name = "test_" + DATABASES.incrementAndGet() + "_" + Long.toHexString(System.nanoTime() & 0xffffff);
        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        catch (SQLException e) {
            throw new IllegalStateException("Could not create a test database in the shared container", e);
        }
        String url = container.getJdbcUrl().replaceFirst("/" + container.getDatabaseName() + "(\\?|$)", "/" + name + "$1");
        return new Database(url, container.getUsername(), container.getPassword());
    }

    /**
     * Replaces the {@code @ServiceConnection} this used to be: Boot's own connection details
     * back off when a bean of this type exists, so the data source, Flyway and everything
     * behind them point at this context's database.
     */
    @Bean
    JdbcConnectionDetails postgresConnectionDetails() {
        Database database = freshDatabase();
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return database.username();
            }

            @Override
            public String getPassword() {
                return database.password();
            }

            @Override
            public String getJdbcUrl() {
                return database.jdbcUrl();
            }
        };
    }
}
