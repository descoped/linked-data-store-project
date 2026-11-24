package io.descoped.lds.core.domain;

import io.undertow.server.HttpServerExchange;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lock of a document is waited for as a future, not by a thread.
 */
class DocumentLocksTest {

    private final DocumentLocks locks = new DocumentLocks(1, Duration.ofSeconds(30));

    private CompletableFuture<Boolean> write(HttpServerExchange exchange) {
        return locks.acquire(exchange, "ns", "Person", "ola");
    }

    /**
     * The writes that wait get the lock in the order they asked, each when the one before has let it go.
     */
    @Test
    void writesThatWaitGetTheLockInTheOrderTheyAsked() {
        HttpServerExchange first = new HttpServerExchange(null);
        HttpServerExchange second = new HttpServerExchange(null);
        HttpServerExchange third = new HttpServerExchange(null);

        assertThat(write(first)).isCompletedWithValue(true);
        CompletableFuture<Boolean> secondTaken = write(second);
        CompletableFuture<Boolean> thirdTaken = write(third);
        assertThat(secondTaken).isNotDone();
        assertThat(thirdTaken).isNotDone();
        assertThat(locks.waiting()).isEqualTo(2);

        DocumentLocks.letGoAfterSaga(first);
        assertThat(secondTaken).isCompletedWithValue(true);
        assertThat(thirdTaken).isNotDone();

        DocumentLocks.letGoAfterSaga(second);
        assertThat(thirdTaken).isCompletedWithValue(true);
        assertThat(locks.waiting()).isZero();
    }

    /**
     * A write keeps the lock until its saga has completed, also when it is answered before.
     */
    @Test
    void aWriteKeepsTheLockUntilItsSagaHasCompleted() {
        HttpServerExchange first = new HttpServerExchange(null);
        assertThat(write(first)).isCompletedWithValue(true);
        CompletableFuture<Void> saga = new CompletableFuture<>();
        DocumentLocks.holdUntil(first, saga);
        CompletableFuture<Boolean> next = write(new HttpServerExchange(null));

        DocumentLocks.letGoAfterSaga(first); // answered
        assertThat(next).isNotDone();

        saga.complete(null);
        assertThat(next).isCompletedWithValue(true);
    }

    /**
     * A write whose wait is over is answered that it did not get the lock, and the lock goes to the next one.
     */
    @Test
    void aWriteWhoseWaitIsOverDoesNotGetTheLock() {
        DocumentLocks quick = new DocumentLocks(1, Duration.ofMillis(50));
        HttpServerExchange first = new HttpServerExchange(null);
        assertThat(quick.acquire(first, "ns", "Person", "ola")).isCompletedWithValue(true);
        CompletableFuture<Boolean> late = quick.acquire(new HttpServerExchange(null), "ns", "Person", "ola");

        assertThat(late).succeedsWithin(Duration.ofSeconds(5)).isEqualTo(false);
        assertThat(quick.waiting()).isZero();

        DocumentLocks.letGoAfterSaga(first);
        assertThat(quick.acquire(new HttpServerExchange(null), "ns", "Person", "ola")).isCompletedWithValue(true);
    }

    /**
     * A request that is answered, or whose connection is gone, before it got the lock never takes it: the lock goes
     * to the write after it.
     */
    @Test
    void aRequestAnsweredBeforeItGotTheLockNeverTakesIt() {
        HttpServerExchange first = new HttpServerExchange(null);
        HttpServerExchange gone = new HttpServerExchange(null);
        assertThat(write(first)).isCompletedWithValue(true);
        CompletableFuture<Boolean> goneTaken = write(gone);
        CompletableFuture<Boolean> after = write(new HttpServerExchange(null));

        DocumentLocks.letGoAfterSaga(gone);
        assertThat(goneTaken).isCompletedWithValue(false);

        DocumentLocks.letGoAfterSaga(first);
        assertThat(after).isCompletedWithValue(true);
    }

    /**
     * The time of a turn is the time of the clock, to the microsecond, when the clock has moved on.
     */
    @Test
    void theTimeOfATurnIsNowWhenTheClockHasMovedOn() {
        DocumentLocks timed = new DocumentLocks(1, Duration.ofSeconds(30), clock(
                "2026-11-25T00:00:00.000001500Z", "2026-11-25T00:00:01Z"));

        assertThat(timed.timeOfTurn()).isEqualTo(ZonedDateTime.parse("2026-11-25T00:00:00.000001Z[Etc/UTC]"));
        assertThat(timed.timeOfTurn()).isEqualTo(ZonedDateTime.parse("2026-11-25T00:00:01Z[Etc/UTC]"));
    }

    /**
     * A clock that goes back (set back, or the clock of a virtual machine corrected) or stands still does not give a
     * turn a time before or at the turn before it: a write would then read the document as it was before the write
     * before it, and write a version in the past that the newest version does not have.
     */
    @Test
    void theTimeOfATurnIsAfterTheTurnBeforeItAlsoWhenTheClockGoesBack() {
        DocumentLocks timed = new DocumentLocks(1, Duration.ofSeconds(30), clock(
                "2026-11-25T00:00:01Z", "2026-11-25T00:00:00.995Z", "2026-11-25T00:00:00.995Z",
                "2026-11-25T00:00:01.000001Z", "2026-11-25T00:00:02Z"));

        assertThat(List.of(timed.timeOfTurn(), timed.timeOfTurn(), timed.timeOfTurn(), timed.timeOfTurn(),
                timed.timeOfTurn())).extracting(ZonedDateTime::toInstant).containsExactly(
                Instant.parse("2026-11-25T00:00:01Z"),
                Instant.parse("2026-11-25T00:00:01.000001Z"),
                Instant.parse("2026-11-25T00:00:01.000002Z"),
                Instant.parse("2026-11-25T00:00:01.000003Z"),
                Instant.parse("2026-11-25T00:00:02Z"));
    }

    /**
     * A clock that has gone back by more than a second keeps the writes after the last one until it has caught up,
     * and then gives its own time again (LDS says so in its log, once each way).
     */
    @Test
    void aClockFarBehindKeepsTheWritesAfterTheLastUntilItHasCaughtUp() {
        DocumentLocks timed = new DocumentLocks(1, Duration.ofSeconds(30), clock(
                "2026-11-25T00:00:10Z", "2026-11-25T00:00:05Z", "2026-11-25T00:00:06Z", "2026-11-25T00:00:11Z",
                "2026-11-25T00:00:12Z"));

        assertThat(List.of(timed.timeOfTurn(), timed.timeOfTurn(), timed.timeOfTurn(), timed.timeOfTurn(),
                timed.timeOfTurn())).extracting(ZonedDateTime::toInstant).containsExactly(
                Instant.parse("2026-11-25T00:00:10Z"),
                Instant.parse("2026-11-25T00:00:10.000001Z"),
                Instant.parse("2026-11-25T00:00:10.000002Z"),
                Instant.parse("2026-11-25T00:00:11Z"),
                Instant.parse("2026-11-25T00:00:12Z"));
    }

    /* A clock that gives the instants in turn. */
    private static Clock clock(String... instants) {
        Deque<Instant> next = new ArrayDeque<>();
        for (String instant : instants) {
            next.add(Instant.parse(instant));
        }
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Instant instant() {
                return next.removeFirst();
            }
        };
    }
}
