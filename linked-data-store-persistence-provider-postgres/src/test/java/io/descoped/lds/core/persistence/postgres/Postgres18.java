package io.descoped.lds.core.persistence.postgres;

import org.testcontainers.postgresql.PostgreSQLContainer;

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

    private static PostgreSQLContainer start() {
        PostgreSQLContainer container = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("lds")
                .withUsername("lds")
                .withPassword("lds");
        container.start();
        return container;
    }

    /**
     * The provider configuration that reaches the container.
     */
    static Map<String, String> configuration() {
        return Map.of(
                "postgres.driver.host", CONTAINER.getHost(),
                "postgres.driver.port", String.valueOf(CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)),
                "postgres.driver.user", CONTAINER.getUsername(),
                "postgres.driver.password", CONTAINER.getPassword(),
                "postgres.driver.database", CONTAINER.getDatabaseName()
        );
    }
}
