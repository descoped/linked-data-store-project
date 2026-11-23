package io.descoped.lds.core.search;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.IndexEntry;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.IndexUpdate;
import io.descoped.lds.api.search.SearchIndex;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends the updates of the index queue to the search index. In rounds: it takes a batch of updates, reads each
 * written document from storage at its version (one after another: a transaction is one connection or session of the
 * store), sends the entries of the batch in one request ({@link IndexEntries}: a written version with its texts, a
 * deletion as a deletion), and removes the updates when the index has taken them. An update the index does not take is put off and tried again, later each time, up to a minute; when a batch
 * fails, each of its updates is tried alone, so one update the index refuses does not hold back the others. A round
 * follows at once when the last one took updates, and after a short wait when there were none.
 * <p>
 * Storage holds the version of every update: the saga of a write adds it to the queue only after its storage step,
 * and never undoes that step, and a rebuild adds what it read from storage. Storage is read as of the version, so the
 * index is told what storage holds; a document storage does not have at all is not sent.
 * <p>
 * An update the index keeps refusing is tried again for as long as LDS runs, a minute apart: an update is never
 * dropped (decided by the owner, D1 and D2). From its tenth attempt each refusal is logged as an error, and the
 * update, the oldest in the queue, makes the readiness DEGRADED once it has waited over a minute.
 */
public final class IndexQueueWorker {

    private static final Logger LOG = LoggerFactory.getLogger(IndexQueueWorker.class);

    /* The name of the worker's thread, followed by the number of the worker in the JVM. */
    static final String THREAD_NAME = "lds-index-queue-";
    private static final AtomicInteger WORKERS = new AtomicInteger();

    static final int BATCH = 100;
    static final Duration LEASE = Duration.ofMinutes(2);
    static final Duration FIRST_DELAY = Duration.ofSeconds(1);
    static final Duration LONGEST_DELAY = Duration.ofMinutes(1);

    private final IndexQueue queue;
    private final SearchIndex index;
    private final RxJsonPersistence persistence;
    private final IndexEntries indexEntries;
    private final Duration poll;
    private final ExecutorService executor;
    private final Scheduler scheduler;

    private final Object lock = new Object();
    private boolean stopped; // guarded by lock
    private Disposable next; // guarded by lock
    private CompletableFuture<Void> round = CompletableFuture.completedFuture(null); // guarded by lock

    /**
     * A worker that is not started.
     *
     * @param queue       the queue it takes updates from
     * @param index       the index it sends them to
     * @param persistence  the storage it reads the documents from
     * @param indexEntries what the index is given of a document
     * @param poll         how long it waits for updates when there were none
     */
    public IndexQueueWorker(IndexQueue queue, SearchIndex index, RxJsonPersistence persistence, IndexEntries indexEntries,
                            Duration poll) {
        this.queue = queue;
        this.index = index;
        this.persistence = persistence;
        this.indexEntries = indexEntries;
        this.poll = poll;
        String threadName = THREAD_NAME + WORKERS.incrementAndGet();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler = Schedulers.from(executor);
    }

    /**
     * Starts the rounds.
     */
    public void start() {
        schedule(Duration.ZERO);
    }

    /**
     * Stops after the round that runs, waiting for it at most the given time; the updates it has not removed stay in
     * the queue.
     *
     * @param timeout the longest wait for the round
     * @param unit    its unit
     * @throws InterruptedException when the wait is interrupted
     */
    public void stop(long timeout, TimeUnit unit) throws InterruptedException {
        CompletableFuture<Void> running;
        synchronized (lock) {
            stopped = true;
            if (next != null) {
                next.dispose();
            }
            running = round;
        }
        try {
            running.get(timeout, unit);
        } catch (TimeoutException e) {
            LOG.warn("The index queue was still sending after {} {}; what it had taken is taken again after {}",
                    timeout, unit, LEASE);
        } catch (ExecutionException e) {
            LOG.debug("The last round of the index queue failed", e);
        }
        executor.shutdownNow();
        if (!executor.awaitTermination(timeout, unit)) {
            LOG.warn("The thread of the index queue had not ended {} {} after it was stopped", timeout, unit);
        }
    }

    /**
     * Whether the worker has stopped and its thread has ended.
     *
     * @return true after {@link #stop(long, TimeUnit)} when the thread has ended
     */
    boolean isTerminated() {
        return executor.isTerminated();
    }

