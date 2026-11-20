package io.descoped.lds.server;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The image of the server, built from the Dockerfile with what Maven has just packaged, and started as it is:
 * no configuration, so everything in memory, with the example model. The test needs Docker and its command line.
 */
class ServerImageIT {

    private static final String IMAGE = "lds-server-it:" + UUID.randomUUID();
    private static final int PORT = 9090;

    private static GenericContainer<?> container;
    private static Lds lds;

    @BeforeAll
    static void buildAndStart() throws Exception {
        docker("build", "--quiet", "--tag", IMAGE, ".");
        container = start();
        lds = new Lds(container.getMappedPort(PORT));
    }

    @AfterAll
    static void stopAndRemove() throws Exception {
        if (container != null) {
            container.stop();
        }
        docker("image", "rm", "--force", IMAGE);
    }

    /*
     * Started when the health check of the image says that LDS answers.
     */
    private static GenericContainer<?> start() {
        GenericContainer<?> started = new GenericContainer<>(IMAGE)
                .withExposedPorts(PORT)
                .waitingFor(Wait.forHealthcheck().withStartupTimeout(Duration.ofMinutes(2)));
        started.start();
        LoopbackPorts.check(started);
        return started;
    }

    private static void docker(String... arguments) throws Exception {
        Process process = new ProcessBuilder(Stream.concat(Stream.of("docker"), Stream.of(arguments)).toList())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(5, TimeUnit.MINUTES)).as("docker %s ended", List.of(arguments)).isTrue();
        assertThat(process.exitValue()).as("docker %s: %s", List.of(arguments), output).isZero();
    }

    @Test
    void aDocumentOfTheExampleModelIsWrittenAndReadBack() throws Exception {
        assertThat(lds.put("/ns/Company/acme", "{\"name\":\"Acme\"}").statusCode()).isEqualTo(201);
        assertThat(lds.put("/ns/Person/ola", "{\"name\":\"Ola\",\"address\":{\"city\":\"Oslo\"},\"employer\":\"/Company/acme\"}").statusCode()).isEqualTo(201);

        HttpResponse<String> read = lds.get("/ns/Person/ola");

        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).contains("\"name\":\"Ola\"", "\"address\":{\"city\":\"Oslo\"}", "\"employer\":\"/Company/acme\"");
    }

    @Test
    void aLinkIsFollowedBothWaysWithGraphQL() throws Exception {
        assertThat(lds.put("/ns/Company/brew", "{\"name\":\"Brew\"}").statusCode()).isEqualTo(201);
        assertThat(lds.put("/ns/Person/kari", "{\"name\":\"Kari\",\"employer\":\"/Company/brew\"}").statusCode()).isEqualTo(201);

        assertThat(lds.graphql("{ PersonById(id: \"kari\") { name employer { name } } }"))
                .isEqualTo("{\"data\":{\"PersonById\":{\"name\":\"Kari\",\"employer\":{\"name\":\"Brew\"}}}}");
        assertThat(lds.graphql("{ CompanyById(id: \"brew\") { employees { edges { node { name } } } } }"))
                .isEqualTo("{\"data\":{\"CompanyById\":{\"employees\":{\"edges\":[{\"node\":{\"name\":\"Kari\"}}]}}}}");
    }

    @Test
    void aDocumentThatDoesNotFollowTheModelIsRefused() throws Exception {
        HttpResponse<String> refused = lds.put("/ns/Person/wrong", "{\"name\":7}");

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).isEqualTo("Schema validation error: [$.name: integer found, string expected]");
        assertThat(lds.get("/ns/Person/wrong").statusCode()).isEqualTo(404);
    }

    @Test
    void theModelOfTheImageIsTheExample() throws Exception {
        HttpResponse<String> schema = lds.get("/ns/Company?schema");

        assertThat(schema.statusCode()).isEqualTo(200);
        assertThat(schema.body()).contains("\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"", "\"$ref\":\"#/$defs/Company\"", "languageText");
    }

    @Test
    void ldsDoesNotRunAsRoot() throws Exception {
        assertThat(container.execInContainer("id", "-un").getStdout().strip()).isEqualTo("lds");
    }

    /**
     * {@code docker stop} sends the signal to the JVM itself, which then stops the application in order and ends
     * with 143, the code of a process that ended by that signal.
     */
    @Test
    void theContainerStopsWhenItIsAskedTo() {
        GenericContainer<?> another = start();
        try {
            another.getDockerClient().stopContainerCmd(another.getContainerId()).withTimeout(60).exec();

            InspectContainerResponse.ContainerState state = another.getDockerClient().inspectContainerCmd(another.getContainerId()).exec().getState();
            assertThat(state.getExitCodeLong()).isEqualTo(143L);
            assertThat(another.getLogs()).contains("ShutdownHook triggered", "Leaving.. Bye!");
        } finally {
            another.stop();
        }
    }
}
