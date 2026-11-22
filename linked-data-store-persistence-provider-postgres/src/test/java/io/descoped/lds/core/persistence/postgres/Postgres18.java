package io.descoped.lds.core.persistence.postgres;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * One PostgreSQL 18 container for every test class of the module, started on first use and stopped by
 * Testcontainers when the JVM ends. The tests need Docker.
 */
final class Postgres18 {

    private static final String IMAGE = "postgres:18-alpine";

    private static final PostgreSQLContainer CONTAINER = start();

    private Postgres18() {
    }

    @SuppressWarnings("resource") // the container is returned, and Testcontainers stops it when the JVM ends
    private static PostgreSQLContainer start() {
        PostgreSQLContainer container = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("lds")
                .withUsername("lds")
                .withPassword("lds");
        container.start();
        LoopbackPorts.check(container);
        return container;
    }

    /**
     * The provider configuration that reaches the container.
     */
    static Map<String, String> configuration() {
        return configuration(CONTAINER.getDatabaseName());
    }

    /**
     * Creates an empty database in the container, and returns the provider configuration that reaches it.
     *
     * @param number tells the databases of one test run apart
     */
    static Map<String, String> configurationOfNewDatabase(int number) {
        String database = "empty_" + number;
        try (Connection connection = CONTAINER.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return configuration(database);
    }

    private static Map<String, String> configuration(String database) {
        return Map.of(
                "postgres.driver.host", CONTAINER.getHost(),
                "postgres.driver.port", String.valueOf(CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)),
                "postgres.driver.user", CONTAINER.getUsername(),
                "postgres.driver.password", CONTAINER.getPassword(),
                "postgres.driver.database", database
        );
    }
}
