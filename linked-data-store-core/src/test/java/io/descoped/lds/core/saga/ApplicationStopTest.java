package io.descoped.lds.core.saga;

import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.core.UndertowApplication;
import io.descoped.lds.test.server.TestServer;
import io.descoped.lds.test.server.TestServerListener;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.SagaHandoffResult;
import no.cantara.saga.execution.adapter.Adapter;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryType;
import no.cantara.sagalog.SagaLogOwner;
import no.cantara.sagalog.SagaLogPool;
import no.cantara.sagalog.SagaPoolShutdownException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Map;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The order in which the application stops. A saga hands each of its steps to the thread pool of the sagas, writes
 * each step to its saga log and writes to the stores, so the application lets the sagas that are running complete
 * before it shuts any of those down. Each test starts a server of its own, with a saga log whose writes and
 * truncate take time ({@link SlowTruncateSagaLogInitializer}), so that a saga is still running when the server is
 * stopped.
 */
class ApplicationStopTest {

    private static final String BODY = "{\"name\":\"stop\",\"contacts\":[]}";
    private static final byte[] BODY_BYTES = BODY.getBytes(StandardCharsets.UTF_8);
    private static final int HALF = BODY_BYTES.length / 2;

    private final HttpClient http = HttpClient.newHttpClient();
    private TestServer server;
    private HeldSaga held;

