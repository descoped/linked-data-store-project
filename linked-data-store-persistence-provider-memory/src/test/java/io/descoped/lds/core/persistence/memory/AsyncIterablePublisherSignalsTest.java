package io.descoped.lds.core.persistence.memory;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.descoped.lds.core.persistence.memory.OrderedKeyValueTransaction.ROW_LIMIT_UNLIMITED;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncIterablePublisherSignalsTest {

    /**
     * The entries are delivered on a thread of the store, so one that is asked for inside {@code onSubscribe} must
     * wait until {@code onSubscribe} has returned: signals to a subscriber come one after the other.
     */
    @Test
    void nothingIsDeliveredBeforeOnSubscribeHasReturned() throws InterruptedException {
        MemoryTransactionFactory factory = new MemoryTransactionFactory(false);
        MemoryTransaction tx = factory.createTransaction(false);
        try {
            for (int i = 0; i < 3; i++) {
                tx.set(new byte[]{(byte) i}, new byte[0], "i");
            }
            AsyncIterablePublisher<KeyValue> publisher = new AsyncIterablePublisher<>(tx.getRange(
                    KeySelector.firstGreaterOrEqual(new byte[]{0}), KeySelector.firstGreaterOrEqual(new byte[]{9}), "i", ROW_LIMIT_UNLIMITED));
            List<String> signals = new CopyOnWriteArrayList<>();
            CountDownLatch delivered = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);

            publisher.subscribe(new Subscriber<>() {
                @Override
                public void onSubscribe(Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                    try {
                        // time for another thread to deliver, if it were allowed to
                        assertThat(delivered.await(200, TimeUnit.MILLISECONDS)).isFalse();
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                    signals.add("onSubscribe returns");
                }

                @Override
                public void onNext(KeyValue entry) {
                    signals.add("onNext " + entry.key()[0]);
                    delivered.countDown();
                }

                @Override
                public void onError(Throwable failure) {
                    signals.add("onError " + failure);
                    done.countDown();
                }

                @Override
                public void onComplete() {
                    signals.add("onComplete");
                    done.countDown();
                }
            });

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(signals).containsExactly("onSubscribe returns", "onNext 0", "onNext 1", "onNext 2", "onComplete");
        } finally {
            tx.cancel();
            factory.close();
        }
    }

    @Test
    void nothingFollowsTheErrorOfAnIteratorThatFails() throws InterruptedException {
        AsyncIterable<Integer> failingOnTheSecond = () -> new Counting() {
            @Override
            public Integer next() {
                if (++count == 2) {
                    throw new IllegalStateException("the second item cannot be read");
                }
                return count;
            }
        };
        Recording subscriber = new Recording(Long.MAX_VALUE);

        new AsyncIterablePublisher<>(failingOnTheSecond).subscribe(subscriber);

        assertThat(subscriber.done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(subscriber.followed.await(200, TimeUnit.MILLISECONDS)).isFalse(); // time for anything that would follow
        assertThat(subscriber.signals).containsExactly("onNext 1", "onError the second item cannot be read");
    }

    @Test
    void nothingFollowsTheErrorOfARequestForNothing() throws InterruptedException {
        AsyncIterable<Integer> endless = Counting::new;
        Recording subscriber = new Recording(0);

        new AsyncIterablePublisher<>(endless).subscribe(subscriber);

        assertThat(subscriber.done.await(10, TimeUnit.SECONDS)).isTrue();
        subscriber.subscription.request(1);
        assertThat(subscriber.followed.await(200, TimeUnit.MILLISECONDS)).isFalse(); // time for anything that would follow
        assertThat(subscriber.signals).containsExactly("onError requested amount must be > 0");
    }

    @Test
    void anIteratorThatCannotTellWhetherThereIsMoreFailsTheStream() throws InterruptedException {
        AsyncIterable<Integer> broken = () -> new Counting() {
            @Override
            public CompletableFuture<Boolean> onHasNext() {
                return CompletableFuture.failedFuture(new IllegalStateException("the store is gone"));
            }
        };
        Recording subscriber = new Recording(1);

        new AsyncIterablePublisher<>(broken).subscribe(subscriber);

        assertThat(subscriber.done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(subscriber.signals).containsExactly("onError the store is gone");
    }

    @Test
    void eachSubscriberIsGivenEveryEntry() throws InterruptedException {
        AsyncIterable<Integer> fromOneToThree = () -> new Counting() {
            @Override
            public boolean hasNext() {
                return count < 3;
            }
        };
        AsyncIterablePublisher<Integer> publisher = new AsyncIterablePublisher<>(fromOneToThree);
        Recording first = new Recording(Long.MAX_VALUE);
        Recording second = new Recording(Long.MAX_VALUE);

        publisher.subscribe(first);
        assertThat(first.done.await(10, TimeUnit.SECONDS)).isTrue();
        publisher.subscribe(second);
        assertThat(second.done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(first.signals).containsExactly("onNext 1", "onNext 2", "onNext 3");
        assertThat(second.signals).containsExactly("onNext 1", "onNext 2", "onNext 3");
    }

    /* A store that is closed cannot read: the subscriber is told so, and subscribe returns. */
    @Test
    void aSubscriberOfAClosedStoreIsGivenTheError() throws InterruptedException {
        MemoryTransactionFactory factory = new MemoryTransactionFactory(false);
        MemoryTransaction tx = factory.createTransaction(false);
        tx.set(new byte[]{1}, new byte[0], "i");
        AsyncIterablePublisher<KeyValue> publisher = new AsyncIterablePublisher<>(tx.getRange(
                KeySelector.firstGreaterOrEqual(new byte[]{0}), KeySelector.firstGreaterOrEqual(new byte[]{9}), "i", ROW_LIMIT_UNLIMITED));
        tx.cancel();
        factory.close();
        List<String> signals = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        publisher.subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(KeyValue entry) {
                signals.add("onNext");
            }

            @Override
            public void onError(Throwable failure) {
                signals.add("onError " + failure.getClass().getSimpleName());
                done.countDown();
            }

            @Override
            public void onComplete() {
                signals.add("onComplete");
                done.countDown();
            }
        });

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(signals).containsExactly("onError RejectedExecutionException");
    }

    /* Counts from 1 without end, each answer given on another thread, as the store gives its own. */
    private static class Counting implements AsyncIterator<Integer> {
        int count;

        @Override
        public CompletableFuture<Boolean> onHasNext() {
            return CompletableFuture.supplyAsync(this::hasNext);
        }

        @Override
        public boolean hasNext() {
            return true;
        }

        @Override
        public Integer next() {
            return ++count;
        }
    }

    /*
     * Requests once, when it is subscribed, and keeps what it is signalled. It stops asking after three items.
     * followed is counted down by a signal that comes after done.
     */
    private static final class Recording implements Subscriber<Integer> {
        final List<String> signals = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        final CountDownLatch followed = new CountDownLatch(1);
        final long firstRequest;
        volatile Subscription subscription;

        Recording(long firstRequest) {
            this.firstRequest = firstRequest;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            this.subscription = subscription;
            subscription.request(firstRequest);
        }

        @Override
        public void onNext(Integer item) {
            note("onNext " + item);
            if (item == 3) {
                subscription.cancel();
                done.countDown();
            }
        }

        @Override
        public void onError(Throwable failure) {
            note("onError " + failure.getMessage());
            done.countDown();
        }

        @Override
        public void onComplete() {
            note("onComplete");
            done.countDown();
        }

        private void note(String signal) {
            if (done.getCount() == 0) {
                followed.countDown();
            }
            signals.add(signal);
        }
    }
}
