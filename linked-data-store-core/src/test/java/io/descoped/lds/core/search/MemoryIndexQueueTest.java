package io.descoped.lds.core.search;

import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.IndexUpdate;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queue in memory: taken in order, held for a lease, put off with one attempt more, removed when taken by the
 * index.
 */
class MemoryIndexQueueTest {

    static final class MovingClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void move(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovingClock clock = new MovingClock();
    private final MemoryIndexQueue queue = new MemoryIndexQueue(clock);

    static IndexUpdate update(String id) {
        return new IndexUpdate("ns", "Person", id, ZonedDateTime.parse("2026-01-01T01:00:00+01:00[Europe/Oslo]"),
                IndexUpdate.Operation.CREATE_OR_OVERWRITE);
    }

    private List<String> taken(int max) {
        return queue.take(max, Duration.ofMinutes(1)).blockingGet().stream().map(entry -> entry.update().id()).toList();
    }

    @Test
    void updatesAreTakenInTheOrderTheyWereAddedAndAtMostTheNumberAsked() {
        for (String id : List.of("c", "a", "b")) {
            queue.add(update(id)).blockingAwait();
        }

        assertThat(taken(2)).containsExactly("c", "a");
        assertThat(taken(10)).containsExactly("b");
    }

    @Test
    void anUpdateThatIsTakenIsNotTakenAgainUntilItsLeaseHasEnded() {
        queue.add(update("a")).blockingAwait();
        assertThat(taken(10)).containsExactly("a");

        clock.move(Duration.ofSeconds(59));
        assertThat(taken(10)).isEmpty();
        clock.move(Duration.ofSeconds(1));
        assertThat(taken(10)).containsExactly("a");
    }

    @Test
    void anUpdateThatIsPutOffWaitsAndHasOneAttemptMore() {
        queue.add(update("a")).blockingAwait();
        List<IndexQueue.Entry> entries = queue.take(10, Duration.ofMinutes(1)).blockingGet();

        queue.postpone(entries, Duration.ofSeconds(5)).blockingAwait();

        clock.move(Duration.ofSeconds(4));
        assertThat(taken(10)).isEmpty();
        clock.move(Duration.ofSeconds(1));
        List<IndexQueue.Entry> again = queue.take(10, Duration.ofMinutes(1)).blockingGet();
        assertThat(again).extracting(IndexQueue.Entry::attempts).containsExactly(1);
    }

    @Test
    void theLagIsTheNumberOfUpdatesAndWhenTheOldestWasAddedUntilTheyAreRemoved() {
        assertThat(queue.lag().blockingGet()).isEqualTo(new IndexQueue.Lag(0, null));
        Instant first = clock.instant();
        queue.add(update("a")).blockingAwait();
        clock.move(Duration.ofSeconds(3));
        queue.add(update("b")).blockingAwait();

        assertThat(queue.lag().blockingGet()).isEqualTo(new IndexQueue.Lag(2, first));

        queue.remove(queue.take(10, Duration.ofMinutes(1)).blockingGet()).blockingAwait();
        assertThat(queue.lag().blockingGet()).isEqualTo(new IndexQueue.Lag(0, null));
        assertThat(queue.durable()).isFalse();
    }
}
