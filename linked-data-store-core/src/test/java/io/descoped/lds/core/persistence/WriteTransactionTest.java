package io.descoped.lds.core.persistence;

import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.TransactionStatistics;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.reactivex.rxjava3.core.Completable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A write of a saga commits only when it completed, and a write that fails is cancelled.
 */
class WriteTransactionTest {

    private final List<String> ended = new ArrayList<>();

    private RxJsonPersistence persistence() {
        Transaction transaction = new Transaction() {
            @Override
            public CompletableFuture<TransactionStatistics> commit() {
                ended.add("commit");
                return CompletableFuture.completedFuture(new TransactionStatistics());
            }

            @Override
            public CompletableFuture<TransactionStatistics> cancel() {
                ended.add("cancel");
                return CompletableFuture.completedFuture(new TransactionStatistics());
            }
        };
        return (RxJsonPersistence) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RxJsonPersistence.class}, (_, method, _) -> {
                    if (method.getName().equals("createTransaction")) {
                        return transaction;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void aWriteThatCompletesIsCommitted() {
        WriteTransaction.run(persistence(), _ -> Completable.complete());

        assertThat(ended).containsExactly("commit");
    }

    @Test
    void aWriteThatFailsIsCancelledAndNotCommitted() {
        IllegalStateException failure = new IllegalStateException("the store failed");

        assertThatThrownBy(() -> WriteTransaction.run(persistence(), _ -> Completable.error(failure))).isSameAs(failure);
        assertThat(ended).containsExactly("cancel");
    }

    @Test
    void aWriteThatThrowsBeforeItStartsIsCancelled() {
        assertThatThrownBy(() -> WriteTransaction.run(persistence(), _ -> {
            throw new IllegalArgumentException("no write");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(ended).containsExactly("cancel");
    }
}
