package io.descoped.lds.core.persistence.memory;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.List;
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
            CountDownLatch done = new CountDownLatch(1);

            publisher.subscribe(new Subscriber<>() {
                @Override
                public void onSubscribe(Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                    try {
                        Thread.sleep(200); // time for another thread to deliver, if it were allowed to
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    signals.add("onSubscribe returns");
                }

                @Override
                public void onNext(KeyValue entry) {
                    signals.add("onNext " + entry.key()[0]);
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
}
