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
import io.descoped.lds.api.search.Language;
import io.descoped.lds.api.search.SearchPage;
import io.descoped.lds.api.search.SearchQuery;
import io.descoped.lds.core.persistence.memory.MemoryInitializer;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The worker sends what the queue holds, as storage holds it, and keeps what the index does not take.
 */
class IndexQueueWorkerTest {

    private static final ZonedDateTime VERSION = ZonedDateTime.parse("2026-01-01T01:00:00+01:00[Europe/Oslo]");

    /**
     * An index that keeps what it is sent, and refuses what it is told to.
     */
    static final class FakeIndex implements SearchIndex {
        final List<DocumentKey> written = new CopyOnWriteArrayList<>();
        final List<String> writtenNames = new CopyOnWriteArrayList<>();
        final List<DocumentKey> deleted = new CopyOnWriteArrayList<>();
        final List<Integer> batches = new CopyOnWriteArrayList<>();
        volatile boolean down;
        volatile String refusedId;

        @Override
        public Completable put(List<IndexEntry> entries) {
            return Completable.defer(() -> {
                if (down || entries.stream().anyMatch(entry -> entry.id().equals(refusedId))) {
                    return Completable.error(new IllegalStateException("the index refuses"));
                }
                batches.add(entries.size());
                entries.forEach(entry -> {
                    DocumentKey key = new DocumentKey(entry.namespace(), entry.entity(), entry.id(),
                            entry.version().withZoneSameInstant(ZoneOffset.UTC));
                    if (entry.deleted()) {
                        deleted.add(key);
                    } else {
                        written.add(key);
                        writtenNames.add(entry.texts().get(Language.NONE).getFirst());
                    }
                });
                return Completable.complete();
            });
        }

        @Override
        public Completable health() {
            return down ? Completable.error(new IllegalStateException("the index does not answer")) : Completable.complete();
        }

        @Override
        public Single<SearchPage> search(SearchQuery query) {
            throw new UnsupportedOperationException();
        }
    }

    private final MemoryIndexQueueTest.MovingClock clock = new MemoryIndexQueueTest.MovingClock();
    private final MemoryIndexQueue queue = new MemoryIndexQueue(clock);
    private final FakeIndex index = new FakeIndex();
    private final RxJsonPersistence persistence = new MemoryInitializer().initialize("ns", Map.of(), Set.of("Person"));
    private final IndexQueueWorker worker = new IndexQueueWorker(queue, index, persistence,
            IndexEntriesTest.withoutAModel(), Duration.ofMillis(20));

    private static ZonedDateTime utc(ZonedDateTime version) {
        return version.withZoneSameInstant(ZoneOffset.UTC);
    }

