package dev.merlionos.customerservice;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * A real pgvector Postgres with this repository's migrations applied, and no Spring context.
 * For the classes whose whole job is a few SQL statements -- tickets, budget, lease -- the
 * database is the thing under test and a context around it would only slow the test down and
 * put a mock somewhere it should not be.
 */
public final class MigratedPostgres implements AutoCloseable {

    /** This test's own database in the shared container; see {@link PostgresTestcontainer}. */
    public final PostgresTestcontainer.Database database;
    public final HikariDataSource dataSource;
    public final JdbcTemplate jdbc;
    public final DataSourceTransactionManager transactionManager;

    private MigratedPostgres() {
        database = PostgresTestcontainer.freshDatabase();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(database.jdbcUrl());
        dataSource.setUsername(database.username());
        dataSource.setPassword(database.password());
        dataSource.setMaximumPoolSize(20);
        dataSource.setMinimumIdle(1);
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
    }
}
