package io.descoped.lds.core.persistence;

import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.TransactionStatistics;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.reactivex.rxjava3.core.Completable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A write of a saga commits only when it completed, and a write that fails is cancelled.
 */
class WriteTransactionTest {

    private final List<String> ended = new ArrayList<>();

    private RxJsonPersistence persistence() {
        return persistence(null, null);
    }

    private RxJsonPersistence persistence(RuntimeException commitFailure, RuntimeException cancelFailure) {
        Transaction transaction = new Transaction() {
            @Override
            public CompletableFuture<TransactionStatistics> commit() {
                ended.add("commit");
                return commitFailure == null ? CompletableFuture.completedFuture(new TransactionStatistics())
                        : CompletableFuture.failedFuture(commitFailure);
            }

            @Override
            public CompletableFuture<TransactionStatistics> cancel() {
                ended.add("cancel");
                if (cancelFailure != null) {
                    throw cancelFailure;
                }
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

    @Test
    void aCancelThatFailsIsKeptWithTheFailureOfTheWrite() {
        IllegalStateException failure = new IllegalStateException("the store failed");
        IllegalStateException cancelFailure = new IllegalStateException("the connection is gone");

        assertThatThrownBy(() -> WriteTransaction.run(persistence(null, cancelFailure), _ -> Completable.error(failure)))
                .isSameAs(failure)
                .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(cancelFailure));
        assertThat(ended).containsExactly("cancel");
    }

    @Test
    void aWriteThatFailsWithACheckedExceptionIsCancelled() {
        IOException failure = new IOException("the disk is full");

        assertThatThrownBy(() -> WriteTransaction.run(persistence(), _ -> Completable.error(failure)))
                .isInstanceOf(RuntimeException.class).hasCause(failure);
        assertThat(ended).containsExactly("cancel");
    }

    @Test
    void aCommitThatFailsIsThrownAndNotFollowedByACancel() {
        IllegalStateException commitFailure = new IllegalStateException("the commit failed");

        // the store has ended the transaction when its commit fails; a cancel after it would undo nothing in
        // PostgreSQL and Neo4j, and would replay the undo log of the memory provider over a commit it may have made
        assertThatThrownBy(() -> WriteTransaction.run(persistence(commitFailure, null), _ -> Completable.complete()))
                .isInstanceOf(CompletionException.class).hasCause(commitFailure);
        assertThat(ended).containsExactly("commit");
    }
}