    private void store(String id, ZonedDateTime version, String name) {
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.createOrOverwrite(tx, new JsonDocument(new DocumentKey("ns", "Person", id, version),
                    JsonTools.toJsonNode("{\"name\":\"" + name + "\"}")), null).blockingAwait();
        }
    }

    private void add(String id, IndexUpdate.Operation operation) {
        queue.add(new IndexUpdate("ns", "Person", id, VERSION, operation)).blockingAwait();
    }

    @Test
    void aWrittenDocumentIsSentAsStorageHoldsItAtItsVersionAndTheUpdateIsRemoved() {
        store("ola", VERSION, "Ola");
        store("ola", VERSION.plusHours(1), "Ola Nordmann");
        add("ola", IndexUpdate.Operation.CREATE_OR_OVERWRITE);

        assertThat(worker.round().blockingGet()).isTrue();

        assertThat(index.written).containsExactly(new DocumentKey("ns", "Person", "ola", utc(VERSION)));
        assertThat(index.writtenNames).containsExactly("Ola");
        assertThat(queue.lag().blockingGet().updates()).isZero();
        assertThat(worker.round().blockingGet()).isFalse();
    }

    @Test
    void theReadOfARoundLeavesTheStoreFreeForTheNextWrite() throws Exception {
        // the memory store holds a lock of the thread that opened a transaction until it is closed on that thread; a
        // lock left behind makes the next write wait for ever, so the writes run with a time limit
        CompletableFuture<Void> writesAndRounds = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 5; i++) {
                store("w" + i, VERSION, "W");
                add("w" + i, IndexUpdate.Operation.CREATE_OR_OVERWRITE);
                worker.round().blockingGet();
            }
            store("next", VERSION.plusHours(1), "Next");
        });

        writesAndRounds.get(10, TimeUnit.SECONDS);
        assertThat(index.written).hasSize(5);
    }

    @Test
    void aDeleteIsSentAsADeleteOfItsVersion() {
        add("gone", IndexUpdate.Operation.DELETE);

        worker.round().blockingGet();

        assertThat(index.deleted).containsExactly(new DocumentKey("ns", "Person", "gone", utc(VERSION)));
        assertThat(index.written).isEmpty();
        assertThat(queue.lag().blockingGet().updates()).isZero();
    }

    @Test
    void anUpdateWhoseDocumentStorageDoesNotHaveIsRemovedWithoutBeingSent() {
        add("never-stored", IndexUpdate.Operation.CREATE_OR_OVERWRITE);

        worker.round().blockingGet();

        assertThat(index.written).isEmpty();
        assertThat(queue.lag().blockingGet().updates()).isZero();
    }

    @Test
    void theUpdatesOfARoundAreSentInOneBatchAndAtMostOneHundred() {
        for (int i = 0; i < 150; i++) {
            store("p" + i, VERSION, "P" + i);
            add("p" + i, IndexUpdate.Operation.CREATE_OR_OVERWRITE);
        }

        worker.round().blockingGet();
        worker.round().blockingGet();

        assertThat(index.batches).containsExactly(100, 50);
    }

    @Test
    void whileTheIndexIsDownTheUpdatesStayAndArePutOffAndThenSent() {
        store("a", VERSION, "A");
        add("a", IndexUpdate.Operation.CREATE_OR_OVERWRITE);
        add("b", IndexUpdate.Operation.DELETE);
        index.down = true;

        worker.round().blockingGet();

        assertThat(queue.lag().blockingGet().updates()).isEqualTo(2);
        assertThat(worker.round().blockingGet()).as("put off for a second").isFalse();

        index.down = false;
        clock.move(Duration.ofSeconds(1));
        worker.round().blockingGet();

        assertThat(index.written).extracting(DocumentKey::id).containsExactly("a");
        assertThat(index.deleted).extracting(DocumentKey::id).containsExactly("b");
        assertThat(queue.lag().blockingGet().updates()).isZero();
    }

    @Test
    void anUpdateTheIndexRefusesDoesNotHoldBackTheOthers() {
        for (String id : List.of("a", "bad", "c")) {
            store(id, VERSION, id);
            add(id, IndexUpdate.Operation.CREATE_OR_OVERWRITE);
        }
        index.refusedId = "bad";

        worker.round().blockingGet();

        assertThat(index.written).extracting(DocumentKey::id).containsExactly("a", "c");
        List<IndexQueue.Entry> left = queue.take(10, Duration.ofMinutes(1)).blockingGet();
        assertThat(left).isEmpty(); // put off
        clock.move(Duration.ofSeconds(1));
        assertThat(queue.take(10, Duration.ofMinutes(1)).blockingGet())
                .extracting(entry -> entry.update().id(), IndexQueue.Entry::attempts).containsExactly(
                        org.assertj.core.groups.Tuple.tuple("bad", 1));
    }

    @Test
    void theWaitBeforeATryAgainDoublesFromASecondToAMinute() {
        assertThat(IndexQueueWorker.delay(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(IndexQueueWorker.delay(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(IndexQueueWorker.delay(5)).isEqualTo(Duration.ofSeconds(32));
        assertThat(IndexQueueWorker.delay(6)).isEqualTo(Duration.ofMinutes(1));
        assertThat(IndexQueueWorker.delay(1000)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void aStartedWorkerSendsWhatIsAddedAndStopsWhenAsked() throws InterruptedException {
        worker.start();
        try {
            store("late", VERSION, "Late");
            add("late", IndexUpdate.Operation.CREATE_OR_OVERWRITE);

            await().atMost(Duration.ofSeconds(5)).until(() -> index.written.size() == 1);
        } finally {
            worker.stop(5, TimeUnit.SECONDS);
        }
        add("after", IndexUpdate.Operation.DELETE);
        Thread.sleep(100);
        assertThat(index.deleted).isEmpty();
    }
}
