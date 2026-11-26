package io.descoped.lds.core.search;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.PersistenceDeletePolicy;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.IndexEntry;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.IndexUpdate;
import io.descoped.lds.api.search.SearchQuery;
import io.descoped.lds.core.persistence.memory.MemoryInitializer;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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
        /* how often it was asked how many updates it holds, and held some */
        final AtomicInteger heldWhenAsked = new AtomicInteger();
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
            return queue.lag().doOnSuccess(lag -> {
                if (lag.updates() > 0) {
                    heldWhenAsked.incrementAndGet();
                }
            });
        }

        @Override
        public Single<Boolean> holds(String namespace, String entity, String id) {
            return queue.holds(namespace, entity, id);
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
            rebuild.stop(Duration.ofSeconds(5));
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
        rebuild = new IndexRebuild(persistence, queue, new SimpleSearchIndex(), "ns", List.of("Person", "Company"));
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
        rebuild = new IndexRebuild(counted, queue, new SimpleSearchIndex(), "ns", List.of("Person"));

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
        rebuild = new IndexRebuild(counting(persistence, read), queue, new SimpleSearchIndex(), "ns",
                List.of("Person"));
        rebuild.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> read.get() > 0);

        rebuild.stop(Duration.ofSeconds(5));

        assertThat(rebuild.isDone()).isTrue();
        write("Person", "after", FEB);
    }

    /**
     * A store that holds the thread of the rebuild, and does not let go when it is interrupted, holds the stop up for
     * the time it is told and a second for the thread, not until the store lets go.
     */
    @Test
    void aStoreThatHoldsTheThreadHoldsTheStopNoLongerThanItIsTold() throws InterruptedException {
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        RxJsonPersistence holding = (RxJsonPersistence) Proxy.newProxyInstance(
                RxJsonPersistence.class.getClassLoader(), new Class<?>[]{RxJsonPersistence.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("createTransaction")) {
                        opening.countDown();
                        awaitUninterruptibly(hold);
                    }
                    try {
                        return method.invoke(persistence, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        rebuild = new IndexRebuild(holding, queue, new SimpleSearchIndex(), "ns", List.of("Person"));
        rebuild.start();
        assertThat(opening.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            CompletableFuture<Void> stop = CompletableFuture.runAsync(() -> rebuild.stop(Duration.ofMillis(200)));

            assertThat(stop).succeedsWithin(Duration.ofSeconds(3));
        } finally {
            hold.countDown();
        }
    }

    /* Waits for the latch, and keeps waiting when interrupted: a thread that does not let go. */
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
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
        stopped.stop(Duration.ofSeconds(5));
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
        // the index can only be added to: nothing in it is cleared
        IndexQueueWorkerLifecycleTest.HeldIndex index = new IndexQueueWorkerLifecycleTest.HeldIndex();
        IndexQueueWorker worker = new IndexQueueWorker(queue, index, persistence, IndexEntriesTest.withoutAModel(),
                Duration.ofMillis(20));
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

    /**
     * When the queue is empty after the rebuild, the index no longer holds what the rebuild did not send again: an
     * entry of a version storage does not hold. Another namespace keeps its entries, and so does an entry taken less
     * than the margin before the rebuild: an old entry left is the lesser fault.
     */
    @Test
    void theEntriesTheRebuildDidNotSendAgainAreRemovedWhenTheQueueIsEmpty() throws InterruptedException {
        MemoryIndexQueueTest.MovingClock clock = new MemoryIndexQueueTest.MovingClock();
        SimpleSearchIndex index = new SimpleSearchIndex(clock);
        index.put(List.of(IndexEntry.written("ns", "Person", "gone", JAN, Map.of()),
                IndexEntry.written("other", "Person", "elsewhere", JAN, Map.of()))).blockingAwait();
        clock.move(Duration.ofMinutes(10)); // the index took them ten minutes before the rebuild
        index.put(List.of(IndexEntry.written("ns", "Person", "lately", JAN, Map.of()))).blockingAwait();
        clock.move(Duration.ofSeconds(30)); // and this thirty seconds before, within the margin
        write("Person", "ola", JAN);
        IndexQueueWorker worker = new IndexQueueWorker(queue, index, persistence, IndexEntriesTest.withoutAModel(),
                Duration.ofMillis(5));
        worker.start();
        try {
            rebuild = new IndexRebuild(persistence, queue, index, "ns", List.of("Person", "Company"),
                    Duration.ofMillis(5));
            rebuild.start();
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(5)).until(rebuild::isDone);
        } finally {
            worker.stop(5, TimeUnit.SECONDS);
        }

        assertThat(everyVersionIn(index, "ns")).containsExactlyInAnyOrder("ola", "lately");
        assertThat(everyVersionIn(index, "other")).containsExactly("elsewhere");
    }

    /**
     * The clean-up waits until the queue is empty: until then the index has not taken all the rebuild sent, and the
     * log says that it waits, and the lag of the queue.
     */
    @Test
    void theCleanUpWaitsUntilTheQueueIsEmpty() {
        MemoryIndexQueueTest.MovingClock clock = new MemoryIndexQueueTest.MovingClock();
        SimpleSearchIndex index = new SimpleSearchIndex(clock);
        index.put(List.of(IndexEntry.written("ns", "Person", "gone", JAN, Map.of()))).blockingAwait();
        clock.move(Duration.ofMinutes(10)); // the index took it ten minutes before the rebuild
        write("Person", "ola", JAN);
        Logger logger = (Logger) LoggerFactory.getLogger(IndexRebuild.class);
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        try {
            // the log says at every look that the clean-up waits
            rebuild = new IndexRebuild(persistence, queue, index, "ns", List.of("Person"), Duration.ofMillis(5),
                    Duration.ZERO);
            rebuild.start();

            // the rebuild has added its update and found it in the queue twice, and has not cleaned up
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(5))
                    .until(() -> queue.heldWhenAsked.get() >= 2);
        } finally {
            logger.detachAppender(lines);
        }
        assertThat(rebuild.isDone()).isFalse();
        assertThat(everyVersionIn(index, "ns")).containsExactly("gone");
        assertThat(lines.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains("waited") && line.contains("it holds 1 updates"));

        queue.remove(queue.take(10, Duration.ofMinutes(1)).blockingGet()).blockingAwait();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(5)).until(rebuild::isDone);
        assertThat(everyVersionIn(index, "ns")).isEmpty();
    }

    private static List<String> everyVersionIn(SimpleSearchIndex index, String namespace) {
        return index.search(new SearchQuery(namespace, "*:*", Set.of(), ZonedDateTime.parse("2100-01-01T00:00:00Z"),
                true, 0, 100)).blockingGet().hits().stream().map(hit -> hit.key().id()).toList();
    }
}
