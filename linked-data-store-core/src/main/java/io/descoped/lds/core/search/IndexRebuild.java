package io.descoped.lds.core.search;

import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.IndexUpdate;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rebuilds the search index from storage: every version of every document of every entity type is added to the index
 * queue, as the writes added them, and the worker of the queue sends them. A version that marks a document as deleted
 * is added as a delete, and the versions before it as writes, so the index holds what storage holds, back in time.
 * Each version is an entry of its own in the index and is overwritten in place, so search answers all the while.
 * <p>
 * The versions of one entity type are read in one read transaction, opened, read and closed on the rebuild's own
 * thread (the memory store holds a lock of the thread that opened it), and taken from storage as the queue takes
 * them. The updates are added to the queue side by side, a few at a time.
 */
public final class IndexRebuild {

    private static final Logger LOG = LoggerFactory.getLogger(IndexRebuild.class);

    static final int CONCURRENT_ADDS = 8;

    private final RxJsonPersistence persistence;
    private final IndexQueue queue;
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
     * @param namespace   the namespace
     * @param entities    the entity types, read in this order
     */
    public IndexRebuild(RxJsonPersistence persistence, IndexQueue queue, String namespace, List<String> entities) {
        this.persistence = persistence;
        this.queue = queue;
        this.namespace = namespace;
        this.entities = List.copyOf(entities);
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
            running = rebuild().subscribe(
                    added -> LOG.info("The search index is rebuilt from storage: {} versions added to the index queue",
                            added),
                    error -> LOG.error("The rebuild of the search index failed; what was added to the index queue is"
                            + " sent, and a start with the rebuild asked for again adds every version", error));
        }
    }

    /**
     * Stops a rebuild that runs, and closes its read transaction. What it has added to the queue stays there.
     */
    public void stop() {
        synchronized (lock) {
            stopped = true;
            if (running != null && !running.isDisposed()) {
                LOG.warn("The rebuild of the search index was stopped before it was done");
                running.dispose();
            }
        }
        executor.close(); // waits for the read transaction to be closed
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
