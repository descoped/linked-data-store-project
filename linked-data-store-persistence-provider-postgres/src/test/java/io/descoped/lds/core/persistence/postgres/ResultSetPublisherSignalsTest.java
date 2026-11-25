package io.descoped.lds.core.persistence.postgres;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultSetPublisherSignalsTest {

    @Test
    void nothingFollowsTheErrorOfARequestForNothing() {
        Recorder recorder = new Recorder(0);

        new ResultSetPublisher(rowsWithoutEnd()).subscribe(recorder);
        recorder.subscription.request(1);

        assertThat(recorder.signals).containsExactly("onError requested amount must be > 0");
    }

    @Test
    void withoutAResultSetTheErrorIsSignalledOnce() {
        Recorder recorder = new Recorder(-1);

        new ResultSetPublisher(null).subscribe(recorder);

        assertThat(recorder.signals).containsExactly("onError resultSet");
    }

    @Test
    void withoutAResultSetTheErrorIsSignalledOnceAlsoWhenRowsAreAskedForAtOnce() {
        Recorder recorder = new Recorder(1);

        new ResultSetPublisher(null).subscribe(recorder);

        assertThat(recorder.signals).hasSize(1).allMatch(signal -> signal.startsWith("onError"));
    }

    @Test
    void nothingFollowsTheCompletion() {
        Recorder recorder = new Recorder(1);

        new ResultSetPublisher(noRows()).subscribe(recorder);
        recorder.subscription.request(0);

        assertThat(recorder.signals).containsExactly("onComplete");
    }

    /* Records the signals it gets. In onSubscribe it asks for the rows it is given, or for none when that is -1. */
    private static final class Recorder implements Subscriber<ResultSet> {
        private final List<String> signals = new ArrayList<>();
        private final long rowsAtOnce;
        private Subscription subscription;

        private Recorder(long rowsAtOnce) {
            this.rowsAtOnce = rowsAtOnce;
        }

        @Override
        public void onSubscribe(Subscription s) {
            subscription = s;
            if (rowsAtOnce >= 0) {
                s.request(rowsAtOnce);
            }
        }

        @Override
        public void onNext(ResultSet row) {
            signals.add("onNext");
        }

        @Override
        public void onError(Throwable failure) {
            signals.add("onError " + failure.getMessage());
        }

        @Override
        public void onComplete() {
            signals.add("onComplete");
        }
    }

    /* A result set that always has a next row; the publisher calls nothing else. */
    private static ResultSet rowsWithoutEnd() {
        return resultSet(true);
    }

    /* A result set without rows. */
    private static ResultSet noRows() {
        return resultSet(false);
    }

    private static ResultSet resultSet(boolean hasNext) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (_, method, _) -> {
                    if ("next".equals(method.getName())) {
                        return hasNext;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
