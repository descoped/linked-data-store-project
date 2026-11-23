package io.descoped.lds.core.search.queue.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.IndexQueueProvider;
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

/**
 * The index queue {@code postgres}, in the database of the transaction log ({@code txlog.config.postgres.driver.*}).
 */
public final class PostgresIndexQueueProvider implements IndexQueueProvider {

    private static final int POOL_SIZE = 4;

    static final String HOST = "txlog.config.postgres.driver.host";
    static final String PORT = "txlog.config.postgres.driver.port";
    static final String USER = "txlog.config.postgres.driver.user";
    static final String PASSWORD = "txlog.config.postgres.driver.password";
    static final String DATABASE = "txlog.config.postgres.driver.database";

    /**
     * Called by the service loader.
     */
    public PostgresIndexQueueProvider() {
        // the queue is made by indexQueue
    }

    /**
     * {@return {@code postgres}, the name of the transaction log provider whose database it shares}
     */
    @Override
    public String providerId() {
        return "postgres";
    }

    /**
     * {@return the keys of the database of the transaction log}
     */
    @Override
    public Set<String> configurationKeys() {
        return Set.of(HOST, PORT, USER, PASSWORD, DATABASE);
    }

    /**
     * Connects to the database of the transaction log and creates the table of the queue when it is missing.
     *
     * @param configuration the configuration of LDS
     * @return the queue, to be closed when it is no longer used
     */
    @Override
    public IndexQueue indexQueue(Map<String, String> configuration) {
        HikariDataSource dataSource = openDataSource(configuration);
        try {
            createTableIfMissing(dataSource);
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
        return new PostgresIndexQueue(dataSource);
    }

    private static HikariDataSource openDataSource(Map<String, String> configuration) {
        PGSimpleDataSource database = new PGSimpleDataSource();
        database.setServerNames(new String[]{configuration.get(HOST)});
        database.setPortNumbers(new int[]{Integer.parseInt(configuration.get(PORT))});
        database.setUser(configuration.get(USER));
        database.setPassword(configuration.get(PASSWORD));
        database.setDatabaseName(configuration.get(DATABASE));

        HikariConfig config = new HikariConfig();
        config.setPoolName("lds-index-queue");
        config.setDataSource(database);
        config.setAutoCommit(false);
        config.setMaximumPoolSize(POOL_SIZE);
        return new HikariDataSource(config);
    }

    /*
     * Runs init-queue.sql, the file next to this class, in one transaction.
     */
    private static void createTableIfMissing(HikariDataSource dataSource) {
        String sql;
        try (InputStream in = PostgresIndexQueueProvider.class.getResourceAsStream("init-queue.sql")) {
            if (in == null) {
                throw new IllegalStateException("init-queue.sql is missing from the PostgreSQL index queue");
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
            throw new IllegalStateException("The table of the index queue could not be created", e);
        }
    }
}
