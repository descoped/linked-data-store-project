package io.descoped.lds.api.persistence.reactivex;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A read that is cancelled while a group waits for its next item ends that group quietly: nothing is reported to
 * RxJava's handler of errors that no one receives. A group whose upstream fails ends with that failure, once.
 */
class RxGroupByToolsTest {

    private final List<Throwable> undelivered = new CopyOnWriteArrayList<>();

    @BeforeEach
    void listen() {
        RxJavaPlugins.setErrorHandler(undelivered::add);
    }

    @AfterEach
    void stopListening() {
        RxJavaPlugins.setErrorHandler(null);
    }

    /* Waits for the thread to end, at most five seconds. */
    private static void awaitEnd(Thread thread) throws InterruptedException {
        thread.join(Duration.ofSeconds(5));
        assertThat(thread.isAlive()).as("the thread of the group has ended").isFalse();
    }

    @Test
    void aReadCancelledWhileAGroupWaitsForItsNextItemReportsNothing() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            AtomicReference<Thread> groupThread = new AtomicReference<>();
            TestSubscriber<Integer> read = RxGroupByTools.groupByOrdered(
                            Flowable.just(1, 2).concatWith(Flowable.never()), _ -> "one group")
                    .concatMap(group -> group.flowable().doOnNext(_ -> groupThread.set(Thread.currentThread())))
                    .test();
            read.awaitCount(2);
            read.assertValues(1, 2);

            read.cancel();

            awaitEnd(groupThread.get());
            assertThat(undelivered).as("errors reported to RxJava's handler").isEmpty();
        }
    }

    @Test
    void aGroupWhoseUpstreamFailsEndsWithThatFailure() throws InterruptedException {
        IllegalStateException failure = new IllegalStateException("the store failed");
        Set<Thread> before = groupThreads();
        TestSubscriber<Integer> read = RxGroupByTools.groupByOrdered(
                        Flowable.just(1, 2).concatWith(Flowable.error(failure)), _ -> "one group")
                .concatMap(RxGroupByTools.MyGroupedFlowable::flowable)
                .test();

        assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
        // the failure reaches the read at once, and the group that was being read is cancelled
        read.assertError(failure);
        for (Thread started : groupThreads()) {
            if (!before.contains(started)) {
                awaitEnd(started); // then it has said all it says
            }
        }
        assertThat(undelivered).isEmpty();
    }

    /* The threads groups are read on. */
    private static Set<Thread> groupThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("RxNewThreadScheduler")).collect(Collectors.toSet());
    }
}
