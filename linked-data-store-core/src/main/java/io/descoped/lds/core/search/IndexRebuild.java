package io.descoped.lds.core.search;

import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.IndexUpdate;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.core.lifecycle.Pools;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rebuilds the search index from storage: every version of every document of every entity type is added to the index
 * queue, as the writes added them, and the worker of the queue sends them. A version that marks a document as deleted
 * is added as a delete, and the versions before it as writes, so the index holds what storage holds, back in time.
 * Each version is an entry of its own in the index, sent again under the key it has, so it is replaced by the same
 * entry and search answers all the while.
 * <p>
 * When every update is added, and the queue is then empty (the index has taken them all, the updates of other writes
 * too), the index removes the entries of the namespace it took before the rebuild started, by its own clock: what
 * the rebuild did not send again is what storage no longer holds, or what an earlier model searched. A queue that is
 * never empty puts the clean-up off, and the log says so every few minutes with the lag of the queue; a rebuild that
 * is stopped before it leaves the old entries, which search does not find unless they are versions storage no longer
 * holds.
 * <p>
 * The versions of one entity type are read in one read transaction, opened, read and closed on the rebuild's own
 * thread (the memory store holds a lock of the thread that opened it), and taken from storage as the queue takes
 * them. The updates are added to the queue side by side, a few at a time.
 */
public final class IndexRebuild {

    private static final Logger LOG = LoggerFactory.getLogger(IndexRebuild.class);

    static final int CONCURRENT_ADDS = 8;

    /*
     * Taken off the time since the rebuild started, so that no entry the rebuild sent is removed: the start is
     * measured here, and the index's clock is read when the removal reaches it, later, after the request and the
     * index's own queue of updates. An old entry left (removed by the next rebuild, and found by no search unless it
     * is a version storage no longer holds) is the lesser fault: an entry removed by mistake is lost without a word.
     */
    static final Duration MARGIN = Duration.ofMinutes(1);

    /* How often the log says that the clean-up still waits for an empty queue. */
    static final Duration WAITING_REPORT = Duration.ofMinutes(5);

