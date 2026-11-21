package io.descoped.lds.core.persistence.memory;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class AsyncIterablePublisher<T> implements Publisher<T> {

    final AsyncIterator<T> asyncIterator;

    public AsyncIterablePublisher(AsyncIterable<T> asyncIterable) {
        this.asyncIterator = asyncIterable.iterator();
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        AsyncIterableSubscription subscription = new AsyncIterableSubscription(subscriber);
        subscriber.onSubscribe(subscription);
        if (asyncIterator == null) {
            subscription.signalError(new NullPointerException("asyncIterator"));
            return;
        }
        subscription.onSubscribeReturned();
    }

    class AsyncIterableSubscription implements Subscription {
        final AtomicLong requested = new AtomicLong();
        final AtomicLong published = new AtomicLong();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<Subscriber<? super T>> subscriber = new AtomicReference<>();
        /*
         * Held by the one publication that is under way. It starts held: an item is delivered on a thread of the
         * iterator, so what is requested inside onSubscribe is delivered only when onSubscribe has returned.
         */
        final AtomicBoolean publicationPending = new AtomicBoolean(true);

        AsyncIterableSubscription(Subscriber<? super T> subscriber) {
            this.subscriber.set(subscriber);
        }

        void onSubscribeReturned() {
            publicationPending.set(false);
            queuePublicationRequest(this::asyncIterate);
        }

        @Override
        public void request(long n) {
            try {
                if (n <= 0) {
                    throw new IllegalArgumentException("requested amount must be > 0");
                }
                if (cancelled.get()) {
                    return;
                }
                long r;
                long newRequestedValue;
                do {
                    r = requested.get();
                    if (Long.MAX_VALUE - r < n) {
                        // overflow
                        newRequestedValue = Long.MAX_VALUE;
                    } else {
                        newRequestedValue = r + n;
                    }
                } while (!requested.compareAndSet(r, newRequestedValue));
                queuePublicationRequest(this::asyncIterate);
            } catch (Throwable t) {
                signalError(t);
            }
        }

        /*
         * The last signal. The subscription is cancelled first, so nothing follows it: not from a publication
         * that is under way, and not from a later request.
         */
        private void signalError(Throwable failure) {
            cancelled.set(true);
            Subscriber<? super T> target = this.subscriber.getAndSet(null);
            if (target != null) {
                target.onError(failure);
            }
        }

        private void signalComplete() {
            cancelled.set(true);
            Subscriber<? super T> target = this.subscriber.getAndSet(null);
            if (target != null) {
                target.onComplete();
            }
        }

        private void asyncIterate() {
            asyncIterator.onHasNext().whenComplete((hasNext, failure) -> {
                if (failure != null) {
                    signalError(failure); // the iterator could not tell whether there is a next item
                    return;
                }
                Subscriber<? super T> target = this.subscriber.get();
                if (target == null) {
                    // asynchronously cancelled
                    return;
                }
                if (!hasNext.booleanValue()) {
                    signalComplete();
                    return;
                }
                try {
                    T item = asyncIterator.next();
                    target.onNext(item);
                    published.incrementAndGet();
                } catch (Throwable t) {
                    signalError(t);
                    return; // the publication stays held
                }
                publicationPending.set(false);
                queuePublicationRequest(this::asyncIterate);
            });
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            subscriber.set(null);
        }

        public void queuePublicationRequest(Runnable runnable) {
            if (cancelled.get()) {
                return;
            }
            // loop until stable. Instability is caused by concurrency
            for (; ; ) {
                if (!publicationPending.compareAndSet(false, true)) {
                    return; // a publication is already pending
                }
                long p = published.get();
                long r = requested.get();
                if (r > p) {
                    // available budget
                    runnable.run(); // runnable should eventually cause onNext publication
                    return;
                } else {
                    publicationPending.set(false);
                    // re-check whether requested count has increased while publicationPending flag was set
                    if (r == requested.get()) {
                        // requested count has not changed
                        return;
                    }
                }
            }
        }
    }
}