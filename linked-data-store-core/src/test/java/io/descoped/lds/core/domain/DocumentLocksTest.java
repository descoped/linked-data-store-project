package io.descoped.lds.core.domain;

import io.undertow.server.HttpServerExchange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
}
