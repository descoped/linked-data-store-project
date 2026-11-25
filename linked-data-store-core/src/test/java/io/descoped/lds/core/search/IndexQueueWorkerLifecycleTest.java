package io.descoped.lds.core.search;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.IndexUpdate;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.search.IndexEntry;
import io.descoped.lds.api.search.SearchPage;
import io.descoped.lds.api.search.SearchQuery;
import io.descoped.lds.core.persistence.memory.MemoryInitializer;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The life of the worker: it sends nothing before it is started; a stop lets the round that is sending finish, so
 * its updates are either taken by the index and removed or left in the queue; a stop that waits too long leaves the
 * update; nothing is sent after a stop and its thread ends; stopping twice or before a start does no harm; a queue
 * that fails does not stop it.
 */
class IndexQueueWorkerLifecycleTest {

    private static final ZonedDateTime VERSION = ZonedDateTime.parse("2026-01-01T01:00:00+01:00[Europe/Oslo]");
    private static final Duration POLL = Duration.ofMillis(20);

    /**
     * An index that answers on a thread of its own, as Solr's client does, and can be held before it answers.
     */
    static class HeldIndex implements SearchIndex {
        final List<String> taken = new CopyOnWriteArrayList<>();
        final CountDownLatch sending = new CountDownLatch(1);
        volatile CountDownLatch hold = new CountDownLatch(0);

        @Override
        public Completable put(List<IndexEntry> entries) {
            return Completable.create(emitter -> Thread.ofPlatform().start(() -> {
                sending.countDown();
                try {
                    hold.await();
                    entries.stream().filter(entry -> !entry.deleted()).forEach(entry -> taken.add(entry.id()));
                    emitter.onComplete();
                } catch (InterruptedException e) {
                    emitter.onError(e);
                }
            }));
        }

        @Override
        public Completable removeOlderThan(String namespace, Duration age) {
            return Completable.complete();
        }

        @Override
        public Completable refresh() {
            return Completable.complete();
        }

        @Override
        public Completable health() {
            return Completable.complete();
        }

        @Override
        public void close() {
            // nothing to let go of
        }

        @Override
        public Single<SearchPage> search(SearchQuery query) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A queue in memory whose first takes fail.
     */
    static final class FailingQueue implements IndexQueue {
        final MemoryIndexQueue queue = new MemoryIndexQueue();
        final AtomicInteger failures;

        FailingQueue(int failures) {
            this.failures = new AtomicInteger(failures);
        }

        @Override
        public Completable add(IndexUpdate update) {
            return queue.add(update);
        }

        @Override
        public Single<List<Entry>> take(int max, Duration lease) {
            return failures.getAndDecrement() > 0
                    ? Single.error(new IllegalStateException("the queue's database does not answer"))
                    : queue.take(max, lease);
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

    private final RxJsonPersistence persistence = new MemoryInitializer().initialize("ns", Map.of(), Set.of("Person"));
    private final HeldIndex index = new HeldIndex();
    private IndexQueueWorker worker;

    @AfterEach
    void stopWorker() throws InterruptedException {
        index.hold.countDown();
        if (worker != null) {
            worker.stop(5, TimeUnit.SECONDS);
        }
    }

    private IndexQueueWorker worker(IndexQueue queue) {
        worker = new IndexQueueWorker(queue, index, persistence, IndexEntriesTest.withoutAModel(), POLL);
        return worker;
    }

    private void storeAndAdd(IndexQueue queue, String id) {
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.createOrOverwrite(tx, new JsonDocument(new DocumentKey("ns", "Person", id, VERSION),
                    JsonTools.toJsonNode("{\"name\":\"" + id + "\"}")), null).blockingAwait();
        }
        queue.add(new IndexUpdate("ns", "Person", id, VERSION, IndexUpdate.Operation.CREATE_OR_OVERWRITE)).blockingAwait();
    }

    private static long inQueue(IndexQueue queue) {
        return queue.lag().blockingGet().updates();
    }

    @Test
    void nothingIsSentBeforeTheWorkerIsStarted() throws InterruptedException {
        MemoryIndexQueue queue = new MemoryIndexQueue();
        worker(queue);
        storeAndAdd(queue, "early");

        Thread.sleep(POLL.multipliedBy(10).toMillis());
        assertThat(index.taken).isEmpty();

        worker.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> index.taken.contains("early"));
        await().atMost(Duration.ofSeconds(5)).until(() -> inQueue(queue) == 0);
    }

