package io.descoped.lds.server;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The images of the server, built from the Dockerfile with what Maven has just packaged, and started as they are:
 * no configuration, so everything in memory, with the example model. The regular image (the default target) is
 * tried in full; the distroless and the debug image, which hold the same LDS on another base or runtime, are tried
 * for what makes them differ. The test needs Docker and its command line.
 */
class ServerImageIT {

    private static final String IMAGE = "lds-server-it:" + UUID.randomUUID();
    private static final String DISTROLESS = IMAGE + "-distroless";
    private static final String DEBUG = IMAGE + "-debug";
    private static final int PORT = 9090;

    private static GenericContainer<?> container;
    private static GenericContainer<?> distroless;
    private static GenericContainer<?> debug;
    private static Lds lds;

    @BeforeAll
    static void buildAndStart() throws Exception {
        docker("build", "--quiet", "--tag", IMAGE, ".");
        docker("build", "--quiet", "--target", "distroless", "--tag", DISTROLESS, ".");
        docker("build", "--quiet", "--target", "debug", "--tag", DEBUG, ".");
        container = start(IMAGE);
        distroless = start(DISTROLESS);
        debug = start(DEBUG);
        lds = new Lds(container.getMappedPort(PORT));
    }

    @AfterAll
    static void stopAndRemove() throws Exception {
        for (GenericContainer<?> started : new GenericContainer<?>[]{container, distroless, debug}) {
            if (started != null) {
                started.stop();
            }
        }
        docker("image", "rm", "--force", IMAGE, DISTROLESS, DEBUG);
    }

    /*
     * Started when LDS answers /ping, as whoever runs it asks: the image declares no health check.
     */
    @SuppressWarnings("resource") // the container is returned, and the caller stops it
    private static GenericContainer<?> start(String image) {
        GenericContainer<?> started = new GenericContainer<>(image)
                .withExposedPorts(PORT)
                .waitingFor(Wait.forHttp("/ping").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));
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

    /**
     * The distroless image has no program but {@code java} to ask, so the user of each image is read from what the
     * image says it runs as and from its {@code /etc/passwd}.
     */
    @Test
    void ldsDoesNotRunAsRootInAnyImage() {
        for (GenericContainer<?> started : List.of(container, distroless, debug)) {
            String user = started.getDockerClient().inspectContainerCmd(started.getContainerId()).exec().getConfig().getUser();
            String passwd = started.copyFileFromContainer("/etc/passwd", in -> new String(in.readAllBytes(), StandardCharsets.UTF_8));

            assertThat(user).as(started.getDockerImageName()).isEqualTo("lds");
            assertThat(passwd.lines()).as(started.getDockerImageName()).contains("lds:x:1000:1000:lds:/lds:/sbin/nologin");
        }
    }

