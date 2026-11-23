package io.descoped.lds.core.search;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.PersistenceDeletePolicy;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.IndexUpdate;
import io.descoped.lds.core.persistence.memory.MemoryInitializer;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

/**
 * The rebuild of the search index from storage: what it adds to the queue, that it reads storage only as fast as the
 * queue takes, that a stop or a queue that fails leaves the store as it was, and that the worker then sends it all.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class IndexRebuildTest {

    private static final ZonedDateTime JAN = ZonedDateTime.parse("2026-01-01T00:00:00Z");
    private static final ZonedDateTime FEB = ZonedDateTime.parse("2026-02-01T00:00:00Z");
    private static final ZonedDateTime MAR = ZonedDateTime.parse("2026-03-01T00:00:00Z");

    /**
     * A queue in memory that counts what is added, and can hold every add or fail them.
     */
    static final class WatchedQueue implements IndexQueue {
        final MemoryIndexQueue queue = new MemoryIndexQueue();
        final List<IndexUpdate> added = new CopyOnWriteArrayList<>();
        volatile boolean hold;
        volatile boolean fail;

        @Override
        public Completable add(IndexUpdate update) {
            if (fail) {
                return Completable.error(new IllegalStateException("the queue's database does not answer"));
            }
            if (hold) {
                return Completable.never();
            }
            return queue.add(update).doOnComplete(() -> added.add(update));
        }

        @Override
        public Single<List<Entry>> take(int max, Duration lease) {
            return queue.take(max, lease);
        }

        @Override
        public Completable remove(Collection<Entry> entries) {
            return queue.remove(entries);
        }

        @Override
        public Completable postpone(Collection<Entry> entries, Duration delay) {
            return queue.postpone(entries, delay);
        }

        @Override
        public Single<Lag> lag() {
            return queue.lag();
        }

        @Override
        public boolean durable() {
            return false;
        }

        @Override
        public void close() {
            queue.close();
        }
    }

    private final RxJsonPersistence persistence = new MemoryInitializer().initialize("ns", Map.of(),
            Set.of("Person", "Company"));
    private final WatchedQueue queue = new WatchedQueue();
    private IndexRebuild rebuild;

    @AfterEach
    void stop() {
        if (rebuild != null) {
            rebuild.stop();
        }
    }

    /* The store, counting the versions its read of every version gives. */
    @SuppressWarnings("unchecked")
    private static RxJsonPersistence counting(RxJsonPersistence store, AtomicInteger read) {
        return (RxJsonPersistence) Proxy.newProxyInstance(RxJsonPersistence.class.getClassLoader(),
                new Class<?>[]{RxJsonPersistence.class}, (proxy, method, arguments) -> {
                    Object result;
                    try {
                        result = method.invoke(store, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    return method.getName().equals("readAllDocumentVersions")
                            ? ((Flowable<JsonDocument>) result).doOnNext(_ -> read.incrementAndGet())
                            : result;
                });
    }

    private IndexRebuild rebuild() {
        rebuild = new IndexRebuild(persistence, queue, "ns", List.of("Person", "Company"));
        return rebuild;
    }

    private void write(String entity, String id, ZonedDateTime version) {
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.createOrOverwrite(tx, new JsonDocument(new DocumentKey("ns", entity, id, version),
                    JsonTools.toJsonNode("{\"name\":\"" + id + " " + version.getMonth() + "\"}")), null).blockingAwait();
        }
    }

    private void delete(String entity, String id, ZonedDateTime version) {
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.markDocumentDeleted(tx, "ns", entity, id, version, PersistenceDeletePolicy.FAIL_IF_INCOMING_LINKS)
                    .blockingAwait();
        }
    }

    @Test
    void everyVersionOfEveryEntityTypeIsAddedTheDeletedOnesIncluded() {
        write("Person", "ola", JAN);
        write("Person", "ola", FEB);
        write("Person", "kari", JAN);
        delete("Person", "kari", MAR);
        write("Company", "acme", FEB);

        long added = rebuild().rebuild().blockingGet();

        assertThat(added).isEqualTo(5);
        assertThat(queue.added)
                .extracting(IndexUpdate::entity, IndexUpdate::id, update -> update.version().toInstant(), IndexUpdate::operation)
                .containsExactlyInAnyOrder(
                        tuple("Person", "ola", JAN.toInstant(), IndexUpdate.Operation.CREATE_OR_OVERWRITE),
                        tuple("Person", "ola", FEB.toInstant(), IndexUpdate.Operation.CREATE_OR_OVERWRITE),
                        tuple("Person", "kari", JAN.toInstant(), IndexUpdate.Operation.CREATE_OR_OVERWRITE),
                        tuple("Person", "kari", MAR.toInstant(), IndexUpdate.Operation.DELETE),
                        tuple("Company", "acme", FEB.toInstant(), IndexUpdate.Operation.CREATE_OR_OVERWRITE));
        assertThat(queue.added).allMatch(update -> update.namespace().equals("ns"));
    }

    @Test
    void anEmptyStoreAddsNothing() {
        assertThat(rebuild().rebuild().blockingGet()).isZero();
        assertThat(queue.added).isEmpty();
    }

    /**
     * The queue sets the pace: while it takes nothing, a few versions are read ahead, not the whole store.
     */
    @Test
    void storageIsReadOnlyAsFastAsTheQueueTakes() {
        for (int i = 0; i < 300; i++) {
            write("Person", "p" + String.format("%03d", i), JAN);
        }
        AtomicInteger read = new AtomicInteger();
        RxJsonPersistence counted = counting(persistence, read);
        queue.hold = true;
        rebuild = new IndexRebuild(counted, queue, "ns", List.of("Person"));

        rebuild.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> read.get() > 0);
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> read.get() < 50);

        assertThat(read.get()).isLessThanOrEqualTo(IndexRebuild.CONCURRENT_ADDS * 2);
    }

    /**
     * A stop closes the read transaction on the thread that opened it, so the store takes writes again; the memory
     * store would otherwise keep its lock.
     */
    @Test
    void aStopEndsTheRebuildAndTheStoreTakesWritesAgain() {
        for (int i = 0; i < 50; i++) {
            write("Person", "p" + i, JAN);
        }
        AtomicInteger read = new AtomicInteger();
        queue.hold = true;
        rebuild = new IndexRebuild(counting(persistence, read), queue, "ns", List.of("Person"));
        rebuild.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> read.get() > 0);

        rebuild.stop();

        assertThat(rebuild.isDone()).isTrue();
        write("Person", "after", FEB);
    }

    @Test
    void aQueueThatFailsEndsTheRebuildAndTheStoreTakesWritesAgain() {
        write("Person", "ola", JAN);
        queue.fail = true;
        rebuild().start();

        await().atMost(Duration.ofSeconds(5)).until(rebuild::isDone);

        write("Person", "after", FEB);
        assertThat(queue.added).isEmpty();
    }

    @Test
    void aRebuildIsStartedOnceAndNotAfterAStop() {
        IndexRebuild stopped = rebuild();
        stopped.stop();
        stopped.start();
        assertThat(stopped.isDone()).isFalse();
        assertThat(queue.added).isEmpty();
    }

    /**
     * With the worker: what the rebuild adds reaches the index, every version that is not a deletion, and the queue
     * is emptied. The index is never cleared, so what it held is answered all the while.
     */
    @Test
    void theWorkerSendsWhatTheRebuildAddsAndTheIndexIsNeverCleared() throws InterruptedException {
        write("Person", "ola", JAN);
        write("Person", "ola", FEB);
        write("Person", "kari", JAN);
        delete("Person", "kari", MAR);
        IndexQueueWorkerLifecycleTest.HeldIndex index = new IndexQueueWorkerLifecycleTest.HeldIndex() {
            @Override
            public Completable deleteAll() {
                return Completable.error(new AssertionError("a rebuild does not clear the index"));
            }
        };
        IndexQueueWorker worker = new IndexQueueWorker(queue, index, persistence, Duration.ofMillis(20));
        worker.start();
        try {
            rebuild().start();
            await().atMost(Duration.ofSeconds(10)).until(() -> rebuild.isDone()
                    && queue.lag().blockingGet().updates() == 0);
        } finally {
            worker.stop(5, TimeUnit.SECONDS);
        }

        assertThat(index.taken).containsExactlyInAnyOrder("ola", "ola", "kari");
    }
}
