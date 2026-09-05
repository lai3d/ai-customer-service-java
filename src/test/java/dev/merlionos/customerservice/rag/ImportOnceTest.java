package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.CustomerServiceApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code app.rag.import-mode=once} is what a Kubernetes Job runs: import, record, exit zero.
 * This starts the real application against a fresh database with a recording exit handler
 * in place of {@code System.exit}, and reads the rows the next process would.
 */
class ImportOnceTest {

    @Test
    @DisplayName("once imports, records the version, closes the context and exits zero")
    void importsThenExits() throws InterruptedException {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            CountDownLatch exited = new CountDownLatch(1);
            AtomicInteger exitCode = new AtomicInteger(-1);
            ExitHandler recording = code -> {
                exitCode.set(code);
                exited.countDown();
            };

            ConfigurableApplicationContext context = new SpringApplicationBuilder(CustomerServiceApplication.class)
                    .profiles("test")
                    .initializers(ctx -> ctx.getBeanFactory().registerSingleton("exitHandler", recording))
                    .run("--app.rag.import-mode=once", "--server.port=0",
                            "--spring.datasource.url=" + postgres.getJdbcUrl(),
                            "--spring.datasource.username=" + postgres.getUsername(),
                            "--spring.datasource.password=" + postgres.getPassword());

            assertThat(exited.await(60, TimeUnit.SECONDS)).as("the process asked to exit").isTrue();
            assertThat(exitCode.get()).isZero();
            assertThat(context.isActive()).as("the context was closed before exiting").isFalse();

            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store", Integer.class)).isEqualTo(36);
            assertThat(jdbc.queryForObject("SELECT document_count FROM corpus_import", Integer.class)).isEqualTo(36);
        }
    }
}
