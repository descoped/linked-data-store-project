package io.descoped.lds.core.search;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.IndexUpdate;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.search.SearchResponse;
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
    static final class HeldIndex implements SearchIndex {
        final List<String> taken = new CopyOnWriteArrayList<>();
        final CountDownLatch sending = new CountDownLatch(1);
        volatile CountDownLatch hold = new CountDownLatch(0);

        @Override
        public Completable createOrOverwrite(JsonDocument document) {
            return createOrOverwrite(List.of(document));
        }

        @Override
        public Completable createOrOverwrite(Collection<JsonDocument> documents) {
            return Completable.create(emitter -> Thread.ofPlatform().start(() -> {
                sending.countDown();
                try {
                    hold.await();
                    documents.forEach(document -> taken.add(document.key().id()));
                    emitter.onComplete();
                } catch (InterruptedException e) {
                    emitter.onError(e);
                }
            }));
        }

        @Override
        public Completable delete(JsonDocument document) {
            return Completable.complete();
        }

        @Override
        public Completable deleteAll() {
            return Completable.complete();
        }

        @Override
        public Single<SearchResponse> search(String query, Set<String> typeFilter, long from, long size) {
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
        worker = new IndexQueueWorker(queue, index, persistence, POLL);
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
