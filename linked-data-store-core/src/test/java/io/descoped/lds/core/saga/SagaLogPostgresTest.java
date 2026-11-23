package io.descoped.lds.core.saga;

import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.core.UndertowApplication;
import io.descoped.lds.test.server.TestServer;
import io.descoped.lds.test.server.TestServerListener;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogId;
import no.cantara.sagalog.SagaLogOwner;
import no.cantara.sagalog.SagaLogPool;
import org.awaitility.Awaitility;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write path with its saga log in PostgreSQL 18 (a container, so the tests need Docker): a saga log in a
 * database takes time to write and to truncate, which the memory and file saga logs of the other tests do not.
 * Every test starts its servers itself, with a cluster name of its own, so the tests share no saga log.
 */
public class SagaLogPostgresTest {

    private static final String BODY = "{\"name\":\"saga log in postgres\",\"contacts\":[]}";

    /** A write waits for its saga without a limit; a saga that never completes must fail the test, not hang it. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<TestServer> servers = new ArrayList<>();
    private PostgreSQLContainer postgres;

    @BeforeClass
    @SuppressWarnings("resource") // the container is kept in a field and stopped after the class
    public void startPostgres() {
        postgres = new PostgreSQLContainer("postgres:18-alpine")
                .withDatabaseName("sagalog")
                .withUsername("lds")
                .withPassword("lds");
        postgres.start();
        LoopbackPorts.check(postgres);
    }

    @AfterClass(alwaysRun = true)
    public void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @AfterMethod(alwaysRun = true)
    public void stopServers() {
        for (TestServer server : List.copyOf(servers)) {
            stop(server);
        }
    }

    @Test
    public void concurrentWritesAllSucceedAndLeaveTheSagaLogsEmpty() throws Exception {
        TestServer server = start("concurrent", "01", "saga.recovery.enabled", "false", "saga.number-of-logs", "5");
        ExecutorService clients = Executors.newFixedThreadPool(16);
        try {
            List<Future<Integer>> statuses = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                String uri = "/data/provisionagreement/c" + i + "?sync=true";
                statuses.add(clients.submit(() -> put(server, uri)));
            }
            for (Future<Integer> status : statuses) {
                assertThat(status.get(60, TimeUnit.SECONDS)).isEqualTo(201);
            }
        } finally {
            clients.shutdownNow();
        }
        assertThat(entries(server)).isZero();
        assertThat(deadEntries(server)).isZero();
        assertThat(server.getApplication().getSec().executionIdBySagaLogId).isEmpty();
    }

    @Test
    public void anUnfinishedSagaIsRecoveredByTheNextWrite() throws Exception {
        TestServer server = start("next-write", "01", "saga.recovery.enabled", "false", "saga.number-of-logs", "1");
        String[] failures = {"failAfter%20S", "failBefore%20persistence", "failAfter%20persistence", "failBefore%20E"};
        for (int i = 0; i < failures.length; i++) {
            assertThat(put(server, "/data/provisionagreement/u" + i + "?sync=true&saga=" + failures[i])).as(failures[i]).isEqualTo(500);
            assertThat(entries(server)).as("entries left by %s", failures[i]).isPositive();

            assertThat(put(server, "/data/provisionagreement/n" + i + "?sync=true")).as("the write after %s", failures[i]).isEqualTo(201);

            assertThat(get(server, "/data/provisionagreement/u" + i)).as("the document of the recovered saga, %s", failures[i]).isEqualTo(200);
            assertThat(get(server, "/data/provisionagreement/n" + i)).isEqualTo(200);
            assertThat(entries(server)).as("entries after the recovery of %s", failures[i]).isZero();
        }
        assertThat(deadEntries(server)).isZero();
    }

    @Test
    public void anUnfinishedSagaIsRecoveredWhenTheServerStartsAgain() throws Exception {
        TestServer first = start("restart", "01", "saga.recovery.enabled", "false", "saga.number-of-logs", "1");
        assertThat(put(first, "/data/provisionagreement/r?sync=true&saga=failAfter%20S")).isEqualTo(500);
        assertThat(entries(first)).isPositive();
        stop(first);

        // the documents are in memory, so the second server has this one only if it ran the saga
        TestServer second = start("restart", "01", "saga.recovery.enabled", "true", "saga.number-of-logs", "1");
        await(Duration.ofSeconds(20), () -> get(second, "/data/provisionagreement/r") == 200);
        await(Duration.ofSeconds(20), () -> entries(second) == 0);
        assertThat(deadEntries(second)).isZero();
    }

    @Test
    public void anUnfinishedSagaOfAnotherInstanceIsRecoveredByTheCluster() throws Exception {
        TestServer first = start("cluster", "01", "saga.recovery.enabled", "false", "saga.number-of-logs", "1");
        assertThat(put(first, "/data/provisionagreement/x?sync=true&saga=failAfter%20S")).isEqualTo(500);
        assertThat(entries(first)).isPositive();
        stop(first);

        TestServer second = start("cluster", "02", "saga.recovery.enabled", "true", "saga.number-of-logs", "1",
                "saga.recovery.interval.seconds.min", "1", "saga.recovery.interval.seconds.max", "1");
        await(Duration.ofSeconds(30), () -> get(second, "/data/provisionagreement/x") == 200);
        // the recovery is done only when the log of the instance that left is emptied and deleted; until then the
        // saga would be run again on every pass
        SagaLogPool pool = second.getApplication().getSec().getSagaLogPool();
        await(Duration.ofSeconds(30), () -> pool.clusterWideLogIds().stream().noneMatch(logId -> "01".equals(logId.getClusterInstanceId())));
        assertThat(put(second, "/data/provisionagreement/y?sync=true")).isEqualTo(201);
        assertThat(deadEntries(second)).isZero();
    }

    /**
     * A write whose transaction log step ran, but whose End entry of that step never reached the saga log (the
     * process stopped between the two), is finished by the next write: the recovery runs the step again, then
     * the rest of the saga. The transaction log is PostgreSQL, as in the compose file, in the same container; search
     * is off.
     */
    @Test
    public void aWriteWhoseTransactionLogStepEndedUnrecordedIsFinishedByTheNextWrite() throws Exception {
        TestServer server = start("lost-entry", "01", "sagalog.provider", LostEntrySagaLogInitializer.class.getName(),
                "saga.recovery.enabled", "false", "saga.number-of-logs", "1",
                "graphql.search.enabled", "false", // the index queue of a PostgreSQL transaction log is a module of its own
                "txlog.rawdata.provider", "postgres",
                "txlog.config.postgres.driver.host", postgres.getHost(),
                "txlog.config.postgres.driver.port", String.valueOf(postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)),
                "txlog.config.postgres.driver.user", postgres.getUsername(),
                "txlog.config.postgres.driver.password", postgres.getPassword(),
                "txlog.config.postgres.driver.database", postgres.getDatabaseName());
        LostEntrySagaLogInitializer.LOSE_END_OF.set("txlog");

