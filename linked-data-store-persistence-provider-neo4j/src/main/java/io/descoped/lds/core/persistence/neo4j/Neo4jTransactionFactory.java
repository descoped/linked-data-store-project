package io.descoped.lds.core.persistence.neo4j;

import io.descoped.lds.api.persistence.PersistenceException;
import io.descoped.lds.api.persistence.TransactionFactory;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

/**
 * Opens the transactions of the provider, one session of the driver for each.
 */
final class Neo4jTransactionFactory implements TransactionFactory {

    private final Driver driver;
    private final boolean logCypher;

    Neo4jTransactionFactory(Driver driver, boolean logCypher) {
        this.driver = driver;
        this.logCypher = logCypher;
    }

    @Override
    public Neo4jTransaction createTransaction(boolean readOnly) throws PersistenceException {
        Session session = driver.session(SessionConfig.builder()
                .withDefaultAccessMode(readOnly ? AccessMode.READ : AccessMode.WRITE)
                .build());
        Neo4jTransaction transaction = null;
        try {
            transaction = new Neo4jTransaction(session, logCypher);
            return transaction;
        } finally {
            if (transaction == null) {
                // the transaction did not begin
                session.close();
            }
        }
    }

    @Override
    public void close() {
        driver.close();
    }
}
