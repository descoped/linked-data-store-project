package io.descoped.lds.core.persistence.postgres;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ResultSetPublisherSignalsTest {

    @Test
    void nothingFollowsTheErrorOfARequestForNothing() {
        List<String> signals = new ArrayList<>();
        AtomicReference<Subscription> subscription = new AtomicReference<>();

        new ResultSetPublisher(rowsWithoutEnd()).subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription s) {
                subscription.set(s);
                s.request(0);
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
        });
        subscription.get().request(1);

        assertThat(signals).containsExactly("onError requested amount must be > 0");
    }

    /* A result set that always has a next row; the publisher calls nothing else. */
    private static ResultSet rowsWithoutEnd() {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, arguments) -> {
                    if ("next".equals(method.getName())) {
                        return true;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
