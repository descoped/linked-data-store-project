package io.descoped.lds.server;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole LDS of the compose file, started with {@code docker compose} as a person would: the image built from
 * the Dockerfile, Neo4j for the documents, PostgreSQL for the saga log and the transaction log, and Solr for
 * search. Only the ports are other than in the file: Docker picks them, so that the test runs next to a stack
 * that is already up. The test needs Docker with its compose command.
 */
class ServerStackIT {

    private static ComposeContainer stack;
    private static ContainerState server;
    private static Lds lds;

    @BeforeAll
    static void start() {
        stack = new ComposeContainer(new File("compose.yaml"))
                .withBuild(true)
                .withEnv("LDS_PORT", "0")
                .withEnv("NEO4J_HTTP_PORT", "0")
                .withEnv("NEO4J_BOLT_PORT", "0")
                .withEnv("SOLR_PORT", "0")
                .withRemoveImages(ComposeContainer.RemoveImages.LOCAL)
                .waitingFor("lds", Wait.forHealthcheck())
                .withStartupTimeout(Duration.ofMinutes(5));
        stack.start();
        server = stack.getContainerByServiceName("lds").orElseThrow();
        LoopbackPorts.check(server);
        lds = new Lds(publishedPort(server, 9090));
    }

    /*
     * The port on the machine that Docker picked for a port of a container that compose started.
     */
    private static int publishedPort(ContainerState container, int port) {
        Ports.Binding[] bindings = container.getContainerInfo().getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(port));
        assertThat(bindings).as("where port %s is published", port).hasSize(1);
        return Integer.parseInt(bindings[0].getHostPortSpec());
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.stop();
        }
    }

    @Test
    void ldsIsConfiguredWithEveryEngineOfTheStack() {
        assertThat(server.getLogs()).contains(
                "Using persistence provider: neo4j",
                "Using saga-log-pool provider-class: no.cantara.sagalog.postgres.PostgresSagaLogInitializer",
                "Using transaction-log provider: postgres",
                "Search index provider configured: solr");
    }

    @Test
    void aDocumentIsWrittenAndReadBackAndItsLinkIsFollowedBothWays() throws Exception {
        assertThat(lds.put("/ns/Company/acme", "{\"name\":\"Acme\"}").statusCode()).isEqualTo(201);
        assertThat(lds.put("/ns/Person/ola", "{\"name\":\"Ola\",\"address\":{\"city\":\"Oslo\"},\"employer\":\"/Company/acme\"}").statusCode()).isEqualTo(201);

        HttpResponse<String> read = lds.get("/ns/Person/ola");
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).contains("\"name\":\"Ola\"", "\"address\":{\"city\":\"Oslo\"}", "\"employer\":\"/Company/acme\"");

        assertThat(lds.graphql("{ PersonById(id: \"ola\") { name employer { name } } }"))
                .isEqualTo("{\"data\":{\"PersonById\":{\"name\":\"Ola\",\"employer\":{\"name\":\"Acme\"}}}}");
        assertThat(lds.graphql("{ CompanyById(id: \"acme\") { employees { edges { node { name } } } } }"))
                .isEqualTo("{\"data\":{\"CompanyById\":{\"employees\":{\"edges\":[{\"node\":{\"name\":\"Ola\"}}]}}}}");
    }

    /**
     * A write is in the index within the five seconds the provider gives Solr to commit it.
     */
    @Test
    void aDocumentIsFoundByATextOfIt() throws Exception {
        assertThat(lds.put("/ns/Company/brew", "{\"name\":\"Nordic Brewing\",\"about\":[{\"languageCode\":\"en\",\"languageText\":\"A small brewery by the fjord\"}]}").statusCode()).isEqualTo(201);

        String query = "{ Search(query: \"fjord\") { edges { node { ... on Company { name } } } } }";
        String found = lds.graphql(query);
        long giveUp = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!found.contains("Nordic Brewing") && System.nanoTime() < giveUp) {
            Thread.sleep(500);
            found = lds.graphql(query);
        }

        assertThat(found).isEqualTo("{\"data\":{\"Search\":{\"edges\":[{\"node\":{\"name\":\"Nordic Brewing\"}}]}}}");
    }
}
