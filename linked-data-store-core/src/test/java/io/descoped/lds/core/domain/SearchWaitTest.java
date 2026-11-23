package io.descoped.lds.core.domain;

import io.descoped.lds.api.search.IndexEntry;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.IndexUpdate;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.search.SearchPage;
import io.descoped.lds.api.search.SearchQuery;
import io.descoped.lds.core.search.MemoryIndexQueue;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A write that asks to be searchable waits until the queue holds no update of its document, then for the index to make
 * what it took searchable, and no longer than the longest wait.
 */
class SearchWaitTest {

    private static final Duration POLL = Duration.ofMillis(10);
    private static final Duration LONGEST = Duration.ofMillis(500);

    private final MemoryIndexQueue queue = new MemoryIndexQueue();
    private final CountingIndex index = new CountingIndex();
    private final SearchWait wait = new SearchWait(queue, index, POLL, LONGEST);

    static final class CountingIndex implements SearchIndex {
        final AtomicInteger refreshes = new AtomicInteger();
        volatile boolean down;

        @Override
        public Completable refresh() {
            return Completable.defer(() -> {
                if (down) {
                    return Completable.error(new IllegalStateException("the index is down"));
                }
                refreshes.incrementAndGet();
                return Completable.complete();
            });
        }

        @Override
        public Completable put(List<IndexEntry> entries) {
            return Completable.complete();
        }

        @Override
        public Completable health() {
            return Completable.complete();
        }

        @Override
        public Single<SearchPage> search(SearchQuery query) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void aWriteWhoseUpdateIsSentIsMadeSearchableAtOnce() {
        assertThat(wait.until("ns", "Person", "ola")).isTrue();
        assertThat(index.refreshes).hasValue(1);
    }

    @Test
    void aWriteWaitsUntilTheQueueHoldsNoUpdateOfItsDocument() throws Exception {
        add("ola");
        add("kari");
        CompletableFuture<Void> sent = CompletableFuture.runAsync(() -> {
            List<IndexQueue.Entry> taken = queue.take(10, Duration.ofMinutes(1)).blockingGet();
            queue.remove(taken.stream().filter(entry -> entry.update().id().equals("ola")).toList()).blockingAwait();
        }, CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS));

        long start = System.nanoTime();
        assertThat(wait.until("ns", "Person", "ola")).isTrue();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(100));
        assertThat(sent).isDone();
        assertThat(index.refreshes).hasValue(1);
        assertThat(queue.holds("ns", "Person", "kari").blockingGet()).isTrue();
    }

    @Test
    void aWriteIsAnsweredAfterTheLongestWaitWhenItsUpdateIsNotSent() {
        add("ola");

        long start = System.nanoTime();
        assertThat(wait.until("ns", "Person", "ola")).isFalse();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(LONGEST, LONGEST.multipliedBy(4));
        assertThat(index.refreshes).hasValue(0);
    }

    @Test
    void aWriteIsAnsweredWhenTheIndexCannotMakeItSearchable() {
        index.down = true;

        assertThat(wait.until("ns", "Person", "ola")).isFalse();
    }

    @Test
    void aWriteAsksToBeSearchableWithSearchableTrue() {
        assertThat(SearchWait.asked(parameters("searchable", "true"))).isTrue();
        assertThat(SearchWait.asked(parameters("searchable", "TRUE"))).isTrue();
        assertThat(SearchWait.asked(parameters("searchable", "false"))).isFalse();
        assertThat(SearchWait.asked(Map.of())).isFalse();
        // a write that is to be searchable is done before it is answered, whatever sync says
        Map<String, Deque<String>> both = new java.util.HashMap<>(parameters("searchable", "true"));
        both.putAll(parameters("sync", "false"));
        assertThat(SyncParameter.waits(both)).isTrue();
        assertThat(SyncParameter.waits(parameters("sync", "false"))).isFalse();
    }

    private void add(String id) {
        queue.add(new IndexUpdate("ns", "Person", id, ZonedDateTime.parse("2026-01-01T00:00:00Z"),
                IndexUpdate.Operation.CREATE_OR_OVERWRITE)).blockingAwait();
    }

    private static Map<String, Deque<String>> parameters(String name, String value) {
        return Map.of(name, new ArrayDeque<>(List.of(value)));
    }
}
