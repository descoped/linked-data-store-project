package io.descoped.lds.core.persistence;

import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Reads of the store composed as Rx, so that no thread waits for them: a read transaction is opened, read and closed
 * on one thread of the store's own, and what it read is given on.
 * <p>
 * A transaction of the store in memory holds a lock of the thread that opened it, so it must be closed on that
 * thread; a store may emit on threads of its own (the store in memory does). A read therefore subscribes on one of
 * these threads, observes what the store gives on it, and is closed on it, also when it is disposed. Opening a
 * transaction may wait (for the lock of a write, for a connection): it waits on a thread of the store, never on a
 * thread of a request. A read that is given up (disposed) interrupts its thread first, so a store that hangs does
 * not keep the thread, and its transaction is then closed on that thread.
 */
public final class StoreReads {

    private static final Scheduler[] THREADS = threads(Math.max(2, Runtime.getRuntime().availableProcessors()));
    private static final AtomicInteger NEXT = new AtomicInteger();

    private StoreReads() {
    }

    private static Scheduler[] threads(int count) {
        Scheduler[] threads = new Scheduler[count];
        for (int i = 0; i < count; i++) {
            String name = "lds-store-read-" + i;
            ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            });
            threads[i] = Schedulers.from(executor, true); // a read that is given up interrupts its thread
        }
        return threads;
    }

    private static Scheduler next() {
        return THREADS[Math.floorMod(NEXT.getAndIncrement(), THREADS.length)];
    }

    /**
     * A read in a read transaction of its own, which is closed on the thread that opened it.
     *
     * @param persistence the store
     * @param read        the read, given the transaction
     * @param <T>         what is read
     * @return what was read, or nothing
     */
    public static <T> Maybe<T> maybe(RxJsonPersistence persistence, Function<Transaction, Maybe<T>> read) {
        Scheduler thread = next();
        return Maybe.using(() -> persistence.createTransaction(true),
                        tx -> read.apply(tx).observeOn(thread),
                        Transaction::close)
                .unsubscribeOn(thread)
                .subscribeOn(thread);
    }

    /**
     * A read in a read transaction of its own, which is closed on the thread that opened it.
     *
     * @param persistence the store
     * @param read        the read, given the transaction
     * @param <T>         what is read
     * @return what was read
     */
    public static <T> Single<T> single(RxJsonPersistence persistence, Function<Transaction, Single<T>> read) {
        Scheduler thread = next();
        return Single.using(() -> persistence.createTransaction(true),
                        tx -> read.apply(tx).observeOn(thread),
                        Transaction::close)
                .unsubscribeOn(thread)
                .subscribeOn(thread);
    }
}
