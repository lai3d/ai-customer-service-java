package dev.merlionos.customerservice;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real pgvector Postgres with this repository's migrations applied, and no Spring context.
 * For the classes whose whole job is a few SQL statements -- tickets, budget, lease -- the
 * database is the thing under test and a context around it would only slow the test down and
 * put a mock somewhere it should not be.
 */
public final class MigratedPostgres implements AutoCloseable {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:pg17")
            .asCompatibleSubstituteFor("postgres");

    public final PostgreSQLContainer<?> container;
    public final HikariDataSource dataSource;
    public final JdbcTemplate jdbc;
    public final DataSourceTransactionManager transactionManager;

    private MigratedPostgres() {
        container = new PostgreSQLContainer<>(PGVECTOR_IMAGE);
        container.start();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(container.getJdbcUrl());
        dataSource.setUsername(container.getUsername());
        dataSource.setPassword(container.getPassword());
        dataSource.setMaximumPoolSize(20);
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    public static MigratedPostgres start() {
        return new MigratedPostgres();
    }

    public int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Override
    public void close() {
        dataSource.close();
        container.stop();
    }
}