    private final RxJsonPersistence persistence;
    private final IndexQueue queue;
    private final SearchIndex index;
    private final Duration poll;
    private final Duration waitingReport;
    private final String namespace;
    private final List<String> entities;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("lds-index-rebuild").factory());
    private final Scheduler scheduler = Schedulers.from(executor);
    private final Object lock = new Object();
    private Disposable running;
    private boolean stopped;

    /**
     * @param persistence the store
     * @param queue       the index queue
     * @param index       the search index, which removes what the rebuild did not send again
     * @param namespace   the namespace
     * @param entities    the entity types, read in this order
     */
    public IndexRebuild(RxJsonPersistence persistence, IndexQueue queue, SearchIndex index, String namespace,
                        List<String> entities) {
        this(persistence, queue, index, namespace, entities, Duration.ofSeconds(1), WAITING_REPORT);
    }

    IndexRebuild(RxJsonPersistence persistence, IndexQueue queue, SearchIndex index, String namespace,
                 List<String> entities, Duration poll) {
        this(persistence, queue, index, namespace, entities, poll, WAITING_REPORT);
    }

    IndexRebuild(RxJsonPersistence persistence, IndexQueue queue, SearchIndex index, String namespace,
                 List<String> entities, Duration poll, Duration waitingReport) {
        this.persistence = persistence;
        this.queue = queue;
        this.index = index;
        this.namespace = namespace;
        this.entities = List.copyOf(entities);
        this.poll = poll;
        this.waitingReport = waitingReport;
    }

    /**
     * Adds an update for every version of every document to the queue.
     *
     * @return the number of updates added
     */
    Single<Long> rebuild() {
        return Flowable.fromIterable(entities)
                .concatMap(entity -> Flowable.using(
                                () -> persistence.createTransaction(true),
                                // its end reaches using on the rebuild's thread, and a few versions are read ahead
                                transaction -> persistence.readAllDocumentVersions(transaction, namespace, entity)
                                        .observeOn(scheduler, false, CONCURRENT_ADDS),
                                Transaction::close)
                        // opened, read, asked for more and closed, also when cancelled, on the rebuild's thread
                        .subscribeOn(scheduler)
                        .unsubscribeOn(scheduler))
                .map(IndexRebuild::update)
                .flatMapSingle(update -> queue.add(update).toSingleDefault(1L), false, CONCURRENT_ADDS)
                .count();
    }

    /**
     * Rebuilds, then waits until the queue is empty, asking as often as given, and removes the entries the index took
     * before the rebuild started.
     *
     * @return the number of updates added
     */
    Single<Long> rebuildAndCleanUp() {
        return Single.defer(() -> {
            long started = System.nanoTime();
            return rebuild().flatMap(added -> {
                LOG.info("Every version is added to the index queue ({}); the entries the search index took before the"
                        + " rebuild are removed when the queue is empty", added);
                return emptied()
                        .andThen(Completable.defer(() -> index.removeOlderThan(namespace,
                                Duration.ofNanos(System.nanoTime() - started).plus(MARGIN))))
                        .toSingleDefault(added);
            });
        });
    }

    private Completable emptied() {
        return Completable.defer(() -> {
            long waiting = System.nanoTime();
            AtomicLong reported = new AtomicLong(waiting);
            return Flowable.interval(0, poll.toMillis(), TimeUnit.MILLISECONDS)
                    .concatMapSingle(_ -> queue.lag())
                    .doOnNext(lag -> {
                        long now = System.nanoTime();
                        if (lag.updates() > 0 && now - reported.get() >= waitingReport.toNanos()) {
                            reported.set(now);
                            LOG.info("The clean-up after the rebuild of the search index has waited {} for the index"
                                    + " queue to be empty: it holds {} updates, the oldest added {}",
                                    Duration.ofNanos(now - waiting).withNanos(0), lag.updates(), lag.oldest());
                        }
                    })
                    .filter(lag -> lag.updates() == 0)
                    .firstOrError()
                    .ignoreElement();
        });
    }

    private static IndexUpdate update(JsonDocument document) {
        return new IndexUpdate(document.key().namespace(), document.key().entity(), document.key().id(),
                document.key().timestamp(),
                document.deleted() ? IndexUpdate.Operation.DELETE : IndexUpdate.Operation.CREATE_OR_OVERWRITE);
    }

    /**
     * Starts the rebuild in the background and returns; it says in the log when it is done, or why it failed.
     */
    public void start() {
        synchronized (lock) {
            if (stopped || running != null) {
                return;
            }
            LOG.info("Rebuilding the search index from storage: {} in the namespace {}", entities, namespace);
            LOG.warn("search.index.rebuild is on: every start of every LDS with it rebuilds the search index. It is"
                    + " meant for one start of one LDS; turn it off when the rebuild is done");
            running = rebuildAndCleanUp().subscribe(
                    added -> LOG.info("The search index is rebuilt from storage: {} versions sent, and the entries it"
                            + " took before the rebuild removed", added),
                    error -> LOG.error("The rebuild of the search index failed, or its clean-up; what was added to the"
                            + " index queue is sent, and a start with the rebuild asked for again adds every version"
                            + " and cleans up", error));
        }
    }

    /**
     * Stops a rebuild that runs, and closes its read transaction. What it has added to the queue stays there.
     *
     * @param timeout how long the read may take to end, at most
     */
    public void stop(Duration timeout) {
        synchronized (lock) {
            stopped = true;
            if (running != null && !running.isDisposed()) {
                LOG.warn("The rebuild of the search index was stopped before it was done");
                running.dispose();
            }
        }
        // waits for the read transaction to be closed, at most the time given
        Pools.stop("The rebuild of the search index", executor, timeout);
    }

    /**
     * Whether a rebuild that was started has ended, done, failed or stopped.
     *
     * @return true when it has ended
     */
    boolean isDone() {
        synchronized (lock) {
            return running != null && running.isDisposed();
        }
    }
}