    /*
     * The next round, after the delay. Stopping cancels a round that waits to start, never one that runs: a running
     * round is let finish, so its updates are either taken by the index and removed, or left in the queue.
     */
    private void schedule(Duration delay) {
        synchronized (lock) {
            if (stopped) {
                return;
            }
            next = scheduler.scheduleDirect(this::runRound, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void runRound() {
        CompletableFuture<Void> thisRound = new CompletableFuture<>();
        synchronized (lock) {
            if (stopped) {
                return;
            }
            round = thisRound;
        }
        round().subscribe(tookUpdates -> {
            thisRound.complete(null);
            schedule(tookUpdates ? Duration.ZERO : poll);
        }, error -> {
            LOG.error("A round of the index queue failed", error);
            thisRound.complete(null);
            schedule(poll);
        });
    }

    /**
     * One round: takes a batch, sends it, and removes or puts off its updates.
     *
     * @return whether it took updates
     */
    Single<Boolean> round() {
        return queue.take(BATCH, LEASE).flatMap(entries -> entries.isEmpty()
                ? Single.just(false)
                : sendAll(entries).toSingleDefault(true));
    }

    private Completable sendAll(List<IndexQueue.Entry> entries) {
        return send(entries)
                .andThen(Completable.defer(() -> queue.remove(entries)))
                .onErrorResumeNext(error -> {
                    if (entries.size() == 1) {
                        return putOff(entries.getFirst(), error);
                    }
                    LOG.warn("The search index did not take a batch of {} updates; each is tried alone: {}",
                            entries.size(), error.toString());
                    return Flowable.fromIterable(entries).concatMapCompletable(entry -> send(List.of(entry))
                            .andThen(Completable.defer(() -> queue.remove(List.of(entry))))
                            .onErrorResumeNext(alone -> putOff(entry, alone)));
                });
    }

    /* From this attempt on, a refused update is logged as an error: the index has refused it for minutes. */
    static final int ATTEMPTS_LOGGED_AS_ERROR = 10;

    private Completable putOff(IndexQueue.Entry entry, Throwable error) {
        Duration delay = delay(entry.attempts());
        int attempt = entry.attempts() + 1;
        LOG.atLevel(attempt >= ATTEMPTS_LOGGED_AS_ERROR ? Level.ERROR : Level.WARN).log(
                "The search index did not take the update of {}/{}/{} at {} (attempt {}); tried again in {}: {}",
                entry.update().namespace(), entry.update().entity(), entry.update().id(), entry.update().version(),
                attempt, delay, error.toString());
        return queue.postpone(List.of(entry), delay);
    }

    /**
     * The wait before an update is tried again: one second, twice as long after each attempt, a minute at most.
     *
     * @param attempts the attempts it has had
     * @return the wait
     */
    static Duration delay(int attempts) {
        int doublings = Math.min(attempts, 6);
        Duration delay = FIRST_DELAY.multipliedBy(1L << doublings);
        return delay.compareTo(LONGEST_DELAY) > 0 ? LONGEST_DELAY : delay;
    }

    /*
     * Reads the written documents at their versions, then sends the entries of the batch in one update. The read
     * transaction is opened, and closed, on the thread of the worker: a store may tie the transaction to the thread
     * that opened it (the memory store holds a lock of that thread), and the documents can come on another.
     */
    private Completable send(List<IndexQueue.Entry> entries) {
        return Single.using(() -> persistence.createTransaction(true),
                        transaction -> Flowable.fromIterable(entries)
                                .concatMapMaybe(entry -> entryOf(transaction, entry.update()))
                                .toList()
                                .observeOn(scheduler),
                        Transaction::close)
                .subscribeOn(scheduler)
                .flatMapCompletable(index::put);
    }

    /*
     * A written version as storage holds it, and a deletion, each at the version of its update: the instant the
     * write was made at (the memory store does not yet give back the instant of a version written with a zone other
     * than UTC). An entity type that is not searched has no entry, and its update is removed.
     */
    private Maybe<IndexEntry> entryOf(Transaction transaction, IndexUpdate update) {
        if (!indexEntries.searched(update.entity())) {
            return Maybe.empty();
        }
        if (update.operation() == IndexUpdate.Operation.DELETE) {
            return Maybe.just(IndexEntry.deleted(update.namespace(), update.entity(), update.id(), update.version()));
        }
        return persistence.readDocument(transaction, update.version(), update.namespace(), update.entity(), update.id())
                .flatMap(document -> Maybe.fromOptional(indexEntries.written(new JsonDocument(new DocumentKey(
                        update.namespace(), update.entity(), update.id(), update.version()), document.jackson()))));
    }
}
