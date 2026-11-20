package io.descoped.lds.core.persistence.neo4j;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.neo4j.Neo4jContainer;

import java.util.Map;

/**
 * One Neo4j 2026 container (community edition) for every test class of the module, started on first use and
 * stopped by Testcontainers when the JVM ends. The tests need Docker.
 */
final class Neo4j2026 {

    private static final String IMAGE = "neo4j:2026.10-community";

    private static final Neo4jContainer CONTAINER = start();

    private Neo4j2026() {
    }

    private static Neo4jContainer start() {
        Neo4jContainer container = new Neo4jContainer(IMAGE)
                .withAdminPassword("lds-development");
        container.start();
        LoopbackPorts.check(container);
        return container;
    }

    /**
     * A driver of its own for the container, to be closed by the caller.
     */
    static Driver driver() {
        return GraphDatabase.driver(CONTAINER.getBoltUrl(), AuthTokens.basic("neo4j", CONTAINER.getAdminPassword()));
    }

    /**
     * The provider configuration that reaches the container.
     */
    static Map<String, String> configuration() {
        return Map.of(
                "neo4j.driver.url", CONTAINER.getBoltUrl(),
                "neo4j.driver.username", "neo4j",
                "neo4j.driver.password", CONTAINER.getAdminPassword(),
                "neo4j.cypher.show", "false"
        );
    }
}
