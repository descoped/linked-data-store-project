package io.descoped.lds.core.saga;

import io.descoped.config.DynamicConfiguration;
import io.descoped.config.StoreBasedDynamicConfiguration;
import io.descoped.lds.core.UndertowApplication;
import io.descoped.lds.test.server.TestServer;
import io.descoped.lds.test.server.TestServerListener;
import no.cantara.saga.execution.SagaHandoffResult;
import no.cantara.sagalog.SagaLogOwner;
import no.cantara.sagalog.SagaLogPool;
import no.cantara.sagalog.SagaPoolShutdownException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The order in which the application stops. A saga hands each of its steps to the thread pool of the sagas, writes
 * each step to its saga log and writes to the stores, so the application lets the sagas that are running complete
 * before it shuts any of those down. Each test starts a server of its own, with a saga log whose writes and
 * truncate take time ({@link SlowTruncateSagaLogInitializer}), so that a saga is still running when the server is
 * stopped.
 */
class ApplicationStopTest {

    private static final String BODY = "{\"name\":\"stop\",\"contacts\":[]}";

    private final HttpClient http = HttpClient.newHttpClient();
    private TestServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.getApplication().stop(); // a second stop does nothing more
        }
    }

    @Test
    void aSagaThatIsRunningCompletesBeforeTheApplicationStops() throws Exception {
        server = start();
        SagaExecutionCoordinator sec = server.getApplication().getSec();

        // answered when the saga has written its first entry; the saga goes on
        assertThat(put("/data/provisionagreement/s1?sync=false")).isEqualTo(201);
        List<CompletableFuture<SagaHandoffResult>> running = List.copyOf(sec.runningSagas);
        assertThat(running).hasSize(1);
        assertThat(running.getFirst()).isNotDone();

        server.getApplication().stop();

        assertThat(running.getFirst()).isCompleted();
        assertThat(running.getFirst().join().isSuccess()).isTrue();
        assertThat(sec.runningSagas).isEmpty();
        assertThat(sec.executionIdBySagaLogId).isEmpty();
    }

    @Test
    void theSagaLogPoolGivesOutNoLogAfterTheApplicationHasStopped() {
        server = start();
        SagaLogPool pool = server.getApplication().getSec().getSagaLogPool();

        server.getApplication().stop();

        assertThatThrownBy(() -> pool.tryAcquire(new SagaLogOwner("application-stop-test")))
                .isInstanceOf(SagaPoolShutdownException.class);
        assertThat(server.getApplication().getSec().getThreadPool().isTerminated()).isTrue();
    }

    private TestServer start() {
        DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
                .propertiesResource(UndertowApplication.getDefaultConfigurationResourcePath())
                .propertiesResource("application_test.properties")
                .values(
                        "sagalog.provider", SlowTruncateSagaLogInitializer.class.getName(),
                        "saga.recovery.enabled", "false",
                        "saga.number-of-logs", "1")
                .build();
        TestServer started = new TestServer(configuration, TestServerListener.findFreePort(new Random(), 9500, 9999));
        started.getApplication().start();
        return started;
    }

    private int put(String uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.testURL(uri))).timeout(Duration.ofSeconds(60))
                .PUT(HttpRequest.BodyPublishers.ofString(BODY)).build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
