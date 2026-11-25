package io.descoped.lds.core.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The stop of a pool of threads within the time a subsystem is given: the work it has taken is let complete for that
 * time, then it is interrupted, and its threads get a short while more to end.
 */
public final class Pools {

    private static final Logger LOG = LoggerFactory.getLogger(Pools.class);

    /** How long the threads of a pool get to end once they are interrupted. */
    public static final Duration GRACE = Duration.ofSeconds(1);

    private Pools() {
    }

    /**
     * Shuts the pool down: it takes no more work, and the work it has taken, running or waiting, may complete
     * within the timeout. Then what still runs is interrupted, and what waits is not run; the threads get
     * {@link #GRACE} to end. A stop on a thread that is interrupted does not wait: the pool is interrupted at once,
     * and the thread stays interrupted.
     *
     * @param name    the pool, as the log names it
     * @param pool    the pool
     * @param timeout how long its work may take to complete, at most
     * @return true when its work completed within the timeout
     */
    public static boolean stop(String name, ExecutorService pool, Duration timeout) {
        pool.shutdown();
        try {
            if (pool.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                return true;
            }
            LOG.warn("{} still had work after {}; it is interrupted", name, timeout);
            pool.shutdownNow();
            if (!pool.awaitTermination(GRACE.toNanos(), TimeUnit.NANOSECONDS)) {
                LOG.error("The threads of {} had not ended {} after they were interrupted", name, GRACE);
            }
            return false;
        } catch (InterruptedException _) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
