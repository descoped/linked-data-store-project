package io.descoped.lds.core.persistence.neo4j;

import io.descoped.lds.core.persistence.test.PersistenceIntegrationTest;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import java.util.Set;

public class Neo4jPersistenceIntegrationTest extends PersistenceIntegrationTest {

    public Neo4jPersistenceIntegrationTest() {
        super("lds-provider-neo4j-integration-test");
    }

    @BeforeClass
    public void setup() {

        persistence = new Neo4jInitializer().initialize(namespace, Neo4j2026.configuration(),
                Set.of("Person", "Address", "FunkyLongAddress"));

    }

    @AfterClass
    public void teardown() {
        if (persistence != null) {
            persistence.close();
        }
    }
}
