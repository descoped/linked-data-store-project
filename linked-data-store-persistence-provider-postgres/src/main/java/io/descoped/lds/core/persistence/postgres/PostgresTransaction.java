package io.descoped.lds.core.persistence.postgres;

import io.descoped.lds.api.persistence.PersistenceException;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.TransactionStatistics;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TransactionState;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One connection of the pool with a transaction open on it. Committing or cancelling ends the transaction and
 * gives the connection back; whatever is asked after that does nothing.
 * <p>
 * A transaction in which a statement failed cannot be committed: PostgreSQL rolls it back. The commit of a write
 * transaction then rolls it back and fails its future. A read transaction commits as before: it wrote nothing, and
 * the failed read has given its own error.
 */
final class PostgresTransaction implements Transaction {

    final Connection connection;
    private final boolean readOnly;
    private final TransactionStatistics statistics = new TransactionStatistics();
    private final AtomicBoolean ended = new AtomicBoolean();

    PostgresTransaction(Connection connection, boolean readOnly) {
        this.connection = connection;
        this.readOnly = readOnly;
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
            if (commit && !readOnly && failed(ending)) {
                // PostgreSQL answers COMMIT with a rollback, and the driver does not say so
                ending.rollback();
                return CompletableFuture.failedFuture(new PersistenceException(
                        "The transaction is rolled back, not committed: a statement in it failed"));
            }
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

    /* Whether a statement of the transaction failed, as the driver last heard from the server; no round trip. */
    private static boolean failed(Connection connection) throws SQLException {
        return connection.isWrapperFor(BaseConnection.class)
                && connection.unwrap(BaseConnection.class).getTransactionState() == TransactionState.FAILED;
    }
}
