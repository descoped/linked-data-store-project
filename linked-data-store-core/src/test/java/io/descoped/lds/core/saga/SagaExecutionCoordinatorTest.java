package io.descoped.lds.core.saga;

import io.descoped.lds.test.ConfigurationOverride;
import io.descoped.lds.test.client.TestClient;
import io.descoped.lds.test.server.TestServer;
import io.descoped.lds.test.server.TestServerListener;
import no.cantara.saga.execution.SagaExecutionTraversalContext;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogOwner;
import no.cantara.sagalog.SagaLogPool;
import org.testng.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Listeners(TestServerListener.class)
public class SagaExecutionCoordinatorTest {

    @Inject
    TestClient client;

    @Inject
    TestServer server;


    @Test
    @ConfigurationOverride({
            "saga.recovery.enabled", "false",
            "sagalog.provider", "no.cantara.sagalog.file.FileSagaLogInitializer",
            "saga.number-of-logs", "1",
            "saga.commands.enabled", "true"
    })
    public void thatSagaRecoveryWorksWhenAllLogsAreClean() {
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        {
            // force a visible non-local saga-log in cluster to trigger more code-paths
            SagaLogPool pool = sec.getSagaLogPool();
            SagaLog otherClusterIdLog = pool.connect(pool.idFor("other", "x"));
            pool.remove(otherClusterIdLog.id());
        }
        sec.completeClusterWideIncompleteSagas(server.getApplication().getSec().recoveryThreadPool);
    }

    @Test
    @ConfigurationOverride({
            "saga.recovery.enabled", "false",
            "sagalog.provider", "no.cantara.sagalog.memory.MemorySagaLogInitializer",
            "saga.number-of-logs", "1",
            "saga.commands.enabled", "true"
    })
    public void testThatInstanceLocalSagaExecutionIsRecoveredOnAcquireClean() {
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        SagaLogPool pool = sec.getSagaLogPool();
        SagaLog deadSagaLog = pool.connect(sec.deadSagaLogId);
        deadSagaLog.truncate();

        client.put("/data/provisionagreement/m1?sync=true&saga=failAfter%20S", "{\"name\":\"saga-handoff-test-data 1\",\"contacts\":[]}").expectAnyOf(500);

        // check state
        SagaLogOwner owner = new SagaLogOwner("core-sec-test");
        SagaLog sagaLog = pool.tryAcquire(owner);
        try {
            assertTrue(sagaLog.readIncompleteSagas().anyMatch(e -> true)); // check that saga-log is not empty
            assertFalse(deadSagaLog.readIncompleteSagas().anyMatch(e -> true)); // check that dead-saga-log is empty
        } finally {
            pool.release(sagaLog.id());
        }

        client.put("/data/provisionagreement/m1?sync=true", "{\"name\":\"saga-handoff-test-data 2\",\"contacts\":[]}").expect201Created();

        pool.tryTakeOwnership(owner, sagaLog.id());
        try {
            assertFalse(sagaLog.readIncompleteSagas().anyMatch(e -> true)); // check that saga-log is empty
            assertFalse(deadSagaLog.readIncompleteSagas().anyMatch(e -> true)); // check that dead-saga-log is empty
        } finally {
            pool.releaseOwnership(sagaLog.id());
        }
    }

    @Test
    @ConfigurationOverride({
            "saga.recovery.enabled", "false",
            "sagalog.provider", "no.cantara.sagalog.memory.MemorySagaLogInitializer",
            "saga.number-of-logs", "1",
            "saga.commands.enabled", "true"
    })
    public void testThatInstanceLocalSagaEntriesAreMovedToDeadSagaLogOnAcquireCleanWhenSagaExecutionFailureRepeats() {
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        SagaLogPool pool = sec.getSagaLogPool();
        SagaLog deadSagaLog = pool.connect(sec.deadSagaLogId);
        deadSagaLog.truncate();

        client.put("/data/provisionagreement/m1?sync=true&saga=failAfter%20S", "{\"name\":\"saga-handoff-test-data\",\"contacts\":[]}").expectAnyOf(500);

        try {
            // check state
            SagaLogOwner owner = new SagaLogOwner("core-sec-test");
            SagaLog sagaLog = pool.tryAcquire(owner);
            try {
                assertTrue(sagaLog.readIncompleteSagas().anyMatch(e -> true)); // check that saga-log is not empty
                assertFalse(deadSagaLog.readIncompleteSagas().anyMatch(e -> true)); // check that dead-saga-log is empty
            } finally {
                pool.release(sagaLog.id());
            }

            SagaLog otherSagaLog = sec.acquireCleanSagaLog(nopAction(), failOnce()); // should move the entries to dead-saga log
            try {
                Assert.assertEquals(otherSagaLog, sagaLog);
                sagaLog = otherSagaLog;

                assertFalse(sagaLog.readIncompleteSagas().anyMatch(e -> true)); // check that saga-log is empty
                assertTrue(deadSagaLog.readIncompleteSagas().anyMatch(e -> true)); // check that dead-saga-log is not empty
            } finally {
                pool.release(otherSagaLog.id());
            }
        } finally {
            deadSagaLog.truncate();
        }
    }

