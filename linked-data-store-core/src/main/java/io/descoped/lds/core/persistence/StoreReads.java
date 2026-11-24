package io.descoped.lds.core.persistence;

import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The threads on which the store is read, so that no thread of a request waits for it: a read transaction is opened,
 * read and closed on one thread of these, and what it read is given on. They belong to the application, which closes
 * them when it stops, after the requests and before the store.
 * <p>
 * A transaction of the store in memory holds a lock of the thread that opened it, so it must be closed on that
 * thread; a store may emit on threads of its own (the store in memory does). A read therefore subscribes on one of
 * these threads, observes what the store gives on it, and is closed on it, also when it is disposed. Opening a
 * transaction may wait (for the lock of a write, for a connection): it waits on a thread of the store, never on a
 * thread of a request. A read that is given up (disposed) interrupts its thread first, so a store that hangs does
 * not keep the thread, and its transaction is then closed on that thread.
 * <p>
 * Also the threads on which a GraphQL query goes on after a search ({@link #queries()}): a field may read the store
 * there and wait for it.
 */
public final class StoreReads {

    private static final Logger LOG = LoggerFactory.getLogger(StoreReads.class);

    private final ExecutorService[] executors;
    private final Scheduler[] threads;
    private final Scheduler queries;
    private final AtomicInteger next = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * As many threads of the store as there are processors, two at least.
     */
    public StoreReads() {
        this(Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * The threads of the store.
     *
     * @param count how many threads read the store
     */
    public StoreReads(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("At least one thread reads the store: " + count);
        }
        executors = new ExecutorService[count];
        threads = new Scheduler[count];
        for (int i = 0; i < count; i++) {
            String name = "lds-store-read-" + i;
            executors[i] = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, name));
            threads[i] = Schedulers.from(executors[i], true); // a read that is given up interrupts its thread
        }
        AtomicInteger query = new AtomicInteger();
        // RxJava's io scheduler, of the application's own: a worker keeps its thread, as a read of the store in
        // memory needs, and the threads are let go when they are not used
        queries = RxJavaPlugins.createIoScheduler(runnable -> daemon(runnable, "lds-query-" + query.getAndIncrement()));
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private Scheduler next() {
        return threads[Math.floorMod(next.getAndIncrement(), threads.length)];
    }

    /**
     * A read in a read transaction of its own, which is closed on the thread that opened it.
     *
     * @param persistence the store
     * @param read        the read, given the transaction
     * @param <T>         what is read
     * @return what was read, or nothing; an error when the threads are closed
     */
    public <T> Maybe<T> maybe(RxJsonPersistence persistence, Function<Transaction, Maybe<T>> read) {
        return Maybe.defer(() -> {
            if (closed.get()) {
                return Maybe.error(stopped());
            }
            Scheduler thread = next();
            return Maybe.using(() -> persistence.createTransaction(true),
                            tx -> read.apply(tx).observeOn(thread),
                            Transaction::close)
                    .unsubscribeOn(thread)
                    .subscribeOn(thread);
        });
    }

    /**
     * A read in a read transaction of its own, which is closed on the thread that opened it.
     *
     * @param persistence the store
     * @param read        the read, given the transaction
     * @param <T>         what is read
     * @return what was read; an error when the threads are closed
     */
    public <T> Single<T> single(RxJsonPersistence persistence, Function<Transaction, Single<T>> read) {
        return Single.defer(() -> {
            if (closed.get()) {
                return Single.error(stopped());
            }
            Scheduler thread = next();
            return Single.using(() -> persistence.createTransaction(true),
                            tx -> read.apply(tx).observeOn(thread),
                            Transaction::close)
                    .unsubscribeOn(thread)
                    .subscribeOn(thread);
        });
    }

    private static IllegalStateException stopped() {
        return new IllegalStateException("The store is not read: the application is stopping");
    }

    /**
     * The threads on which a GraphQL query goes on after a search: a field may still read the store there and wait
     * for it, which a thread of the index or of the store is not kept for.
     *
     * @return the threads of queries
     */
    public Scheduler queries() {
        return queries;
    }

    /**
     * Closes the threads: no read starts after, the reads that run complete within the time given, and are then
     * interrupted. Called when the application stops, after the requests and before the store is closed.
     *
     * @param timeout how long the reads that run may take to complete
     * @throws InterruptedException when interrupted while waiting for them
     */
    public void close(Duration timeout) throws InterruptedException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        queries.shutdown();
        for (ExecutorService executor : executors) {
            executor.shutdown();
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        for (ExecutorService executor : executors) {
            if (!executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                LOG.warn("A read of the store had not completed after {}; it is interrupted", timeout);
                executor.shutdownNow();
            }
        }
    }

    /**
     * Whether the threads are closed and every read on them has ended.
     *
     * @return true when every thread of the store has ended
     */
    public boolean isTerminated() {
        for (ExecutorService executor : executors) {
            if (!executor.isTerminated()) {
                return false;
            }
        }
        return true;
    }
}