    @AfterEach
    void stop() {
        if (held != null) {
            held.release.countDown();
        }
        if (server != null) {
            server.getApplication().getSec().runningSagas.clear(); // what a test put there never completes
            server.getApplication().stop(); // does nothing when the test has stopped it
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

    /**
     * A saga that is inside its first step when the application is stopped. Two steps follow that one, and the
     * saga hands one of them to the thread pool when the first step ends. The stop waits, so the pool is still
     * there then, and the saga completes.
     */
    @Test
    void aSagaInTheMiddleOfItsStepsCompletesWhenTheApplicationIsStopped() throws Exception {
        server = start();
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        held = new HeldSaga(sec);
        CompletableFuture<SagaHandoffResult> completion = held.start();
        assertThat(held.entered.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> stop = CompletableFuture.runAsync(() -> server.getApplication().stop());
        await("the stop waiting for the saga").during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5))
                .until(() -> !stop.isDone());
        assertThat(stop).isNotDone(); // waiting for the saga
        assertThat(sec.getThreadPool().isShutdown()).isFalse();
        held.release.countDown();
        stop.get(30, TimeUnit.SECONDS);

        assertThat(completion).isCompleted();
        assertThat(completion.join().isSuccess()).isTrue();
        assertThat(held.laterSteps).hasValue(2);
        assertStopped(server);
    }

    /**
     * A saga whose first step outlasts the time the stop waits. Its thread pool is shut down when the step ends,
     * so the step it hands to the pool is refused and the saga fails. It must still give back its saga log and
     * its permit, and its saga log must hold what it did, for the recovery after the next start: the saga began
     * and its first step ended.
     */
    @Test
    void aSagaThatOutlastsTheWaitOfTheStopFailsAndLeavesItsLogForRecovery() throws Exception {
        server = start();
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        int permits = sec.semaphore.availablePermits();
        held = new HeldSaga(sec);
        CompletableFuture<SagaHandoffResult> completion = held.start();
        assertThat(held.entered.await(10, TimeUnit.SECONDS)).isTrue();
        SagaLog sagaLog = sec.getSagaLogPool().connect(sec.executionIdBySagaLogId.keySet().iterator().next());

        CompletableFuture<Void> stop = CompletableFuture.runAsync(() -> server.getApplication().stop(200, TimeUnit.MILLISECONDS));
        await("the thread pool to be shut down").atMost(Duration.ofSeconds(10))
                .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .until(sec.getThreadPool()::isShutdown);
        // read when the saga has failed, on its own thread: the saga log pool is shut down after that thread ends
        List<SagaLogEntry> entries = new CopyOnWriteArrayList<>();
        completion.whenComplete((result, failure) -> sagaLog.readIncompleteSagas().forEach(entries::add));
        held.release.countDown();
        stop.get(30, TimeUnit.SECONDS);

        assertThat(completion).isCompletedExceptionally();
        assertThatThrownBy(completion::join).hasRootCauseInstanceOf(RejectedExecutionException.class);
        assertThat(held.laterSteps.get()).isLessThan(2); // the saga runs one of the two itself
        assertThat(entries)
                .anyMatch(entry -> Saga.ID_START.equals(entry.getNodeId()) && entry.getEntryType() == SagaLogEntryType.Start)
                .anyMatch(entry -> "a".equals(entry.getNodeId()) && entry.getEntryType() == SagaLogEntryType.End);
        assertThat(sec.semaphore.availablePermits()).isEqualTo(permits);
        assertThat(sec.executionIdBySagaLogId).isEmpty();
        assertStopped(server);
    }

    /**
     * While the application completes the requests it is handling, it answers a new request 503 Service
     * Unavailable: it is stopping, and the client may try again, here later or at another instance. The request
     * that is being handled, here one whose body has not all arrived, is completed and answered as always.
     */
    @Test
    void aRequestThatArrivesWhileTheApplicationIsStoppingIsAnswered503() throws Exception {
        server = start();
        try (Socket inFlight = requestWithHalfItsBody()) {
            OutputStream out = inFlight.getOutputStream();

            CompletableFuture<Void> stop = CompletableFuture.runAsync(() -> server.getApplication().stop());
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            int status;
            while ((status = put("/data/provisionagreement/late?sync=true")) != 503) {
                // a request that arrived before the stop had begun is handled as always
                assertThat(status).isEqualTo(201);
                assertThat(System.nanoTime()).as("waited for a 503").isLessThan(deadline);
            }
            assertThat(stop).isNotDone(); // waiting for the request whose body has not all arrived

            // a browser reads the 503 only when it has the CORS headers
            HttpRequest fromABrowser = HttpRequest.newBuilder(URI.create(server.testURL("/data/provisionagreement/late")))
                    .timeout(Duration.ofSeconds(60)).header("Origin", "https://app.example").GET().build();
            HttpResponse<Void> refused = http.send(fromABrowser, HttpResponse.BodyHandlers.discarding());
            assertThat(refused.statusCode()).isEqualTo(503);
            assertThat(refused.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://app.example");

            out.write(BODY_BYTES, HALF, BODY_BYTES.length - HALF);
            out.flush();
            String statusLine = new BufferedReader(new InputStreamReader(inFlight.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            assertThat(statusLine).startsWith("HTTP/1.1 201");
            stop.get(30, TimeUnit.SECONDS);
        }
        assertStopped(server);
    }

    /**
     * A request that is not completed in the time the stop waits does not hold the stop up: the HTTP server is
     * stopped, the request loses its connection, and the rest of the application stops.
     */
    @Test
    void aRequestThatIsNotCompletedInTimeDoesNotKeepTheApplicationFromStopping() throws Exception {
        server = start();
        try (Socket inFlight = requestWithHalfItsBody()) {
            server.getApplication().stop(200, TimeUnit.MILLISECONDS);

            assertStopped(server);
            inFlight.setSoTimeout(5000);
            assertThat(inFlight.getInputStream().read()).as("the connection is closed, without an answer").isEqualTo(-1);
        }
    }

    /**
     * A stop on a thread that is interrupted does not wait, and still stops every part.
     */
    @Test
    void aStopThatIsInterruptedStopsEveryPartWithoutWaiting() throws Exception {
        server = start();
        try (Socket inFlight = requestWithHalfItsBody()) {
            assertThat(inFlight.isConnected()).isTrue();
            Thread stopper = new Thread(() -> {
                Thread.currentThread().interrupt();
                server.getApplication().stop();
            }, "interrupted-stop");
            long before = System.nanoTime();
            stopper.start();
            stopper.join(30_000);

            assertThat(stopper.isAlive()).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(8)); // not the ten of a wait
            assertStopped(server);
        }
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
            await("the first pass").atMost(Duration.ofSeconds(10))
                    .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(50))
                    .until(() -> recoveries.get() != 0);
            // no pass after it, for the time of two more passes, if there were any
            await("no pass after the first").during(Duration.ofMillis(2500)).atMost(Duration.ofSeconds(5))
                    .until(() -> recoveries.get() == 1);
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
        SagaLogPool sagaLogPool = sec.getSagaLogPool();
        assertThatThrownBy(() -> sagaLogPool.tryAcquire(owner)).isInstanceOf(SagaPoolShutdownException.class);
    }

    /**
     * A saga of three steps: {@code a}, then {@code b} and {@code c} side by side. The adapter of {@code a} tells
     * the test that it is entered and waits there until the test lets it go. With two steps after {@code a}, the
     * saga hands one of them to the thread pool when {@code a} ends; a single step after it would be run by the
     * thread that ran {@code a}, and the saga would not need the pool again.
     */
    private static final class HeldSaga {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger laterSteps = new AtomicInteger();
        private final SagaExecutionCoordinator sec;
        private final Saga saga;

        HeldSaga(SagaExecutionCoordinator sec) {
            this.sec = sec;
            sec.sagaRepository.getAdapterLoader()
                    .register(new Adapter<>(String.class, "held", (input, dependees) -> {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("the held step was interrupted", e);
                        }
                        return "a";
                    }))
                    .register(new Adapter<>(String.class, "counted", (input, dependees) -> {
                        laterSteps.incrementAndGet();
                        return "later";
                    }));
            saga = Saga.start("HeldSaga").linkTo("a")
                    .id("a").adapter("held").linkTo("b", "c")
                    .id("b").adapter("counted").linkToEnd()
                    .id("c").adapter("counted").linkToEnd()
                    .end();
            sec.sagaRepository.register(saga);
        }

        /** Starts the saga and returns its completion. */
        CompletableFuture<SagaHandoffResult> start() {
            SagaInput input = new SagaInput(sec.generateTxId(), "PUT", "application-stop-test",
                    new DocumentKey("ns", "SomeEntity", "held", ZonedDateTime.now()), null, null,
                    JsonTools.mapper.createObjectNode());
            return sec.handoff(true, sec.sagaRepository.getAdapterLoader(), saga, input, Collections.emptyMap());
        }
    }

    private TestServer start() {
        DynamicConfiguration configuration = DynamicConfiguration.builder()
                .classpath(UndertowApplication.DEFAULT_CONFIGURATION_RESOURCE_PATH)
                .classpath("application_test.properties")
                .values(Map.of(
                        "sagalog.provider", SlowTruncateSagaLogInitializer.class.getName(),
                        "saga.recovery.enabled", "false",
                        "saga.number-of-logs", "1"))
                .build();
        TestServer started = new TestServer(configuration, TestServerListener.findFreePort(new Random(), 9500, 9999));
        started.getApplication().start();
        return started;
    }

    /**
     * Opens a connection and sends a write with the first half of its body: a request that the server is handling
     * until the test sends the rest.
     */
    private Socket requestWithHalfItsBody() throws Exception {
        URI uri = URI.create(server.testURL("/data/provisionagreement/inflight?sync=true"));
        Socket socket = new Socket(uri.getHost(), uri.getPort());
        OutputStream out = socket.getOutputStream();
        out.write(("PUT " + uri.getRawPath() + "?" + uri.getRawQuery() + " HTTP/1.1\r\nHost: " + uri.getAuthority()
                + "\r\nContent-Type: application/json\r\nContent-Length: " + BODY_BYTES.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(BODY_BYTES, 0, HALF);
        out.flush();
        // the request has reached its handler: no part of the server tells it, so the test gives it the time
        await().pollDelay(Duration.ofMillis(300)).until(() -> true);
        return socket;
    }

    private int put(String uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.testURL(uri))).timeout(Duration.ofSeconds(60))
                .PUT(HttpRequest.BodyPublishers.ofString(BODY)).build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
