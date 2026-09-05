package dev.merlionos.customerservice;

import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
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
     * One ONNX session per JVM as well. With the containers gone the runner still died at the
     * sixteenth context (PR #33): the embedding model, its tokenizer and onnxruntime's arena
     * are the other few hundred megabytes each cached context keeps. The first context to
     * build a {@link TransformersEmbeddingModel} keeps it here; every later context is handed
     * that instance before Spring would instantiate its own, so nothing loads the model twice.
     * The model is used concurrently in production, so sharing it across contexts is what it
     * already does across requests. {@code static}, so the post-processor exists before any
     * other bean in the context, which is what a post-processor needs.
     *
     * <p>A context that needs the model's observations in its own meter registry -- the
     * dashboard test, which asserts the embedding timer exists -- opts out with
     * {@code app.test.own-embedding-model=true} and loads its own.
     */
    @Bean
    static SharedEmbeddingModel sharedEmbeddingModel() {
        return new SharedEmbeddingModel();
    }

    public static final String OWN_EMBEDDING_MODEL = "app.test.own-embedding-model";

    private static volatile TransformersEmbeddingModel SHARED_EMBEDDING_MODEL;

    static class SharedEmbeddingModel implements InstantiationAwareBeanPostProcessor, EnvironmentAware {

        private boolean own;

        @Override
        public void setEnvironment(Environment environment) {
            own = environment.getProperty(OWN_EMBEDDING_MODEL, Boolean.class, false);
        }

        @Override
        public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
            return !own && TransformersEmbeddingModel.class.isAssignableFrom(beanClass) ? SHARED_EMBEDDING_MODEL : null;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!own && bean instanceof TransformersEmbeddingModel model && SHARED_EMBEDDING_MODEL == null) {
                SHARED_EMBEDDING_MODEL = model;
            }
            return bean;
        }
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
