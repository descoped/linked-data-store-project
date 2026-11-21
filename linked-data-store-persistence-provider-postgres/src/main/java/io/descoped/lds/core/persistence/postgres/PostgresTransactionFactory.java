package io.descoped.lds.core.persistence.postgres;

import com.zaxxer.hikari.HikariDataSource;
import io.descoped.lds.api.persistence.PersistenceException;
import io.descoped.lds.api.persistence.TransactionFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens a transaction on a connection of the pool, and owns the pool.
 */
final class PostgresTransactionFactory implements TransactionFactory {

    final HikariDataSource dataSource;

    PostgresTransactionFactory(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public PostgresTransaction createTransaction(boolean readOnly) throws PersistenceException {
        try {
            return transactionOn(dataSource.getConnection());
        } catch (SQLException e) {
            throw new PersistenceException(e);
        }
    }

    /* The connection is closed when the transaction cannot be opened on it. */
    private static PostgresTransaction transactionOn(Connection connection) throws SQLException {
        try {
            connection.setAutoCommit(false);
            return new PostgresTransaction(connection);
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
