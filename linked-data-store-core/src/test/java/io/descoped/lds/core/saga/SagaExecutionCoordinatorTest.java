package io.descoped.lds.core.saga;

import io.descoped.lds.test.ConfigurationOverride;
import io.descoped.lds.test.client.TestClient;
import io.descoped.lds.test.server.TestServer;
import io.descoped.lds.test.server.TestServerListener;
import no.cantara.saga.execution.SagaExecutionTraversalContext;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogAlreadyAquiredByOtherOwnerException;
import no.cantara.sagalog.SagaLogOwner;
import no.cantara.sagalog.SagaLogPool;
import org.testng.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
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
            assertTrue(sagaLog.readIncompleteSagas().anyMatch(_ -> true)); // check that saga-log is not empty
            assertFalse(deadSagaLog.readIncompleteSagas().anyMatch(_ -> true)); // check that dead-saga-log is empty
        } finally {
            pool.releaseOwnership(owner, sagaLog.id());
        }

        client.put("/data/provisionagreement/m1?sync=true", "{\"name\":\"saga-handoff-test-data 2\",\"contacts\":[]}").expect201Created();

        pool.tryTakeOwnership(owner, sagaLog.id());
        try {
            assertFalse(sagaLog.readIncompleteSagas().anyMatch(_ -> true)); // check that saga-log is empty
            assertFalse(deadSagaLog.readIncompleteSagas().anyMatch(_ -> true)); // check that dead-saga-log is empty
        } finally {
            pool.releaseOwnership(owner, sagaLog.id());
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
                assertTrue(sagaLog.readIncompleteSagas().anyMatch(_ -> true)); // check that saga-log is not empty
                assertFalse(deadSagaLog.readIncompleteSagas().anyMatch(_ -> true)); // check that dead-saga-log is empty
            } finally {
                pool.releaseOwnership(owner, sagaLog.id());
            }

            SagaExecutionCoordinator.OwnedSagaLog otherSagaLog = sec.acquireCleanSagaLog(nopAction(), failOnce()); // should move the entries to dead-saga log
            try {
                Assert.assertEquals(otherSagaLog.sagaLog(), sagaLog);
                sagaLog = otherSagaLog.sagaLog();

                assertFalse(sagaLog.readIncompleteSagas().anyMatch(_ -> true)); // check that saga-log is empty
                assertTrue(deadSagaLog.readIncompleteSagas().anyMatch(_ -> true)); // check that dead-saga-log is not empty
            } finally {
                pool.releaseOwnership(otherSagaLog.owner(), otherSagaLog.id());
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
        SagaLogOwner owner = new SagaLogOwner("core-sec-test");
        SagaLog sagaLog = pool.tryAcquire(owner, 10, TimeUnit.SECONDS);
        try {
            Assert.assertEquals(SlowTruncateSagaLogInitializer.ACQUIRED_WITH_ENTRIES.get(), 0, "saga logs handed out with entries in them");
            assertFalse(sagaLog.readIncompleteSagas().anyMatch(_ -> true)); // released, so truncated
            assertFalse(pool.connect(sec.deadSagaLogId).readIncompleteSagas().anyMatch(_ -> true));
            assertTrue(sec.executionIdBySagaLogId.isEmpty());
        } finally {
            pool.releaseOwnership(owner, sagaLog.id());
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
                SagaExecutionCoordinator.OwnedSagaLog acquired = sec.acquireCleanSagaLog(nopAction(), nopAction());
                pool.releaseOwnership(acquired.owner(), acquired.id());
                Assert.fail("Acquired a saga log that cannot be emptied");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().startsWith("Unable to acquire clean saga-log"), e.getMessage());
            }
        } finally {
            SlowTruncateSagaLogInitializer.TRUNCATES.set(true);
        }
        SagaLogOwner owner = new SagaLogOwner("core-sec-test");
        SagaLog sagaLog = pool.tryAcquire(owner, 2, TimeUnit.SECONDS);
        Assert.assertNotNull(sagaLog, "the saga log was not released");
        try {
            sagaLog.truncate().join();
        } finally {
            pool.releaseOwnership(owner, sagaLog.id());
        }
    }

    /**
     * Recovery at start and by the timer takes a saga log, runs its sagas and truncates it. When that truncate
     * fails the recovery fails, and the log must be given back: a log that stays taken is lost to every write
     * after, and to every later recovery.
     */
    @Test
    @ConfigurationOverride({
            "saga.recovery.enabled", "false",
            "sagalog.provider", "io.descoped.lds.core.saga.SlowTruncateSagaLogInitializer",
            "saga.number-of-logs", "1",
            "saga.commands.enabled", "true"
    })
    public void thatASagaLogIsReleasedWhenItsTruncateFailsAfterRecovery() throws Exception {
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        SagaLogPool pool = sec.getSagaLogPool();
        client.put("/data/provisionagreement/t1?sync=true&saga=failAfter%20S", "{\"name\":\"unfinished\",\"contacts\":[]}").expectAnyOf(500);
        SlowTruncateSagaLogInitializer.FAILING_TRUNCATES.set(1);
        try {
            sec.completeLocalIncompleteSagas(sec.getRecoveryThreadPool()).join();
            Assert.fail("The truncate failed and the recovery did not");
        } catch (CompletionException e) {
            assertTrue(String.valueOf(e.getCause()).contains("truncate failed"), String.valueOf(e.getCause()));
        } finally {
            SlowTruncateSagaLogInitializer.FAILING_TRUNCATES.set(0);
        }
        client.get("/data/provisionagreement/t1").expect200Ok(); // the saga itself was recovered

        SagaLogOwner owner = new SagaLogOwner("core-sec-test");
        SagaLog sagaLog = pool.tryAcquire(owner, 2, TimeUnit.SECONDS);
        Assert.assertNotNull(sagaLog, "the saga log was not released");
        try {
            sagaLog.truncate().join();
        } finally {
            pool.releaseOwnership(owner, sagaLog.id());
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
        pool.releaseOwnership(owner, sagaLog.id());
        sec.executionIdBySagaLogId.put(sagaLog.id(), "another-execution");
        try {
            client.put("/data/provisionagreement/h1?sync=true", "{\"name\":\"held\",\"contacts\":[]}").expectAnyOf(500);

            SagaLog released = pool.tryAcquire(owner, 2, TimeUnit.SECONDS);
            Assert.assertNotNull(released, "the saga log was not released");
            pool.releaseOwnership(owner, released.id());
            Assert.assertEquals(sec.executionIdBySagaLogId.get(sagaLog.id()), "another-execution");
        } finally {
            sec.executionIdBySagaLogId.remove(sagaLog.id());
        }
    }

    /**
     * A saga that the thread pool does not take. Since saga 2.1.1 the library fails such a saga through its
     * futures, where it threw before. Its log and its permit must be released all the same: a refused write that
     * kept them would cost one of each, until no write could start.
     */
    @Test
    @ConfigurationOverride({
            "saga.recovery.enabled", "false",
            "sagalog.provider", "no.cantara.sagalog.memory.MemorySagaLogInitializer",
            "saga.number-of-logs", "2"
    })
    public void thatASagaTheThreadPoolRefusesReleasesItsLogAndItsPermit() {
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        SagaLogPool pool = sec.getSagaLogPool();
        int permits = sec.semaphore.availablePermits();
        sec.getThreadPool().shutdown(); // refuses every saga from here on; this server is used by this test only

        for (int i = 0; i < 3; i++) { // more writes than there are logs
            client.put("/data/provisionagreement/r" + i + "?sync=true", "{\"name\":\"refused " + i + "\",\"contacts\":[]}").expectAnyOf(500);
        }

        Assert.assertEquals(sec.semaphore.availablePermits(), permits);
        assertTrue(sec.executionIdBySagaLogId.isEmpty());
        SagaLogOwner owner = new SagaLogOwner("core-sec-test");
        List<SagaLog> acquired = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                SagaLog sagaLog = pool.tryAcquire(owner);
                Assert.assertNotNull(sagaLog, "saga log " + i + " was not released");
                acquired.add(sagaLog);
            }
        } finally {
            for (SagaLog sagaLog : acquired) {
                pool.releaseOwnership(owner, sagaLog.id());
            }
        }
    }

    /**
     * The coordinator takes a saga log as an owner and keeps the owner with the log, and the pool gives the log
     * back for that owner only: another caller cannot release a log that a saga is using.
     */
    @Test
    @ConfigurationOverride({
            "saga.recovery.enabled", "false",
            "sagalog.provider", "no.cantara.sagalog.memory.MemorySagaLogInitializer",
            "saga.number-of-logs", "1"
    })
    public void thatASagaLogIsReleasedForTheOwnerThatTookItOnly() {
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        SagaLogPool pool = sec.getSagaLogPool();
        SagaExecutionCoordinator.OwnedSagaLog owned = sec.acquireCleanSagaLog(nopAction(), nopAction());
        try {
            try {
                pool.releaseOwnership(new SagaLogOwner("someone-else"), owned.id());
                Assert.fail("Another owner released the saga log");
            } catch (SagaLogAlreadyAquiredByOtherOwnerException _) {
                // the log is still the coordinator's
            }
            Assert.assertNull(pool.tryAcquire(new SagaLogOwner("core-sec-test")), "the only saga log was given out twice");
        } finally {
            pool.releaseOwnership(owned.owner(), owned.id());
        }
        client.put("/data/provisionagreement/o1?sync=true", "{\"name\":\"owner\",\"contacts\":[]}").expect201Created();
    }

    /**
     * Recovery that cannot start a saga of a log, here a saga this instance does not know, fails. It must give the
     * log back: a log that stays taken is lost to every write after, and to every later recovery.
     */
    @Test
    @ConfigurationOverride({
            "saga.recovery.enabled", "false",
            "sagalog.provider", "no.cantara.sagalog.memory.MemorySagaLogInitializer",
            "saga.number-of-logs", "1"
    })
    public void thatASagaLogIsReleasedWhenRecoveryCannotStartItsSaga() throws Exception {
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        SagaLogPool pool = sec.getSagaLogPool();
        SagaLogOwner owner = new SagaLogOwner("core-sec-test");
        SagaLog sagaLog = pool.tryAcquire(owner);
        try {
            sagaLog.write(sagaLog.builder().startSaga("an-execution", "a-saga-nobody-knows", "{}")).join();
        } finally {
            pool.releaseOwnership(owner, sagaLog.id());
        }
        try {
            sec.completeLocalIncompleteSagas(sec.getRecoveryThreadPool()).join();
            Assert.fail("The recovery of a saga nobody knows did not fail");
        } catch (CompletionException e) {
            assertTrue(String.valueOf(e.getCause()).contains("is not present in sagaRepository"), String.valueOf(e.getCause()));
        } finally {
            SagaLog released = pool.tryAcquire(owner, 2, TimeUnit.SECONDS);
            Assert.assertNotNull(released, "the saga log was not released");
            try {
                released.truncate().join(); // the next test gets it empty
            } finally {
                pool.releaseOwnership(owner, released.id());
            }
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
        return _ -> {
        };
    }
}