        assertThat(put(server, "/data/provisionagreement/lost?sync=true")).as("the write whose End was lost").isEqualTo(500);
        assertThat(LostEntrySagaLogInitializer.LOSE_END_OF.get()).as("the End entry was lost").isNull();
        assertThat(entries(server)).as("the unfinished saga in the log").isPositive();

        assertThat(put(server, "/data/provisionagreement/next?sync=true")).as("the next write").isEqualTo(201);
        await(Duration.ofSeconds(30), () -> entries(server) == 0);

        assertThat(get(server, "/data/provisionagreement/lost")).as("the write that was recovered").isEqualTo(200);
        assertThat(deadEntries(server)).as("entries moved to the dead-letter log").isZero();
    }

    /**
     * Starts a server with its saga log in the container.
     *
     * @param cluster    the cluster name; with the instance id it names the saga logs
     * @param instanceId the id of this server in the cluster
     * @param overrides  further settings, as keys and values
     */
    private TestServer start(String cluster, String instanceId, String... overrides) {
        List<String> values = new ArrayList<>(List.of(
                "sagalog.provider", "no.cantara.sagalog.postgres.PostgresSagaLogInitializer",
                "sagalog.config.cluster.owner", "lds",
                "sagalog.config.cluster.name", cluster,
                "sagalog.config.cluster.instance-id", instanceId,
                "sagalog.config.postgres.driver.host", postgres.getHost(),
                "sagalog.config.postgres.driver.port", String.valueOf(postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)),
                "sagalog.config.postgres.driver.user", postgres.getUsername(),
                "sagalog.config.postgres.driver.password", postgres.getPassword(),
                "sagalog.config.postgres.driver.database", postgres.getDatabaseName(),
                // five logs, ten that recovery may open at once, one for the pool's own queries
                "sagalog.config.connection-pool.max-size", "16",
                "saga.commands.enabled", "true"));
        values.addAll(List.of(overrides));
        DynamicConfiguration.Builder builder = DynamicConfiguration.builder()
                .classpath(UndertowApplication.DEFAULT_CONFIGURATION_RESOURCE_PATH)
                .classpath("application_test.properties");
        for (int i = 0; i < values.size(); i += 2) {
            builder.value(values.get(i), values.get(i + 1)); // a later value of a key is the one that counts
        }
        DynamicConfiguration configuration = builder.build();
        TestServer server = new TestServer(configuration, TestServerListener.findFreePort(new Random(), 9500, 9999));
        servers.add(server);
        server.getApplication().start();
        return server;
    }

    private void stop(TestServer server) {
        if (servers.remove(server)) {
            server.getApplication().stop();
        }
    }

    private int put(TestServer server, String uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.testURL(uri))).timeout(REQUEST_TIMEOUT).PUT(HttpRequest.BodyPublishers.ofString(BODY)).build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int get(TestServer server, String uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.testURL(uri))).timeout(REQUEST_TIMEOUT).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** The number of entries in the saga logs of the server's own instance. */
    private static long entries(TestServer server) throws Exception {
        SagaLogPool pool = server.getApplication().getSec().getSagaLogPool();
        long entries = 0;
        for (SagaLogId logId : pool.instanceLocalLogIds()) {
            SagaLogOwner owner = new SagaLogOwner("saga-log-postgres-test");
            SagaLog sagaLog = pool.tryTakeOwnership(owner, logId, 10, TimeUnit.SECONDS);
            assertThat(sagaLog).as("ownership of %s", logId).isNotNull();
            try {
                entries += sagaLog.readIncompleteSagas().count();
            } finally {
                pool.releaseOwnership(owner, logId);
            }
        }
        return entries;
    }

    private static long deadEntries(TestServer server) {
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        return sec.getSagaLogPool().connect(sec.deadSagaLogId).readIncompleteSagas().count();
    }

    private static void await(Duration atMost, Callable<Boolean> condition) {
        Awaitility.await().atMost(atMost).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(100)).until(condition);
    }
}
