package io.descoped.lds.core.persistence.postgres;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Walks a JDBC result set under demand: one {@link ResultSet#next()} for each row that was requested, never a row
 * ahead of the demand, and no thread of its own. The row is delivered on the thread that requested it.
 * <p>
 * The item is the result set itself, positioned on the new row, and the same instance for every row. Whoever
 * subscribes must read the row inside {@code onNext}, before the cursor moves again.
 * <p>
 * The publisher does not close the result set; the code that opened it does.
 */
final class ResultSetPublisher implements Publisher<ResultSet> {

    private final ResultSet resultSet;

    ResultSetPublisher(ResultSet resultSet) {
        this.resultSet = resultSet;
    }

    @Override
    public void subscribe(Subscriber<? super ResultSet> subscriber) {
        subscriber.onSubscribe(new ResultSetSubscription(subscriber));
        if (resultSet == null) {
            subscriber.onError(new NullPointerException("resultSet"));
        }
    }

    private final class ResultSetSubscription implements Subscription {
        /* The running total of rows asked for, and of rows delivered. */
        private final AtomicLong requested = new AtomicLong();
        private final AtomicLong published = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Subscriber<? super ResultSet>> subscriber = new AtomicReference<>();
        /* Held by the one call that delivers rows. It is kept after the last signal, so nothing follows it. */
        private final AtomicBoolean publicationPending = new AtomicBoolean();

        private ResultSetSubscription(Subscriber<? super ResultSet> subscriber) {
            this.subscriber.set(subscriber);
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
                publish();
            } catch (Throwable t) {
                // the error is the last signal: the subscription is cancelled first, so a later request delivers nothing
                cancelled.set(true);
                Subscriber<? super ResultSet> target = this.subscriber.getAndSet(null);
                if (target == null) {
                    // asynchronously cancelled
                    return;
                }
                target.onError(t);
            }
        }

        /**
         * Delivers rows while there is demand. A loop and not a call per row: a result of any size is walked at
         * the same stack depth.
         */
        private void publish() {
            // loop until stable. Instability is caused by concurrency
            for (; ; ) {
                if (!publicationPending.compareAndSet(false, true)) {
                    return; // a publication is already pending; it sees the new demand when it checks again
                }
                while (requested.get() > published.get()) {
                    // available budget
                    if (!publishNext()) {
                        return; // the last signal was sent, or the subscription was cancelled
                    }
                }
                publicationPending.set(false);
                // re-check whether requested count has increased while publicationPending flag was set
                if (requested.get() <= published.get()) {
                    return;
                }
            }
        }

        /**
         * Moves the cursor one row and delivers it.
         *
         * @return false when nothing more is to be delivered
         */
        private boolean publishNext() {
            Subscriber<? super ResultSet> target = this.subscriber.get();
            if (target == null) {
                // asynchronously cancelled
                return false;
            }
            boolean next;
            try {
                next = resultSet.next();
            } catch (Throwable t) {
                if (!cancelled.get()) {
                    target.onError(t);
                }
                return false;
            }
            if (!next) {
                target.onComplete();
                return false;
            }
            try {
                target.onNext(resultSet);
            } catch (Throwable t) {
                cancel();
                target.onError(t);
                return false;
            }
            published.incrementAndGet();
            return true;
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            subscriber.set(null);
        }
    }
}
