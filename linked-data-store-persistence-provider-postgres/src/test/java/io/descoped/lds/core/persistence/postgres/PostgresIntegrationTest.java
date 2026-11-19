package io.descoped.lds.core.persistence.postgres;

import io.descoped.lds.core.persistence.test.PersistenceIntegrationTest;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import java.util.Set;

public class PostgresIntegrationTest extends PersistenceIntegrationTest {

    public PostgresIntegrationTest() {
        super("lds-provider-postgres-integration-test");
    }

    @BeforeClass
    public void setup() {
        persistence = new PostgresDbInitializer().initialize(namespace, Postgres18.configuration(),
                Set.of("Person", "Address", "FunkyLongAddress"));
    }

    @AfterClass
    public void teardown() {
        if (persistence != null) {
            persistence.close();
        }
    }
}