    /**
     * The regular image has a shell: the one of busybox, with its small tools.
     */
    @Test
    void theRegularImageHasAShell() throws Exception {
        ExecResult result = container.execInContainer("sh", "-c", "id -un");

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout().strip()).isEqualTo("lds");
    }

    /**
     * The distroless image answers as the regular one does, and has no shell to run.
     */
    @Test
    void theDistrolessImageAnswersAndHasNoShell() throws Exception {
        Lds other = new Lds(distroless.getMappedPort(PORT));
        assertThat(other.put("/ns/Company/void", "{\"name\":\"Void\"}").statusCode()).isEqualTo(201);
        assertThat(other.get("/ns/Company/void").body()).contains("\"name\":\"Void\"");

        ExecResult result = distroless.execInContainer("sh", "-c", "true");

        assertThat(result.getExitCode()).isNotZero();
        assertThat(result.getStdout() + result.getStderr()).contains("executable file not found");
    }

    /**
     * No image declares a health check: only LDS reads its configuration, so only whoever runs it knows the port and
     * the prefix of {@code /ping}.
     */
    @Test
    void noImageDeclaresAHealthCheck() {
        for (GenericContainer<?> started : List.of(container, distroless, debug)) {
            assertThat(started.getDockerClient().inspectContainerCmd(started.getContainerId()).exec().getConfig()
                    .getHealthcheck()).as(started.getDockerImageName()).isNull();
        }
    }

    /**
     * {@code /ping} and {@code /health/alive} answer whenever the process does: whether LDS can take writes is the
     * readiness.
     */
    @Test
    void pingAndAliveAnswerWheneverTheProcessDoes() throws Exception {
        assertThat(lds.get("/ping").statusCode()).isEqualTo(200);
        assertThat(lds.get("/health/alive").statusCode()).isEqualTo(200);
    }

    /**
     * In memory, LDS is ready when the store, the saga log and the transaction log answer; there is no search.
     */
    @Test
    void ldsInMemoryIsReady() throws Exception {
        HttpResponse<String> ready = lds.get("/health/ready");

        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(ready.body()).startsWith("{\"status\":\"UP\",\"checks\":{\"persistence\":{\"status\":\"UP\"")
                .contains("\"sagaLog\":{\"status\":\"UP\"", "\"transactionLog\":{\"status\":\"UP\"")
                .doesNotContain("search", "indexQueue");
    }

    /**
     * {@code /ping} is under the prefix and on the port LDS is given, here by its variables.
     */
    @Test
    void pingIsUnderThePrefixOnThePortLdsIsGiven() throws Exception {
        try (GenericContainer<?> prefixed = new GenericContainer<>(IMAGE)
                .withEnv("LDS_HTTP_PREFIX", "/lds")
                .withEnv("LDS_HTTP_PORT", "9191")
                .withExposedPorts(9191)
                .waitingFor(Wait.forHttp("/lds/ping").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)))) {
            prefixed.start();
            LoopbackPorts.check(prefixed);
            Lds other = new Lds(prefixed.getMappedPort(9191));

            assertThat(other.get("/lds/ping").statusCode()).isEqualTo(200);
            assertThat(other.get("/ping").statusCode()).isEqualTo(404);
        }
    }

    /**
     * The debug image has the tools of the JDK, which attach to the JVM of LDS.
     */
    @Test
    void theDebugImageHasTheToolsOfTheJdk() throws Exception {
        ExecResult version = debug.execInContainer("jcmd", "1", "VM.version");
        ExecResult threads = debug.execInContainer("jstack", "1");

        assertThat(version.getExitCode()).as(version.getStderr()).isZero();
        assertThat(version.getStdout()).contains("JDK 27");
        assertThat(threads.getStdout()).contains("Full thread dump");
    }

    /**
     * A variable that starts with LDS_ and sets no key is a name spelled wrong; LDS does not start with it, and says
     * which. The output goes to a file and the wait is bounded, so a container that starts anyway is stopped.
     */
    @Test
    void aVariableThatSetsNoKeyStopsTheStart() throws Exception {
        String name = "lds-unknown-variable-" + ProcessHandle.current().pid();
        Path output = Files.createTempFile("lds-unknown-variable", ".log");
        try {
            Process process = new ProcessBuilder("docker", "run", "--rm", "--name", name,
                    "-e", "LDS_NAMESPACE_DEFALT=x", "-e", "LDS_HTTP_PORT=9090", IMAGE)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            boolean ended = process.waitFor(2, TimeUnit.MINUTES);
            if (!ended) {
                docker("rm", "--force", name);
            }
            assertThat(ended).as("LDS stopped by itself: %s", Files.readString(output)).isTrue();
            assertThat(process.exitValue()).isEqualTo(1);
            assertThat(Files.readString(output))
                    .contains("environment variable(s) LDS_NAMESPACE_DEFALT match no configuration key")
                    .doesNotContain("LDS_HTTP_PORT")
                    .doesNotContain("Server started");
        } finally {
            Files.deleteIfExists(output);
        }
    }

    /**
     * A saga log in PostgreSQL whose pool cannot hold a connection for each saga log and one more is refused at
     * start, before LDS connects to the database, with the keys to change.
     */
    @Test
    void aSagaLogPoolTooSmallForItsLogsStopsTheStart() throws Exception {
        String name = "lds-small-pool-" + ProcessHandle.current().pid();
        Path output = Files.createTempFile("lds-small-pool", ".log");
        try {
            Process process = new ProcessBuilder("docker", "run", "--rm", "--name", name,
                    "-e", "LDS_SAGALOG_PROVIDER=no.cantara.sagalog.postgres.PostgresSagaLogInitializer",
                    "-e", "LDS_SAGALOG_CONFIG_CONNECTION_POOL_MAX_SIZE=10", "-e", "LDS_SAGA_NUMBER_OF_LOGS=30", IMAGE)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            boolean ended = process.waitFor(2, TimeUnit.MINUTES);
            if (!ended) {
                docker("rm", "--force", name);
            }
            assertThat(ended).as("LDS stopped by itself: %s", Files.readString(output)).isTrue();
            assertThat(process.exitValue()).isEqualTo(1);
            assertThat(Files.readString(output))
                    .contains("The saga log has 10 connections in its pool (sagalog.config.connection-pool.max-size)"
                            + " for 30 saga logs (saga.number-of-logs)")
                    .doesNotContain("SagaLogPool provider initialization attempt")
                    .doesNotContain("Server started");
        } finally {
            Files.deleteIfExists(output);
        }
    }

    /**
     * {@code docker stop} sends the signal to the JVM itself, which then stops the application in order and ends
     * with 143, the code of a process that ended by that signal. Its log has no warning of the JDK about
     * {@code sun.misc.Unsafe}, which Undertow uses to free the buffers of the requests.
     */
    @Test
    void theContainerStopsWhenItIsAskedTo() throws Exception {
        GenericContainer<?> another = start(IMAGE);
        try {
            Lds anotherLds = new Lds(another.getMappedPort(PORT));
            for (int i = 0; i < 100; i++) {
                assertThat(anotherLds.put("/ns/Company/c" + i, "{\"name\":\"C" + i + "\"}").statusCode()).isEqualTo(201);
                assertThat(anotherLds.get("/ns/Company/c" + i).statusCode()).isEqualTo(200);
            }
            another.getDockerClient().stopContainerCmd(another.getContainerId()).withTimeout(60).exec();

            InspectContainerResponse.ContainerState state = another.getDockerClient().inspectContainerCmd(another.getContainerId()).exec().getState();
            assertThat(state.getExitCodeLong()).isEqualTo(143L);
            assertThat(another.getLogs()).contains("ShutdownHook triggered", "Leaving.. Bye!")
                    .doesNotContain("sun.misc.Unsafe");
        } finally {
            another.stop();
        }
    }
}
