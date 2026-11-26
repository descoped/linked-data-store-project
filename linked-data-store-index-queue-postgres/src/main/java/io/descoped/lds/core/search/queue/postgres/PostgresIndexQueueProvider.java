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
import java.util.concurrent.TimeUnit;

/**
 * The index queue {@code postgres}, in the database of the transaction log ({@code txlog.config.postgres.driver.*}).
 * The queue is made before the transaction log, so it waits for that database as the transaction log does, for
 * {@code txlog.rawdata.provider.initialization.max-wait-seconds}.
 */
public final class PostgresIndexQueueProvider implements IndexQueueProvider {

    private static final int POOL_SIZE = 4;

    static final String HOST = "txlog.config.postgres.driver.host";
    static final String PORT = "txlog.config.postgres.driver.port";
    static final String USER = "txlog.config.postgres.driver.user";
    static final String PASSWORD = "txlog.config.postgres.driver.password";
    static final String DATABASE = "txlog.config.postgres.driver.database";
    static final String MAX_WAIT_SECONDS = "txlog.rawdata.provider.initialization.max-wait-seconds";

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
     * {@return the keys of the database of the transaction log, and how long the transaction log waits for it}
     */
    @Override
    public Set<String> configurationKeys() {
        return Set.of(HOST, PORT, USER, PASSWORD, DATABASE, MAX_WAIT_SECONDS);
    }

    /**
     * Connects to the database of the transaction log and creates the table of the queue when it is missing. A
     * database that cannot be reached is tried again, about once a second, for as long as the transaction log waits
     * for it ({@code txlog.rawdata.provider.initialization.max-wait-seconds}, none when it is not given).
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
        // HikariCP takes the first connection before it gives the pool, and tries again for this long; 1 ms is one
        // attempt (0 would give a pool that has never reached the database)
        config.setInitializationFailTimeout(Math.max(1, TimeUnit.SECONDS.toMillis(maxWaitSeconds(configuration))));
        return new HikariDataSource(config);
    }

    private static long maxWaitSeconds(Map<String, String> configuration) {
        String value = configuration.get(MAX_WAIT_SECONDS);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(MAX_WAIT_SECONDS + " must be a number of seconds: " + value, e);
        }
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
