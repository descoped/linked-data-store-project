package io.descoped.lds.core.saga;

import io.descoped.lds.core.lifecycle.Pools;
import no.cantara.sagalog.SagaPoolShutdownException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class SagaRecoveryTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(SagaRecoveryTrigger.class);

    private final SagaExecutionCoordinator sec;
    private final int intervalMinSec;
    private final int intervalMaxSec;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private static final int MAX_LEGAL_INTERVAL_SEC = 60 * 60 * 24;
    private final Random random = new Random();

    private static final AtomicInteger triggerThreadId = new AtomicInteger();
    private final ScheduledExecutorService recoveryTriggerSingleThreadedPool = Executors.newScheduledThreadPool(1, runnable -> new Thread(runnable, "saga-recovery-trigger-" + triggerThreadId.incrementAndGet()));
    private final AtomicReference<ScheduledFuture<?>> triggerScheduleRef = new AtomicReference<>();
    private final ExecutorService recoveryThreadPool;

    public SagaRecoveryTrigger(SagaExecutionCoordinator sec, int intervalMinSec, int intervalMaxSec, ScheduledExecutorService recoveryThreadPool) {
        if (intervalMinSec < 1) {
            throw new IllegalArgumentException("illegal configuration: intervalMinSec < 1");
        }
        if (intervalMinSec > MAX_LEGAL_INTERVAL_SEC) {
            throw new IllegalArgumentException("illegal configuration: intervalMinSec > " + MAX_LEGAL_INTERVAL_SEC);
        }
        if (intervalMaxSec < 1) {
            throw new IllegalArgumentException("illegal configuration: intervalMaxSec < 1");
        }
        if (intervalMaxSec > MAX_LEGAL_INTERVAL_SEC) {
            throw new IllegalArgumentException("illegal configuration: intervalMaxSec > " + MAX_LEGAL_INTERVAL_SEC);
        }
        if (intervalMaxSec < intervalMinSec) {
            throw new IllegalArgumentException("illegal configuration: intervalMaxSec < intervalMinSec");
        }
        this.sec = sec;
        this.intervalMinSec = intervalMinSec;
        this.intervalMaxSec = intervalMaxSec;
        this.recoveryThreadPool = recoveryThreadPool;
    }

    public void start() {
        triggerScheduleRef.set(recoveryTriggerSingleThreadedPool.schedule(triggerRecoveryTask(), randomizedWaitSec(), TimeUnit.SECONDS));
    }

    private Runnable triggerRecoveryTask() {
        return () -> {
            try {
                if (stopped.get()) {
                    LOG.info("Saga recovery trigger stopped");
                    return;
                }
                LOG.debug("Saga recovery task triggered, attempting to recover all incomplete sagas cluster-wide");
                sec.completeClusterWideIncompleteSagas(recoveryThreadPool).join();
                LOG.debug("Saga recovery task complete.");
            } catch (Throwable t) {
                if (stopped.get() || shuttingDown(t)) {
                    LOG.info("Saga recovery ended, the application is shutting down: {}", String.valueOf(t));
                    return;
                }
                LOG.error("Error while attempting to run cluster-wide saga recovery", t);
            }
            if (stopped.get()) {
                return; // nothing is scheduled after stop()
            }
            triggerScheduleRef.set(recoveryTriggerSingleThreadedPool.schedule(triggerRecoveryTask(), randomizedWaitSec(), TimeUnit.SECONDS));
        };
    }

    /* The saga log pool is shut down: that is the application stopping, not a fault of the recovery. */
    static boolean shuttingDown(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof SagaPoolShutdownException) {
                return true;
            }
        }
        return false;
    }

    private long randomizedWaitSec() {
        return (long) intervalMinSec + random.nextInt(1 + intervalMaxSec - intervalMinSec);
    }

    /**
     * Stops the trigger, then its thread and the recovery thread pool, whose recovery may complete within the time
     * given, for both together; then what still runs is interrupted (see {@link Pools#stop}).
     *
     * @param timeout how long the recovery that runs may take to complete, at most
     */
    public void stop(Duration timeout) {
        stopped.set(true);
        ScheduledFuture<?> scheduledFuture = triggerScheduleRef.get();
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        LOG.debug("Shutting down single-thread-pool");
        Pools.stop("The saga recovery trigger", recoveryTriggerSingleThreadedPool, timeout);
        LOG.debug("Shutting down recovery thread-pool");
        Pools.stop("The saga recovery thread pool", recoveryThreadPool,
                Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        LOG.debug("Shutting down recovery thread-pool done!");
    }
}