    /**
     * One saga log, a truncate that takes time, and two clients: when one client's saga completes, the other is
     * waiting for the log. It must get it empty, so the log may be released only when its truncate has finished.
     * Otherwise the entries of the finished saga are taken for an unfinished one, and the late truncate removes the
     * entries of the saga that has the log by then.
     */
    @Test
    @ConfigurationOverride({
            "saga.recovery.enabled", "false",
            "sagalog.provider", "io.descoped.lds.core.saga.SlowTruncateSagaLogInitializer",
            "saga.number-of-logs", "1"
    })
    public void thatASagaLogIsReleasedOnlyWhenItsTruncateHasFinished() throws Exception {
        SlowTruncateSagaLogInitializer.ACQUIRED_WITH_ENTRIES.set(0);
        ExecutorService clients = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> statuses = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String uri = "/data/provisionagreement/t" + i + "?sync=true";
                String body = "{\"name\":\"truncate-then-release " + i + "\",\"contacts\":[]}";
                statuses.add(clients.submit(() -> client.put(uri, body).response().statusCode()));
            }
            for (Future<Integer> status : statuses) {
                Assert.assertEquals(status.get(60, TimeUnit.SECONDS).intValue(), 201);
            }
        } finally {
            clients.shutdownNow();
        }
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        SagaLogPool pool = sec.getSagaLogPool();
        SagaLog sagaLog = pool.tryAcquire(new SagaLogOwner("core-sec-test"), 10, TimeUnit.SECONDS);
        try {
            Assert.assertEquals(SlowTruncateSagaLogInitializer.ACQUIRED_WITH_ENTRIES.get(), 0, "saga logs handed out with entries in them");
            assertFalse(sagaLog.readIncompleteSagas().anyMatch(e -> true)); // released, so truncated
            assertFalse(pool.connect(sec.deadSagaLogId).readIncompleteSagas().anyMatch(e -> true));
            assertTrue(sec.executionIdBySagaLogId.isEmpty());
        } finally {
            pool.release(sagaLog.id());
        }
    }

    /**
     * A saga log that cannot be emptied is acquired, recovered, acquired again and given up. Giving it up must
     * release it: a log that stays acquired is lost to every saga after, and with all logs lost every write waits
     * for a log and fails.
     */
    @Test
    @ConfigurationOverride({
            "saga.recovery.enabled", "false",
            "sagalog.provider", "io.descoped.lds.core.saga.SlowTruncateSagaLogInitializer",
            "saga.number-of-logs", "1",
            "saga.commands.enabled", "true"
    })
    public void thatASagaLogIsReleasedWhenItCannotBeAcquiredClean() throws Exception {
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        SagaLogPool pool = sec.getSagaLogPool();
        SlowTruncateSagaLogInitializer.TRUNCATES.set(false);
        try {
            client.put("/data/provisionagreement/u1?sync=true&saga=failAfter%20S", "{\"name\":\"unfinished\",\"contacts\":[]}").expectAnyOf(500);
            try {
                SagaLog acquired = sec.acquireCleanSagaLog(nopAction(), nopAction());
                pool.release(acquired.id());
                Assert.fail("Acquired a saga log that cannot be emptied");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().startsWith("Unable to acquire clean saga-log"), e.getMessage());
            }
        } finally {
            SlowTruncateSagaLogInitializer.TRUNCATES.set(true);
        }
        SagaLog sagaLog = pool.tryAcquire(new SagaLogOwner("core-sec-test"), 2, TimeUnit.SECONDS);
        Assert.assertNotNull(sagaLog, "the saga log was not released");
        try {
            sagaLog.truncate().join();
        } finally {
            pool.release(sagaLog.id());
        }
    }

    /**
     * A saga log that the coordinator still holds for another execution cannot be used; the write fails, and the
     * log is released and the other execution's entry is left alone.
     */
    @Test
    @ConfigurationOverride({
            "saga.recovery.enabled", "false",
            "sagalog.provider", "no.cantara.sagalog.memory.MemorySagaLogInitializer",
            "saga.number-of-logs", "1"
    })
    public void thatASagaLogIsReleasedWhenItIsHeldForAnotherExecution() throws Exception {
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        SagaLogPool pool = sec.getSagaLogPool();
        SagaLogOwner owner = new SagaLogOwner("core-sec-test");
        SagaLog sagaLog = pool.tryAcquire(owner);
        pool.release(sagaLog.id());
        sec.executionIdBySagaLogId.put(sagaLog.id(), "another-execution");
        try {
            client.put("/data/provisionagreement/h1?sync=true", "{\"name\":\"held\",\"contacts\":[]}").expectAnyOf(500);

            SagaLog released = pool.tryAcquire(owner, 2, TimeUnit.SECONDS);
            Assert.assertNotNull(released, "the saga log was not released");
            pool.release(released.id());
            Assert.assertEquals(sec.executionIdBySagaLogId.get(sagaLog.id()), "another-execution");
        } finally {
            sec.executionIdBySagaLogId.remove(sagaLog.id());
        }
    }

    private Consumer<SagaExecutionTraversalContext> failOnce() {
        AtomicBoolean failedOnce = new AtomicBoolean();
        return c -> {
            if (!failedOnce.compareAndSet(false, true)) {
                return;
            }
            throw new RuntimeException(String.format("Forced repeat error initiated from test at sagaNode %s", c.getNode().id));
        };
    }

    private Consumer<SagaExecutionTraversalContext> nopAction() {
        return c -> {
        };
    }
}