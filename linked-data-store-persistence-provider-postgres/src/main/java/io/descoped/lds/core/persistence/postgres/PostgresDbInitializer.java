package io.descoped.lds.core.persistence.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.descoped.lds.api.persistence.PersistenceException;
import io.descoped.lds.api.persistence.PersistenceInitializer;
import io.descoped.lds.api.persistence.ProviderName;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistenceBridge;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * The persistence provider {@code postgres}: documents as fragments in a PostgreSQL database, read as a stream.
 * <p>
 * Starting it opens a pool of {@value #POOL_SIZE} connections and creates the table and its index when they are
 * not there. The database and its user must exist.
 */
@ProviderName("postgres")
public class PostgresDbInitializer implements PersistenceInitializer {

    private static final int POOL_SIZE = 10;

    private PostgresPersistence postgresPersistence;

    /**
     * Called by the service loader.
     */
    public PostgresDbInitializer() {
        // the provider is set up by initialize
    }

    /**
     * {@return the name that selects this provider, {@code postgres}}
     */
    @Override
    public String persistenceProviderId() {
        return "postgres";
    }

    /**
     * {@return the configuration keys this provider reads: host, port, user, password and database, each prefixed
     * with {@code postgres.driver.}}
     */
    @Override
    public Set<String> configurationKeys() {
        return Set.of(
                "postgres.driver.host",
                "postgres.driver.port",
                "postgres.driver.user",
                "postgres.driver.password",
                "postgres.driver.database"
        );
    }

    PostgresPersistence getPostgresPersistence() {
        return postgresPersistence;
    }

    /**
     * Connects to the database, creates the table when it is missing, and returns the provider.
     *
     * @param defaultNamespace not used: the namespace is given with every call
     * @param configuration    the values of {@link #configurationKeys()}
     * @param managedDomains   not used: one table holds every entity
     * @return the provider, to be closed when it is no longer used
     */
    @Override
    public RxJsonPersistence initialize(String defaultNamespace, Map<String, String> configuration, Set<String> managedDomains) {
        int fragmentCapacityBytes = Integer.MAX_VALUE; // Postgres persistence-provider does not support fragmentation of document leaf-nodes.
        JavaUtilLoggerBridge.installJavaUtilLoggerBridgeHandler(Level.INFO);
        HikariDataSource dataSource = openDataSource(configuration);
        try {
            createTableIfMissing(dataSource);
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
        postgresPersistence = new PostgresPersistence(new PostgresTransactionFactory(dataSource));
        return new RxJsonPersistenceBridge(postgresPersistence, fragmentCapacityBytes);
    }

    private static HikariDataSource openDataSource(Map<String, String> configuration) {
        PGSimpleDataSource database = new PGSimpleDataSource();
        database.setServerNames(new String[]{configuration.get("postgres.driver.host")});
        database.setPortNumbers(new int[]{Integer.parseInt(configuration.get("postgres.driver.port"))});
        database.setUser(configuration.get("postgres.driver.user"));
        database.setPassword(configuration.get("postgres.driver.password"));
        database.setDatabaseName(configuration.get("postgres.driver.database"));

        HikariConfig config = new HikariConfig();
        config.setDataSource(database);
        config.setAutoCommit(false);
        config.setMaximumPoolSize(POOL_SIZE);
        return new HikariDataSource(config);
    }

    /**
     * Runs the statements of {@code init-db.sql}, the file next to this class, in one transaction.
     */
    private static void createTableIfMissing(HikariDataSource dataSource) {
        String sql;
        try (InputStream in = PostgresDbInitializer.class.getResourceAsStream("init-db.sql")) {
            if (in == null) {
                throw new IllegalStateException("init-db.sql is missing from the PostgreSQL provider");
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            connection.commit();
        } catch (SQLException e) {
            throw new PersistenceException(e);
        }
    }
}
