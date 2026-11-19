package io.descoped.lds.core.persistence.postgres;

import io.descoped.lds.api.persistence.PersistenceException;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.TransactionStatistics;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One connection of the pool with a transaction open on it. Committing or cancelling ends the transaction and
 * gives the connection back; whatever is asked after that does nothing.
 */
final class PostgresTransaction implements Transaction {

    final Connection connection;
    private final TransactionStatistics statistics = new TransactionStatistics();
    private final AtomicBoolean ended = new AtomicBoolean();

    PostgresTransaction(Connection connection) {
        this.connection = connection;
    }

    @Override
    public CompletableFuture<TransactionStatistics> commit() {
        return end(true);
    }

    @Override
    public CompletableFuture<TransactionStatistics> cancel() {
        return end(false);
    }

    private CompletableFuture<TransactionStatistics> end(boolean commit) {
        if (!ended.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(statistics);
        }
        try (Connection ending = connection) {
            if (commit) {
                ending.commit();
            } else {
                ending.rollback();
            }
            return CompletableFuture.completedFuture(statistics);
        } catch (SQLException e) {
            return CompletableFuture.failedFuture(new PersistenceException(e));
        }
    }
}
