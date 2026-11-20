package io.descoped.lds.core.saga;

import io.descoped.config.DynamicConfiguration;
import io.descoped.config.StoreBasedDynamicConfiguration;
import io.descoped.lds.core.UndertowApplication;
import io.descoped.lds.test.server.TestServer;
import io.descoped.lds.test.server.TestServerListener;
import no.cantara.saga.execution.SagaHandoffResult;
import no.cantara.sagalog.SagaLogOwner;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
            server.getApplication().getSec().runningSagas.clear(); // what a test put there never completes
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

        server.getApplication().stop();

        assertStopped(server);
    }

    @Test
    void theStopWaitsForASagaThatDoesNotEndForAsLongAsItIsTold() {
        server = start();
        server.getApplication().getSec().runningSagas.add(new CompletableFuture<>());

        long before = System.nanoTime();
        server.getApplication().stop(200, TimeUnit.MILLISECONDS);

        assertThat(Duration.ofNanos(System.nanoTime() - before)).isBetween(Duration.ofMillis(200), Duration.ofSeconds(5));
        assertStopped(server);
    }

    @Test
    void aPartThatFailsToStopDoesNotKeepTheRestFromStopping() {
        server = start();
        server.getApplication().getSec().runningSagas.add(new CompletableFuture<>() {
            @Override
            public boolean isDone() {
                throw new IllegalStateException("as the test asked"); // waiting for the running sagas fails
            }
        });

        server.getApplication().stop();

        assertStopped(server);
    }

    @Test
    void waitingForTheRunningSagasEndsWhenTheyHaveCompletedOrFailed() {
        server = start();
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        CompletableFuture<SagaHandoffResult> completing = new CompletableFuture<>();
        CompletableFuture<SagaHandoffResult> failing = new CompletableFuture<>();
        sec.runningSagas.add(completing);
        sec.runningSagas.add(failing);

        assertThat(sec.awaitRunningSagas(50, TimeUnit.MILLISECONDS)).isFalse();

        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> {
            failing.completeExceptionally(new IllegalStateException("as the test asked"));
            completing.complete(null);
        });
        assertThat(sec.awaitRunningSagas(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void waitingForTheRunningSagasEndsWhenTheThreadIsInterrupted() {
        server = start();
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        sec.runningSagas.add(new CompletableFuture<>());

        Thread.currentThread().interrupt();
        try {
            assertThat(sec.awaitRunningSagas(10, TimeUnit.SECONDS)).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // cleared for what comes after
        }
    }

    /**
     * A recovery pass that meets a saga log pool that is shut down is the application stopping. The trigger ends
     * there: it schedules no pass after it.
     */
    @Test
    void theRecoveryTriggerEndsWhenTheSagaLogPoolIsShutDown() throws Exception {
        server = start();
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        server.getApplication().stop();

        AtomicInteger recoveries = new AtomicInteger(); // one for each saga log of each pass
        ScheduledThreadPoolExecutor recoveryThreadPool = new ScheduledThreadPoolExecutor(2) {
            @Override
            public void execute(Runnable command) {
                recoveries.incrementAndGet();
                super.execute(command);
            }
        };
        SagaRecoveryTrigger trigger = new SagaRecoveryTrigger(sec, 1, 1, recoveryThreadPool);
        trigger.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (recoveries.get() == 0) {
                assertThat(System.nanoTime()).as("waited for the first pass").isLessThan(deadline);
                Thread.sleep(50);
            }
            Thread.sleep(2500); // two more passes, if there were any
            assertThat(recoveries).hasValue(1);
        } finally {
            trigger.stop();
        }
    }

    @Test
    void aSagaLogPoolThatIsShutDownIsToldFromAFaultOfTheRecovery() {
        assertThat(SagaRecoveryTrigger.shuttingDown(new CompletionException(new SagaPoolShutdownException("the pool is shut down")))).isTrue();
        assertThat(SagaRecoveryTrigger.shuttingDown(new CompletionException(new IllegalStateException("another failure")))).isFalse();
    }

    private static void assertStopped(TestServer stopped) {
        SagaExecutionCoordinator sec = stopped.getApplication().getSec();
        SagaLogOwner owner = new SagaLogOwner("application-stop-test");
        assertThat(sec.getThreadPool().isTerminated()).isTrue();
        assertThatThrownBy(() -> sec.getSagaLogPool().tryAcquire(owner)).isInstanceOf(SagaPoolShutdownException.class);
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
