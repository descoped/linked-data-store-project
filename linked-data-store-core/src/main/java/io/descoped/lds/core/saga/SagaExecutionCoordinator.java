package io.descoped.lds.core.saga;

import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.rawdata.api.RawdataIds;
import no.cantara.concurrent.futureselector.SelectableFuture;
import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.SagaExecution;
import no.cantara.saga.execution.SagaExecutionTraversalContext;
import no.cantara.saga.execution.SagaHandoffControl;
import no.cantara.saga.execution.SagaHandoffResult;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogAlreadyAquiredByOtherOwnerException;
import no.cantara.sagalog.SagaLogBusyException;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import no.cantara.sagalog.SagaLogId;
import no.cantara.sagalog.SagaLogOwner;
import no.cantara.sagalog.SagaLogPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.groupingBy;

public class SagaExecutionCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(SagaExecutionCoordinator.class);

    static final Pattern deadLetterSagaPattern = Pattern.compile("dead-saga");

    /**
     * A saga log and the owner that took it from the pool. The pool releases a log for its owner only, so the two
     * stay together from the acquire to the release, which is often done by another thread.
     */
    record OwnedSagaLog(SagaLog sagaLog, SagaLogOwner owner) {
        SagaLogId id() {
            return sagaLog.id();
        }
    }

    /* The owner a thread takes a saga log as. */
    private static SagaLogOwner ownerOfThisThread() {
        return new SagaLogOwner("Thread::" + Thread.currentThread().getName());
    }

    final AtomicReference<UUID> previousTxId = new AtomicReference<>(RawdataIds.generate());

    final int numberOfSagaLogs;
    final SagaLogPool sagaLogPool;
    final SagaLogId deadSagaLogId;
    final Map<SagaLogId, String> executionIdBySagaLogId = new ConcurrentHashMap<>();
    /** The completion of every saga that is running: started by a write or by recovery, and not completed. */
    final Set<CompletableFuture<SagaHandoffResult>> runningSagas = ConcurrentHashMap.newKeySet();
    final boolean sagaCommandsEnabled;

    final SagaRepository sagaRepository;
    final SagasObserver sagasObserver;
    final SelectableThreadPoolExecutor threadPool;
    final Semaphore semaphore;
    final ThreadPoolWatchDog threadPoolWatchDog;
    final ExecutorService recoveryThreadPool;

    public SagaExecutionCoordinator(SagaLogPool sagaLogPool, int numberOfSagaLogs, SagaRepository sagaRepository, SagasObserver sagasObserver, SelectableThreadPoolExecutor threadPool, boolean sagaCommandsEnabled, ExecutorService recoveryThreadPool) {
        this.sagaLogPool = sagaLogPool;
        this.numberOfSagaLogs = numberOfSagaLogs;
        this.sagaCommandsEnabled = sagaCommandsEnabled;
        this.recoveryThreadPool = recoveryThreadPool;
        this.deadSagaLogId = sagaLogPool.idFor(sagaLogPool.getLocalClusterInstanceId(), "dead-saga"); // do not register

        if (!deadLetterSagaPattern.matcher(deadSagaLogId.getLogName()).matches()) {
            throw new IllegalStateException(String.format("Unable to match dead-letter-saga log %s with pattern: %s", deadSagaLogId, deadLetterSagaPattern.pattern()));
        }
        for (int i = 0; i < numberOfSagaLogs; i++) {
            SagaLogId logId = sagaLogPool.registerInstanceLocalIdFor(String.format("%02d", i));
            if (deadLetterSagaPattern.matcher(logId.getLogName()).matches()) {
                throw new IllegalStateException(String.format("Unwanted match to of log %s with registered dead-letter-saga pattern: %s", logId, deadLetterSagaPattern.pattern()));
            }
        }
        this.sagaRepository = sagaRepository;
        this.sagasObserver = sagasObserver;
        this.threadPool = threadPool;
        int maxNumberConcurrentSagaExecutions = (threadPool.getMaximumPoolSize() + threadPool.getQueue().remainingCapacity()) / 2;
        this.semaphore = new Semaphore(maxNumberConcurrentSagaExecutions);
        threadPoolWatchDog = new ThreadPoolWatchDog();
    }

    public void startThreadpoolWatchdog() {
        threadPoolWatchDog.start();
    }

    public SagaLogPool getSagaLogPool() {
        return sagaLogPool;
    }

    public SelectableThreadPoolExecutor getThreadPool() {
        return threadPool;
    }

    public SelectableFuture<SagaHandoffResult> handoff(boolean sync, AdapterLoader adapterLoader, Saga saga, SagaInput sagaInput, Map<String, List<SagaCommand>> commandsByNodeId) {
        return handoff(sync, adapterLoader, saga, sagaInput, commandsByNodeId, _ -> {
        });
    }

    /**
     * Starts a saga, as {@link #handoff(boolean, AdapterLoader, Saga, SagaInput, Map)} does, and gives the caller the
     * completion of the saga when it has started, also when the caller does not wait for it ({@code sync} false).
     *
     * @param sync             whether the returned future completes when the saga has completed, or when it has
     *                         been written to its saga log
     * @param adapterLoader    the adapters of the steps
     * @param saga             the saga
     * @param sagaInput        the input of the saga
     * @param commandsByNodeId the commands of the saga admin parameters
     * @param whenStarted      given the completion of the saga, once it has started
     * @return the completion of the saga, or of its handoff
     */
    public SelectableFuture<SagaHandoffResult> handoff(boolean sync, AdapterLoader adapterLoader, Saga saga, SagaInput sagaInput,
                                                       Map<String, List<SagaCommand>> commandsByNodeId,
                                                       Consumer<CompletionStage<SagaHandoffResult>> whenStarted) {
        String executionId = sagaInput.txId();

        OwnedSagaLog owned = acquireCleanSagaLog(_ -> {
        }, _ -> {
        });
        SagaLog sagaLog = owned.sagaLog();
        AtomicBoolean sagaLogReleased = new AtomicBoolean(false);
        Runnable releaseSagaLog = () -> {
            if (sagaLogReleased.compareAndSet(false, true)) {
                try {
                    executionIdBySagaLogId.remove(sagaLog.id(), executionId); // not the entry of another execution
                } finally {
                    sagaLogPool.releaseOwnership(owned.owner(), sagaLog.id());
                }
            }
        };
        SagaHandoffControl handoffControl;
        try {
            executionIdBySagaLogId.compute(sagaLog.id(), (k, v) -> {
                if (v != null) {
                    throw new IllegalStateException(String.format("executionIdBySagaLogId with key %s is already associated with another executionId %s", k, v));
                }
                return executionId;
            });
            SagaExecution sagaExecution = new SagaExecution(sagaLog, threadPool, saga, adapterLoader);
            handoffControl = startSagaExecutionWithThrottling(sagaExecution, sagaInput, sagaLog, sagaCommandsEnabled ? commandsByNodeId : Collections.emptyMap(), releaseSagaLog);
        } catch (RuntimeException e) {
            releaseSagaLog.run(); // the saga was not started, so nothing else will release its log
            throw e;
        }

        track(handoffControl);
        sagasObserver.registerSaga(handoffControl);
        whenStarted.accept(handoffControl.getCompletionFuture());

        return sync ?
                handoffControl.getCompletionFuture() : // full saga-execution
                handoffControl.getHandoffFuture();     // first saga-log write
    }

    /**
     * A new transaction id: a UUIDv7 that is greater, in {@link RawdataIds#ORDER}, than every id this coordinator
     * gave before, also within one millisecond and when the clock goes back.
     */
    public UUID generateTxId() {
        return previousTxId.updateAndGet(RawdataIds::next);
    }

    private void track(SagaHandoffControl handoffControl) {
        CompletableFuture<SagaHandoffResult> completion = handoffControl.getCompletionFuture();
        runningSagas.add(completion);
        completion.whenComplete((_, _) -> runningSagas.remove(completion));
    }

    /**
     * Waits until every saga that is running has completed, or until the time is up. A saga that fails has
     * completed too. A saga has released its saga log when it has completed. Used when the application stops,
     * after it has stopped taking writes and before the thread pool of the sagas is shut down: a saga hands each of
     * its steps to that pool, and a pool that is shut down refuses them.
     *
     * @param timeout the longest time to wait
     * @param unit    the unit of the timeout
     * @return true when no saga is running
     */
    public boolean awaitRunningSagas(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            CompletableFuture<?>[] running = runningSagas.stream().filter(saga -> !saga.isDone()).toArray(CompletableFuture<?>[]::new);
            if (running.length == 0) {
                return true;
            }
            try {
                CompletableFuture.allOf(running).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (ExecutionException _) {
                // one of them failed; all of them have completed
            } catch (TimeoutException _) {
                return false;
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    OwnedSagaLog acquireCleanSagaLog(Consumer<SagaExecutionTraversalContext> forwardRecoveryPreAction, Consumer<SagaExecutionTraversalContext> forwardRecoveryPostAction) {
        return recursiveAcquireCleanSagaLog(new LinkedHashSet<>(), forwardRecoveryPreAction, forwardRecoveryPostAction);
    }

    private OwnedSagaLog recursiveAcquireCleanSagaLog(Set<SagaLogId> attemptedSagaLogIds, Consumer<SagaExecutionTraversalContext> forwardRecoveryPreAction, Consumer<SagaExecutionTraversalContext> forwardRecoveryPostAction) {
        SagaLogOwner owner = ownerOfThisThread();
        SagaLog sagaLog;
        try {
            sagaLog = sagaLogPool.tryAcquire(owner, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (sagaLog == null) {
            throw new IllegalStateException("Timeout. No available saga-logs, please check configuration.");
        }
        OwnedSagaLog owned = new OwnedSagaLog(sagaLog, owner);
        boolean handedOver = false; // to the caller, or to the recovery that releases it
        try {
            if (deadLetterSagaPattern.matcher(sagaLog.id().getLogName()).matches()) {
                throw new IllegalStateException("Dead-letter-saga acquired unintentionally");
            }
            if (sagaLog.readIncompleteSagas().noneMatch(_ -> true)) {
                handedOver = true;
                return owned; // sagaLog is empty, all is well
            }

            if (!attemptedSagaLogIds.add(sagaLog.id())) {
                throw new IllegalStateException(String.format("Unable to acquire clean saga-log. This is the second time saga-log-id %s was chosen.", sagaLog.id()));
            }

            // sagaLog contains at least one element
            asyncAttemptForwardRecoveryOrElseMoveToDeadLetter(owned, forwardRecoveryPreAction, forwardRecoveryPostAction); // will release sagaLog when done
            handedOver = true;
        } finally {
            if (!handedOver) {
                sagaLogPool.releaseOwnership(owner, sagaLog.id()); // or no saga could ever use this log again
            }
        }

        return recursiveAcquireCleanSagaLog(attemptedSagaLogIds, forwardRecoveryPreAction, forwardRecoveryPostAction);
    }

    CompletableFuture<Void> asyncAttemptForwardRecoveryOrElseMoveToDeadLetter(OwnedSagaLog owned, Consumer<SagaExecutionTraversalContext> preAction, Consumer<SagaExecutionTraversalContext> postAction) {
        SagaLog sagaLog = owned.sagaLog();
        return CompletableFuture.runAsync(() -> {
            CompletableFuture<Void> future;
            try {
                future = doCompleteSagaLog(owned, false, false, preAction, postAction);
            } catch (Throwable t) {
                future = CompletableFuture.failedFuture(t); // wrap
            }
            try {
                future.join(); // wait for completion and unwrap
            } catch (Throwable t) {
                LOG.warn("Failed to retry saga-executions from saga-log {}, moving all entries to dead-saga-log.", sagaLog.id(), t);
                moveAllEntriesToDeadLetter(sagaLog);
            } finally {
                sagaLogPool.releaseOwnership(owned.owner(), sagaLog.id());
            }
        }, recoveryThreadPool);
    }

    /* Moves the entries of the saga log to the dead-letter log. A failure is logged. */
    private void moveAllEntriesToDeadLetter(SagaLog sagaLog) {
        try {
            SagaLogOwner deadLetterOwner = ownerOfThisThread();
            SagaLog deadLetterSagaLog = sagaLogPool.tryTakeOwnership(deadLetterOwner, deadSagaLogId, 30, TimeUnit.SECONDS);
            if (deadLetterSagaLog == null) {
                throw new IllegalStateException(String.format("Timeout while attempting to take ownership of %s", deadSagaLogId));
            }
            try {
                moveAllEntries(sagaLog, deadLetterSagaLog).join();
                LOG.info("Moved all saga-log-entries from {} to {}", sagaLog.id(), deadSagaLogId);
            } finally {
                sagaLogPool.releaseOwnership(deadLetterOwner, deadSagaLogId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Error while attempting to move saga-log-entries from {} to dead-saga", sagaLog.id(), e);
        } catch (Throwable tx) {
            LOG.error("Error while attempting to move saga-log-entries from {} to dead-saga", sagaLog.id(), tx);
        }
    }

    CompletableFuture<Void> moveAllEntries(SagaLog from, SagaLog to) {
        // saga-log is not clean, move incomplete saga to recovery-log
        List<CompletableFuture<SagaLogEntry>> futures = new LinkedList<>();
        from.readIncompleteSagas().forEachOrdered(entry -> {
            SagaLogEntryBuilder deadLetterEntryBuilder = to.builder()
                    .sagaName(entry.getSagaName())
                    .entryType(entry.getEntryType())
                    .nodeId(entry.getNodeId())
                    .executionId(entry.getExecutionId())
                    .jsonData(entry.getJsonData());
            futures.add(to.write(deadLetterEntryBuilder));
        });
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()])).thenCompose(_ -> from.truncate());
    }

    /**
     * Starts the saga. When it completes, its saga log is truncated if it succeeded, and only then released with
     * {@code releaseSagaLog}: a log that is released before its truncate has finished is handed to the next saga with
     * the entries of this one still in it. The log and the permit are released also when the truncate fails.
     */
    private SagaHandoffControl startSagaExecutionWithThrottling(SagaExecution sagaExecution, SagaInput sagaInput, SagaLog sagaLog, Map<String, List<SagaCommand>> commandsByNodeId, Runnable releaseSagaLog) {
        SagaHandoffControl handoffControl;
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // set interrupt status
            throw new RuntimeException(e);
        }
        AtomicBoolean permitReleased = new AtomicBoolean(false);
        try {
            String executionId = sagaInput.txId();
            handoffControl = sagaExecution.executeSaga(executionId, sagaInput.asJsonNode(), false,
                    r -> truncateThenRelease(r, sagaLog, releaseSagaLog, permitReleased),
                    failOnSagaCommand("failBefore", commandsByNodeId, sagaLog, executionId),
                    failOnSagaCommand("failAfter", commandsByNodeId, sagaLog, executionId)
            );
            // the permit and the saga log are released when the saga completes
        } catch (RuntimeException e) {
            releasePermit(permitReleased); // ensure that permit is always released even when saga-execution could not be run
            throw e;
        }
        return handoffControl;
    }

    /* When a saga completes: its saga log is truncated if it succeeded, then the log and the permit are released. */
    private void truncateThenRelease(SagaHandoffResult result, SagaLog sagaLog, Runnable releaseSagaLog, AtomicBoolean permitReleased) {
        try {
            if (result.isSuccess()) {
                sagaLog.truncate().join();
            }
        } finally {
            try {
                releaseSagaLog.run();
            } finally {
                releasePermit(permitReleased);
            }
        }
    }

    private void releasePermit(AtomicBoolean permitReleased) {
        if (permitReleased.compareAndSet(false, true)) {
            semaphore.release();
        }
    }

    /* The action before or after a node: fails the node when a saga command of the given name is given for it. */
    private static Consumer<SagaExecutionTraversalContext> failOnSagaCommand(String failCommand, Map<String, List<SagaCommand>> commandsByNodeId, SagaLog sagaLog, String executionId) {
        return sagaExecutionTraversalContext -> {
            List<SagaCommand> commands = commandsByNodeId.get(sagaExecutionTraversalContext.getNode().id);
            if (commands == null) {
                return;
            }
            for (SagaCommand command : commands) {
                if (failCommand.equalsIgnoreCase(command.getCommand())) {
                    throw new IllegalStateException(String.format("%s saga command. nodeId: %s, sagalog-id: %s, executionId: %s", failCommand, sagaExecutionTraversalContext.getNode().id, sagaLog.id(), executionId));
                }
            }
        };
    }

    public void shutdown() {
        threadPoolWatchDog.shutdown();
    }

    public CompletableFuture<Void> completeLocalIncompleteSagas(ExecutorService executorService) {
        Set<SagaLogId> logIds = new LinkedHashSet<>(sagaLogPool.instanceLocalLogIds());

        return recoverIncompleteSagas(executorService, logIds, Collections.emptySet());
    }

    public CompletableFuture<Void> completeClusterWideIncompleteSagas(ExecutorService executorService) {
        Set<SagaLogId> logIds = new LinkedHashSet<>(sagaLogPool.clusterWideLogIds());
        logIds.removeIf(logId -> deadLetterSagaPattern.matcher(logId.getLogName()).matches());
        Set<SagaLogId> instanceLocalLogIds = sagaLogPool.instanceLocalLogIds();
        LinkedHashSet<SagaLogId> nonLocalClusterSagaLogs = new LinkedHashSet<>(logIds);
        nonLocalClusterSagaLogs.removeAll(instanceLocalLogIds);

        return recoverIncompleteSagas(executorService, logIds, nonLocalClusterSagaLogs);
    }

    private CompletableFuture<Void> recoverIncompleteSagas(ExecutorService executorService, Set<SagaLogId> logIds, Set<SagaLogId> nonLocalClusterSagaLogs) {
        List<CompletableFuture<CompletableFuture<Void>>> tasks = new ArrayList<>();
        for (SagaLogId logId : logIds) {
            try {
                tasks.add(CompletableFuture.supplyAsync(() -> recoverSagaLog(logId, nonLocalClusterSagaLogs.contains(logId)), executorService));
            } catch (Throwable t) {
                LOG.warn("Error while attempting to complete saga from saga-log: {}", logId, t);
            }
        }
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[tasks.size()])).join(); // wait for executorService
        List<CompletableFuture<Void>> innerTasks = new ArrayList<>();
        for (CompletableFuture<CompletableFuture<Void>> task : tasks) {
            innerTasks.add(task.join()); // will not block
        }
        return CompletableFuture.allOf(innerTasks.toArray(new CompletableFuture[innerTasks.size()]));
    }

    /* Takes the saga log, when no one else has it, and runs its sagas again. */
    private CompletableFuture<Void> recoverSagaLog(SagaLogId logId, boolean nonClusterLocalSagaLog) {
        SagaLogOwner owner = ownerOfThisThread();
        SagaLog sagaLog;
        try {
            sagaLog = sagaLogPool.tryTakeOwnership(owner, logId);
            if (sagaLog == null) {
                return CompletableFuture.completedFuture(null);
                // unable to take ownership
            }
        } catch (SagaLogBusyException | SagaLogAlreadyAquiredByOtherOwnerException _) {
            if (nonClusterLocalSagaLog) {
                sagaLogPool.remove(logId);
            }
            return CompletableFuture.completedFuture(null);
        }
        try {
            return doCompleteSagaLog(new OwnedSagaLog(sagaLog, owner), true, nonClusterLocalSagaLog, _ -> {
            }, _ -> {
            });
        } catch (Throwable t) {
            LOG.warn("Error completing log {}", logId, t);
            if (nonClusterLocalSagaLog) {
                sagaLogPool.remove(logId);
            }
            sagaLogPool.releaseOwnership(owner, logId);
            return CompletableFuture.failedFuture(t);
        }
    }

    CompletableFuture<Void> doCompleteSagaLog(OwnedSagaLog owned, boolean releaseSagaLogWhenDone, boolean removeFromPoolWhenDone, Consumer<SagaExecutionTraversalContext> preAction, Consumer<SagaExecutionTraversalContext> postAction) {
        SagaLog sagaLog = owned.sagaLog();
        SagaLogOwner owner = owned.owner();
        SagaLogId logId = sagaLog.id();
        Map<String, List<SagaLogEntry>> entriesByExecutionId = sagaLog.readIncompleteSagas().collect(groupingBy(SagaLogEntry::getExecutionId));
        if (entriesByExecutionId.isEmpty()) {
            return completeEmptySagaLog(owner, logId, releaseSagaLogWhenDone, removeFromPoolWhenDone);
        }
        List<CompletableFuture<Void>> futureList = new LinkedList<>();
        for (Map.Entry<String, List<SagaLogEntry>> entry : entriesByExecutionId.entrySet()) {
            String executionId = entry.getKey();
            List<SagaLogEntry> entries = entry.getValue();
            Map<String, List<SagaLogEntry>> entriesByNodeId = entries.stream().collect(groupingBy(SagaLogEntry::getNodeId));
            try {
                futureList.add(startSagaForwardRecovery(executionId, entriesByNodeId, sagaLog, preAction, postAction));
            } catch (Throwable t) {
                if (removeFromPoolWhenDone) {
                    sagaLogPool.remove(logId);
                }
                releaseIfAsked(owner, logId, releaseSagaLogWhenDone);
                return CompletableFuture.failedFuture(t);
            }
        }
        return CompletableFuture.allOf(futureList.toArray(new CompletableFuture[futureList.size()]))
                .handle((v, t) -> {
                    try {
                        return truncateAfterForwardRecovery(v, t, sagaLog, removeFromPoolWhenDone);
                    } finally {
                        // also when the truncate, the remove or the delete failed, or the log would stay held for ever
                        releaseIfAsked(owner, logId, releaseSagaLogWhenDone);
                    }
                });
    }

    private CompletableFuture<Void> completeEmptySagaLog(SagaLogOwner owner, SagaLogId logId, boolean releaseSagaLogWhenDone, boolean removeFromPoolWhenDone) {
        if (removeFromPoolWhenDone) {
            sagaLogPool.remove(logId);
            deleteExternalResourceAssociatedWithSagaLog(logId); // not in use
        }
        releaseIfAsked(owner, logId, releaseSagaLogWhenDone);
        return CompletableFuture.completedFuture(null);
    }

    private void releaseIfAsked(SagaLogOwner owner, SagaLogId logId, boolean releaseSagaLogWhenDone) {
        if (releaseSagaLogWhenDone) {
            sagaLogPool.releaseOwnership(owner, logId);
        }
    }

    /*
     * When the sagas of a saga log have been recovered: truncates the log, and removes it from the pool when asked.
     * When the recovery, the truncate, the remove or the delete fails, the log is removed from the pool all the same
     * when asked, and the first failure is the one thrown, with a failure of that remove added to it as suppressed.
     */
    private Void truncateAfterForwardRecovery(Void v, Throwable recoveryFailure, SagaLog sagaLog, boolean removeFromPoolWhenDone) {
        SagaLogId logId = sagaLog.id();
        boolean removed = false;
        try {
            if (recoveryFailure != null) {
                throw new RuntimeException("Unable to complete saga forward recovery", recoveryFailure);
            }
            sagaLog.truncate().join();
            if (removeFromPoolWhenDone) {
                sagaLogPool.remove(logId);
                removed = true;
                deleteExternalResourceAssociatedWithSagaLog(logId); // not in use
            }
            return v;
        } catch (Throwable failure) {
            if (removeFromPoolWhenDone && !removed) {
                // The log of another instance is not kept in this pool after a failure. Its entries are still in
                // the saga log store, so the next cluster-wide pass finds it again.
                try {
                    sagaLogPool.remove(logId);
                } catch (RuntimeException e) {
                    failure.addSuppressed(e); // the first failure is the one to report
                }
            }
            throw failure;
        }
    }

    private void deleteExternalResourceAssociatedWithSagaLog(SagaLogId logId) {
        boolean deleted = sagaLogPool.delete(logId);
        if (deleted) {
            LOG.info("Deleted unused empty saga-log: {}", logId);
        } else {
            LOG.info("Failed to deleted saga-log: {}", logId);
        }
    }

    private CompletableFuture<Void> startSagaForwardRecovery(String executionId, Map<String, List<SagaLogEntry>> entriesByNodeId, SagaLog sagaLog, Consumer<SagaExecutionTraversalContext> preAction, Consumer<SagaExecutionTraversalContext> postAction) {
        if (entriesByNodeId.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<SagaLogEntry> sagaLogEntries = entriesByNodeId.get(Saga.ID_START);
        if (sagaLogEntries == null) {
            throw new IllegalStateException(String.format("In saga-log %s, Missing Saga start id. Entries: %s", sagaLog.id(), entriesByNodeId));
        }
        SagaLogEntry startSagaEntry = sagaLogEntries.get(0);
        Saga saga = sagaRepository.get(startSagaEntry.getSagaName());
        if (saga == null) {
            throw new IllegalStateException(String.format("In saga-log %s, Saga with name %s is not present in sagaRepository", sagaLog.id(), startSagaEntry.getSagaName()));
        }
        AdapterLoader adapterLoader = sagaRepository.getAdapterLoader();
        JsonNode sagaInput = JsonTools.toJsonNode(startSagaEntry.getJsonData());
        SagaExecution sagaExecution = new SagaExecution(sagaLog, threadPool, saga, adapterLoader);
        CompletableFuture<SagaHandoffResult> future = new CompletableFuture<>();
        SagaHandoffControl handoffControl = sagaExecution.executeSaga(executionId, sagaInput, true, future::complete,
                preAction, postAction
        );
        track(handoffControl);
        sagasObserver.registerSaga(handoffControl);
        LOG.info("Started recovery of saga with sagaLog: {} and executionId: {}", sagaLog.id(), executionId);
        return future.thenCompose(r -> {
            if (r.isFailure()) {
                LOG.info("Recovery of saga failed, sagaLog: {}, executionId: {}", sagaLog.id(), executionId);
                return CompletableFuture.failedFuture(r.getFailureCause()); // unwrap
            } else {
                LOG.info("Recovery of saga succeeded, sagaLog: {}, executionId: {}", sagaLog.id(), executionId);
                return CompletableFuture.completedFuture(null);
            }
        });
    }

    public ExecutorService getRecoveryThreadPool() {
        return recoveryThreadPool;
    }

    /**
     * Looks at the saga thread pool every second, and grows it when it is stuck: every thread is busy, nothing has
     * completed since the last look, and the pool could still grow. A thread pool grows past its core size only when
     * its queue is full, so sagas that wait in the queue behind steps that hold every core thread (a store or a log
     * that hangs) would otherwise wait for those steps, with room in the pool. The watchdog submits empty tasks until
     * the queue overflows, and the pool takes on threads for the sagas in the queue.
     * <p>
     * The saga engine no longer holds a thread while a step waits for the steps before it, so the pool does not get
     * stuck that way any more; steps that hang still can.
     */
    class ThreadPoolWatchDog extends Thread {

        public static final int WATCHDOG_INTERVAL_SEC = 1;

        final AtomicLong deadlockResolutionAttemptCounter = new AtomicLong(0);
        final CountDownLatch doneSignal = new CountDownLatch(1);

        public ThreadPoolWatchDog() {
            super("Saga-Threadpool-Watchdog");
        }

        void shutdown() {
            doneSignal.countDown();
        }

        @Override
        public void run() {
            try {
                BlockingQueue<Runnable> queue = threadPool.getQueue();
                int previousActiveCount = -1;
                long previousCompletedTaskCount = -1;
                while (!doneSignal.await(WATCHDOG_INTERVAL_SEC, TimeUnit.SECONDS)) {
                    // check saga-thread-pool for possible deadlock
                    if (possibleDeadlock(previousActiveCount, previousCompletedTaskCount)) {
                        int emptyTasksToSubmit = (threadPool.getMaximumPoolSize() - threadPool.getPoolSize()) / 2 + queue.remainingCapacity();
                        // Submit a number of empty tasks to thread-pool in order to force the work-queue to overflow.
                        // This should force around half the remaining thread-pool-capacity (with regards to max-size) to become available
                        LOG.info("Submitting {} empty-tasks in an attempt to resolve a potential saga-deadlock due to mismatch between workload and thread-pool configuration. Threadpool-state: {}", emptyTasksToSubmit, threadPool);
                        for (int i = 0; i < emptyTasksToSubmit; i++) {
                            threadPool.submit(() -> {
                            });
                        }
                        deadlockResolutionAttemptCounter.incrementAndGet();
                    }
                    previousCompletedTaskCount = threadPool.getCompletedTaskCount();
                    previousActiveCount = threadPool.getActiveCount();
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                LOG.warn("Saga threadpool watchdog interrupted and died.");
            } catch (Throwable t) {
                LOG.warn("", t);
            }
        }

        boolean possibleDeadlock(int previousActiveCount, long previousCompletedTaskCount) {
            return threadPool.getActiveCount() > 0 // at least one active thread
                    && threadPool.getActiveCount() == threadPool.getPoolSize() // all threads in pool are active
                    && threadPool.getPoolSize() < threadPool.getMaximumPoolSize() // pool can still grow
                    && threadPool.getActiveCount() == previousActiveCount // no recent change in activity
                    && threadPool.getCompletedTaskCount() == previousCompletedTaskCount; // no more tasks have completed since previous check
        }
    }
}