    @Test
    void aStopLetsTheRoundThatIsSendingFinishAndItsUpdateIsRemoved() throws Exception {
        MemoryIndexQueue queue = new MemoryIndexQueue();
        index.hold = new CountDownLatch(1);
        worker(queue).start();
        storeAndAdd(queue, "inflight");
        assertThat(index.sending.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(() -> {
            try {
                worker.stop(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(300);
        assertThat(stopping).as("the stop waits for the round").isNotDone();

        index.hold.countDown();
        stopping.get(10, TimeUnit.SECONDS);

        assertThat(index.taken).containsExactly("inflight");
        assertThat(inQueue(queue)).isZero();
        assertThat(worker.isTerminated()).isTrue();
    }

    @Test
    void aStopThatWaitsLongerThanItIsToldEndsAndTheUpdateIsLeftInTheQueue() throws Exception {
        MemoryIndexQueue queue = new MemoryIndexQueue();
        index.hold = new CountDownLatch(1);
        worker(queue).start();
        storeAndAdd(queue, "slow");
        assertThat(index.sending.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        worker.stop(200, TimeUnit.MILLISECONDS);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(index.taken).isEmpty();
        assertThat(inQueue(queue)).as("left, to be taken again when its lease ends").isEqualTo(1);
    }

    /**
     * A stop on a thread that is interrupted while a round is sending does not wait for it, and still ends the
     * worker: no round follows, and its thread ends.
     */
    @Test
    void aStopThatIsInterruptedWhileARoundIsSendingStillEndsTheWorker() throws Exception {
        MemoryIndexQueue queue = new MemoryIndexQueue();
        index.hold = new CountDownLatch(1);
        worker(queue).start();
        storeAndAdd(queue, "inflight");
        assertThat(index.sending.await(5, TimeUnit.SECONDS)).isTrue();

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> worker.stop(10, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted(); // cleared for what comes after
        }
        index.hold.countDown();

        await().atMost(Duration.ofSeconds(5)).until(worker::isTerminated);
    }

    /**
     * An index that holds the thread of the worker, and does not let go when it is interrupted, holds the stop up
     * for the time it is told and a second for the thread, not twice the time.
     */
    @Test
    void aRoundThatHoldsTheThreadHoldsTheStopNoLongerThanItIsTold() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SearchIndex holdingTheThread = new HeldIndex() {
            @Override
            public Completable put(List<IndexEntry> entries) {
                return Completable.fromAction(() -> { // on the thread of the worker
                    entered.countDown();
                    boolean interrupted = false;
                    while (true) {
                        try {
                            release.await();
                            break;
                        } catch (InterruptedException e) {
                            interrupted = true; // not let go
                        }
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        };
        MemoryIndexQueue queue = new MemoryIndexQueue();
        IndexQueueWorker holding = new IndexQueueWorker(queue, holdingTheThread, persistence,
                IndexEntriesTest.withoutAModel(), POLL);
        try {
            holding.start();
            storeAndAdd(queue, "held");
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            holding.stop(2, TimeUnit.SECONDS);

            // two seconds for the round and one for the thread; not two for each
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(3500));
        } finally {
            release.countDown();
            holding.stop(5, TimeUnit.SECONDS);
        }
        await().atMost(Duration.ofSeconds(5)).until(holding::isTerminated);
    }

    @Test
    void afterAStopNothingIsSentAndTheThreadHasEnded() throws InterruptedException {
        MemoryIndexQueue queue = new MemoryIndexQueue();
        worker(queue).start();
        storeAndAdd(queue, "before");
        await().atMost(Duration.ofSeconds(5)).until(() -> index.taken.contains("before"));

        worker.stop(5, TimeUnit.SECONDS);
        storeAndAdd(queue, "after");
        Thread.sleep(POLL.multipliedBy(10).toMillis());

        assertThat(index.taken).containsExactly("before");
        assertThat(inQueue(queue)).isEqualTo(1);
        assertThat(worker.isTerminated()).isTrue();
    }

    @Test
    void stoppingTwiceOrBeforeAStartDoesNoHarm() throws InterruptedException {
        IndexQueueWorker never = worker(new MemoryIndexQueue());
        never.stop(1, TimeUnit.SECONDS);
        never.stop(1, TimeUnit.SECONDS);
        never.start(); // a stopped worker does not start again
        assertThat(never.isTerminated()).isTrue();
    }

    @Test
    void aQueueThatFailsDoesNotStopTheWorker() {
        FailingQueue queue = new FailingQueue(3);
        worker(queue).start();
        storeAndAdd(queue, "later");

        await().atMost(Duration.ofSeconds(5)).until(() -> index.taken.contains("later"));
        assertThat(queue.failures.get()).isNegative();
    }
}
