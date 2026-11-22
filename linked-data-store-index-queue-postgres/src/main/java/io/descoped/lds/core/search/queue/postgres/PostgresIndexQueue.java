package io.descoped.lds.core.search.queue.postgres;

import com.zaxxer.hikari.HikariDataSource;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.IndexUpdate;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The index queue in the table {@code lds_index_queue}. Each call is one statement in a transaction of its own, run on
 * a virtual thread of its own, as JDBC blocks; the pool of connections bounds how many reach the database at once. Taking marks the rows it takes with {@code FOR UPDATE SKIP LOCKED}, so
 * that several LDS on one database take different rows; the times are the database's.
 */
final class PostgresIndexQueue implements IndexQueue {

    private static final DateTimeFormatter VERSION = DateTimeFormatter.ISO_ZONED_DATE_TIME;

    private static final String ADD = "INSERT INTO lds_index_queue (namespace, entity, id, version, operation) "
            + "VALUES (?, ?, ?, ?, ?)";
    private static final String TAKE = "UPDATE lds_index_queue SET available_at = now() + make_interval(secs => ?) "
            + "WHERE sequence IN (SELECT sequence FROM lds_index_queue WHERE available_at <= now() ORDER BY sequence "
            + "LIMIT ? FOR UPDATE SKIP LOCKED) "
            + "RETURNING sequence, namespace, entity, id, version, operation, attempts";
    private static final String REMOVE = "DELETE FROM lds_index_queue WHERE sequence = ANY (?)";
    private static final String POSTPONE = "UPDATE lds_index_queue SET available_at = now() + make_interval(secs => ?), "
            + "attempts = attempts + 1 WHERE sequence = ANY (?)";
    /* The most updates the lag counts: a count of a queue a rebuild has filled would otherwise read every row. */
    static final int LAG_COUNT_LIMIT = 100_000;

    /*
     * The lag reads at most LAG_COUNT_LIMIT rows and the first row of the primary key: the oldest update is the one
     * with the lowest sequence, as both rise as rows are added.
     */
    private static final String LAG = "SELECT (SELECT count(*) FROM (SELECT 1 FROM lds_index_queue LIMIT "
            + LAG_COUNT_LIMIT + ") counted), (SELECT added FROM lds_index_queue ORDER BY sequence LIMIT 1)";

    private final HikariDataSource dataSource;
    private final ExecutorService statements = Executors.newVirtualThreadPerTaskExecutor();
    private final Scheduler scheduler = Schedulers.from(statements);

    PostgresIndexQueue(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    /*
     * Runs the work in a transaction of its own, on a virtual thread, and commits it.
     */
    private <T> Single<T> inTransaction(Work<T> work) {
        return Single.fromCallable(() -> {
            try (Connection connection = dataSource.getConnection()) {
                try {
                    T result = work.run(connection);
                    connection.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    connection.rollback();
                    throw e;
                }
            }
        }).subscribeOn(scheduler);
    }

    @Override
    public Completable add(IndexUpdate update) {
        return inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(ADD)) {
                statement.setString(1, update.namespace());
                statement.setString(2, update.entity());
                statement.setString(3, update.id());
                statement.setString(4, VERSION.format(update.version()));
                statement.setString(5, update.operation().name());
                return statement.executeUpdate();
            }
        }).ignoreElement();
    }

    @Override
    public Single<List<Entry>> take(int max, Duration lease) {
        return inTransaction(connection -> {
            List<Entry> taken = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(TAKE)) {
                statement.setDouble(1, seconds(lease));
                statement.setInt(2, max);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        taken.add(new Entry(rows.getLong(1), new IndexUpdate(rows.getString(2), rows.getString(3),
                                rows.getString(4), ZonedDateTime.parse(rows.getString(5), VERSION),
                                IndexUpdate.Operation.valueOf(rows.getString(6))), rows.getInt(7)));
                    }
                }
            }
            taken.sort(Comparator.comparingLong(Entry::sequence));
            return taken;
        });
    }

    @Override
    public Completable remove(Collection<Entry> entries) {
        if (entries.isEmpty()) {
            return Completable.complete();
        }
        return inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(REMOVE)) {
                statement.setArray(1, sequences(connection, entries));
                return statement.executeUpdate();
            }
        }).ignoreElement();
    }

    @Override
    public Completable postpone(Collection<Entry> entries, Duration delay) {
        if (entries.isEmpty()) {
            return Completable.complete();
        }
        return inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(POSTPONE)) {
                statement.setDouble(1, seconds(delay));
                statement.setArray(2, sequences(connection, entries));
                return statement.executeUpdate();
            }
        }).ignoreElement();
    }

    @Override
    public Single<Lag> lag() {
        return inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(LAG);
                 ResultSet row = statement.executeQuery()) {
                row.next();
                Timestamp oldest = row.getTimestamp(2);
                return new Lag(row.getLong(1), oldest == null ? null : oldest.toInstant());
            }
        });
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public void close() {
        statements.close(); // waits for the statements that run
        dataSource.close();
    }

    private static Array sequences(Connection connection, Collection<Entry> entries) throws SQLException {
        return connection.createArrayOf("bigint", entries.stream().map(Entry::sequence).toArray());
    }

    private static double seconds(Duration duration) {
        return duration.toNanos() / 1e9;
    }
}
