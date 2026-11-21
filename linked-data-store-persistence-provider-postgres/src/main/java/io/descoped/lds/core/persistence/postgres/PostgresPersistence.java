package io.descoped.lds.core.persistence.postgres;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.PersistenceDeletePolicy;
import io.descoped.lds.api.persistence.PersistenceException;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.TransactionFactory;
import io.descoped.lds.api.persistence.reactivex.Range;
import io.descoped.lds.api.persistence.reactivex.RxPersistence;
import io.descoped.lds.api.persistence.streaming.Fragment;
import io.descoped.lds.api.persistence.streaming.FragmentType;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fragments in one PostgreSQL table, {@code fragment}: one row is one leaf value of one version of one document.
 * <p>
 * One database holds any number of namespaces. The namespace is the first part of a row's key, so a document is
 * seen in the namespace it was written in and in no other.
 * <p>
 * Every read is one statement whose rows are fetched from the server as they are asked for
 * ({@link ResultSetPublisher}, fetch size {@value #FETCH_SIZE}), ordered by document, then version, then path. The
 * statement is run when the stream is subscribed to and closed when the stream ends or is cancelled.
 * <p>
 * A version is an instant with millisecond precision. A value is one fragment: this provider does not split a
 * value.
 * <p>
 * Ids are ordered by their UTF-8 bytes (collation {@code POSIX}), which is the order of code points. Java orders
 * strings by UTF-16 code units, and the bridge sorts a list that way. The two differ only between a character
 * from U+E000 to U+FFFF and one above U+FFFF (an emoji), so for ids with both, {@code hasNext} and
 * {@code hasPrevious} can disagree with the list until the bridge stops sorting.
 */
final class PostgresPersistence implements RxPersistence {

    private static final ZoneId UTC = ZoneId.of("Etc/UTC");
    private static final int FETCH_SIZE = 10;
    private static final int WRITE_BATCH_SIZE = 100;

    private static final String COLUMNS = "f.id, f.version, f.path, f.indices, f.type, f.value";
    private static final String SELECT_COLUMNS = "SELECT " + COLUMNS;
    private static final String AND_C_ENTITY = " AND c.entity = ";
    private static final String AND_C_VERSION_AT_MOST = " AND c.version <= ";
    private static final String DELETE_WHERE_NAMESPACE = "DELETE FROM fragment WHERE namespace = ";
    private static final String AND_ENTITY = " AND entity = ";

    final PostgresTransactionFactory transactionFactory;

    PostgresPersistence(PostgresTransactionFactory transactionFactory) {
        this.transactionFactory = transactionFactory;
    }

    @Override
    public TransactionFactory transactionFactory() throws PersistenceException {
        return transactionFactory;
    }

    @Override
    public Transaction createTransaction(boolean readOnly) throws PersistenceException {
        return transactionFactory.createTransaction(readOnly);
    }

    @Override
    public Completable createOrOverwrite(Transaction transaction, Flowable<Fragment> fragments) {
        Connection connection = connectionOf(transaction);
        return Completable.using(
                () -> new WriteBatch(connection),
                batch -> fragments.doOnNext(batch::add).ignoreElements().doOnComplete(batch::complete),
                WriteBatch::close);
    }

    /**
     * The statements of one write. The rows of a document version that is written again are deleted before the
     * new ones are inserted, and both are sent in batches.
     */
    private static final class WriteBatch implements AutoCloseable {
        private final Connection connection;
        private final PreparedStatement insert;
        private final PreparedStatement delete;
        private final Set<DocumentKey> deletedDocuments = new LinkedHashSet<>();
        private int insertCount;
        private int deleteCount;

        private WriteBatch(Connection connection) throws SQLException {
            this.connection = connection;
            this.delete = connection.prepareStatement(
                    "DELETE FROM fragment WHERE namespace = ? AND entity = ? AND id = ? AND version = ?");
            try {
                this.insert = connection.prepareStatement(
                        "INSERT INTO fragment(namespace, entity, id, version, path, indices, type, value) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            } catch (SQLException | RuntimeException e) {
                delete.close();
                throw e;
            }
        }

        private void add(Fragment item) {
            if (item.offset() != 0) {
                throw new IllegalStateException("Postgres fragments must have offset == 0, illegal offset: " + item.offset());
            }
            try {
                OffsetDateTime version = instantOf(item.timestamp());
                if (deletedDocuments.add(DocumentKey.from(item))) {
                    delete.setString(1, item.namespace());
                    delete.setString(2, item.entity());
                    delete.setString(3, item.id());
                    delete.setObject(4, version);
                    delete.addBatch();
                    deleteCount++;
                }
                List<Integer> indices = new ArrayList<>();
                String indexUnawarePath = Fragment.computeIndexUnawarePath(item.path(), indices);
                insert.setString(1, item.namespace());
                insert.setString(2, item.entity());
                insert.setString(3, item.id());
                insert.setObject(4, version);
                insert.setString(5, indexUnawarePath);
                insert.setArray(6, connection.createArrayOf("integer", indices.toArray(new Integer[0])));
                insert.setShort(7, (short) item.fragmentType().ordinal());
                insert.setBytes(8, item.value());
                insert.addBatch();
                if (++insertCount % WRITE_BATCH_SIZE == 0) {
                    execute();
                }
            } catch (SQLException e) {
                throw new PersistenceException(e);
            }
        }

        private void complete() throws SQLException {
            if (insertCount % WRITE_BATCH_SIZE != 0) {
                execute(); // otherwise the batch was run by the very last fragment, or there was no fragment
            }
        }

        private void execute() throws SQLException {
            if (deleteCount > 0) {
                delete.executeBatch();
                delete.clearBatch();
                deleteCount = 0;
            }
            insert.executeBatch();
            insert.clearBatch();
        }

        @Override
        public void close() throws SQLException {
            try (delete) {
                insert.close();
            }
        }
    }

    @Override
    public Flowable<Fragment> read(Transaction transaction, ZonedDateTime snapshot, String namespace, String entity, String id) {
        Query query = new Query();
        query.sql(SELECT_COLUMNS).sql(" FROM fragment f WHERE f.namespace = ").value(namespace)
                .sql(" AND f.entity = ").value(entity)
                .sql(" AND f.id = ").value(id)
                .sql(" AND f.version = (SELECT max(c.version) FROM fragment c WHERE c.namespace = ").value(namespace)
                .sql(AND_C_ENTITY).value(entity)
                .sql(" AND c.id = ").value(id)
                .sql(AND_C_VERSION_AT_MOST).value(instantOf(snapshot))
                .sql(") ORDER BY f.path, f.indices");
        return fragments(transaction, namespace, entity, query);
    }

    /**
     * The versions of one document, oldest first, or newest first for a backward range. {@code after} is not a
     * strict bound: the version that was valid at that time is the first one. {@code before} is exclusive. The
     * limit counts versions.
     */
    @Override
    public Flowable<Fragment> readVersions(Transaction transaction, String namespace, String entity, String id, Range<ZonedDateTime> range) {
        Query query = new Query();
        query.sql(SELECT_COLUMNS).sql(" FROM (SELECT DISTINCT v.id, v.version FROM fragment v WHERE v.namespace = ").value(namespace)
                .sql(" AND v.entity = ").value(entity)
                .sql(" AND v.id = ").value(id);
        if (range.hasBefore()) {
            query.sql(" AND v.version < ").value(instantOf(range.getBefore()));
        }
        if (range.hasAfter()) {
            query.sql(" AND v.version >= coalesce((SELECT max(c.version) FROM fragment c WHERE c.namespace = ").value(namespace)
                .sql(AND_C_ENTITY).value(entity)
                    .sql(" AND c.id = ").value(id)
                    .sql(AND_C_VERSION_AT_MOST).value(instantOf(range.getAfter()))
                    .sql("), '-infinity')");
        }
        query.sql(range.isBackward() ? " ORDER BY v.version DESC" : " ORDER BY v.version");
        return fragments(transaction, namespace, entity, documentsOf(query, namespace, entity, range, "d.version"));
    }

    /**
     * The version valid at the snapshot of every document of the entity whose id is inside the range, both bounds
     * exclusive, by ascending id, or descending for a backward range. The limit counts documents. A document that
     * is marked as deleted at the snapshot is returned as its marker.
     */
    @Override
    public Flowable<Fragment> readAll(Transaction transaction, ZonedDateTime snapshot, String namespace, String entity, Range<String> range) {
        Query query = new Query();
        query.sql(SELECT_COLUMNS).sql(" FROM (SELECT c.id, max(c.version) AS version FROM fragment c WHERE c.namespace = ").value(namespace)
                .sql(AND_C_ENTITY).value(entity)
                .sql(AND_C_VERSION_AT_MOST).value(instantOf(snapshot));
        idsInside(query, "c.id", range);
        query.sql(range.isBackward() ? " GROUP BY c.id ORDER BY c.id DESC" : " GROUP BY c.id ORDER BY c.id");
        return fragments(transaction, namespace, entity, documentsOf(query, namespace, entity, range, "d.id"));
    }

    @Override
    public Completable delete(Transaction transaction, String namespace, String entity, String id, ZonedDateTime version, PersistenceDeletePolicy policy) {
        Query query = new Query();
        query.sql(DELETE_WHERE_NAMESPACE).value(namespace)
                .sql(AND_ENTITY).value(entity)
                .sql(" AND id = ").value(id)
                .sql(" AND version = ").value(instantOf(version));
        return update(transaction, query);
    }

    @Override
    public Completable deleteAllVersions(Transaction transaction, String namespace, String entity, String id, PersistenceDeletePolicy policy) {
        Query query = new Query();
        query.sql(DELETE_WHERE_NAMESPACE).value(namespace)
                .sql(AND_ENTITY).value(entity)
                .sql(" AND id = ").value(id);
        return update(transaction, query);
    }

    @Override
    public Completable deleteAllEntities(Transaction transaction, String namespace, String entity, Iterable<String> paths) {
        Query query = new Query();
        query.sql(DELETE_WHERE_NAMESPACE).value(namespace)
                .sql(AND_ENTITY).value(entity);
        return update(transaction, query);
    }

    @Override
    public Completable markDeleted(Transaction transaction, String namespace, String entity, String id, ZonedDateTime timestamp, PersistenceDeletePolicy policy) {
        Query marker = new Query();
        marker.sql("INSERT INTO fragment(namespace, entity, id, version, path, indices, type) VALUES (").value(namespace)
                .sql(", ").value(entity)
                .sql(", ").value(id)
                .sql(", ").value(instantOf(timestamp))
                .sql(", '', '{}', ").value(FragmentType.DELETED.ordinal())
                .sql(")");
        return delete(transaction, namespace, entity, id, timestamp, policy).andThen(update(transaction, marker));
    }

    /**
     * The documents whose version valid at the snapshot holds the value at the path, by ascending id, or descending
     * for a backward range. The bounds of the range are exclusive and its limit counts documents. A document that
     * held the value in an earlier version only, or that is marked as deleted at the snapshot, is not returned.
     * <p>
     * The path is given without array positions ({@code $.tags[]}), and the value as it is stored. The index is
     * on a hash of the value, so a value of any length can be stored and found.
     */
    @Override
    public Flowable<Fragment> find(Transaction transaction, ZonedDateTime snapshot, String namespace, String entity, String path, byte[] value, Range<String> range) {
        OffsetDateTime snapshotInstant = instantOf(snapshot);
        Query query = new Query();
        query.sql(SELECT_COLUMNS).sql(" FROM (SELECT DISTINCT m.id, m.version FROM fragment m WHERE m.namespace = ").value(namespace)
                .sql(" AND m.entity = ").value(entity)
                .sql(" AND m.path = ").value(path)
                .sql(" AND sha256(m.value) = sha256(").value(value)
                .sql(") AND m.value = ").value(value)
                .sql(" AND m.version <= ").value(snapshotInstant);
        idsInside(query, "m.id", range);
        query.sql(" AND m.version = (SELECT max(c.version) FROM fragment c"
                        + " WHERE c.namespace = m.namespace AND c.entity = m.entity AND c.id = m.id AND c.version <= ").value(snapshotInstant)
                .sql(range.isBackward() ? ") ORDER BY m.id DESC" : ") ORDER BY m.id");
        return fragments(transaction, namespace, entity, documentsOf(query, namespace, entity, range, "d.id"));
    }

    @Override
    public Single<Boolean> hasPrevious(Transaction tx, ZonedDateTime snapshot, String namespace, String entityName, String id) {
        return readAll(tx, snapshot, namespace, entityName, Range.lastBefore(1, id)).isEmpty()
                .map(wasEmpty -> !wasEmpty);
    }

    @Override
    public Single<Boolean> hasNext(Transaction tx, ZonedDateTime snapshot, String namespace, String entityName, String id) {
        return readAll(tx, snapshot, namespace, entityName, Range.firstAfter(1, id)).isEmpty()
                .map(wasEmpty -> !wasEmpty);
    }

    @Override
    public void close() throws PersistenceException {
        transactionFactory.close();
    }

    private static void idsInside(Query query, String column, Range<String> range) {
        if (range.hasAfter()) {
            query.sql(" AND ").sql(column).sql(" > ").value(range.getAfter());
        }
        if (range.hasBefore()) {
            query.sql(" AND ").sql(column).sql(" < ").value(range.getBefore());
        }
    }

    /**
     * Completes a query that selects the fragments of the document versions an inner query picks. The inner query
     * ends in its {@code ORDER BY}; the limit of the range is put on it, so that it counts document versions and
     * not rows.
     * <p>
     * The rows of each document version are fetched by primary key, one version after the other in the order of
     * the inner query. Written as a join of the two, PostgreSQL chooses the way to run it from its statistics, and
     * without statistics (a table that was just filled) it compares every row with every document. In this form
     * the first document is returned before the next one is looked up, whatever the statistics say.
     *
     * @param order the column of the inner query it is ordered by, {@code d.id} or {@code d.version}
     */
    private static Query documentsOf(Query query, String namespace, String entity, Range<?> range, String order) {
        if (range.getLimit() != null) {
            query.sql(" LIMIT ").value(range.getLimit());
        }
        query.sql(") d CROSS JOIN LATERAL (SELECT r.id, r.version, r.path, r.indices, r.type, r.value FROM fragment r"
                        + " WHERE r.namespace = ").value(namespace)
                .sql(" AND r.entity = ").value(entity)
                .sql(" AND r.id = d.id AND r.version = d.version ORDER BY r.path, r.indices) f ORDER BY ").sql(order);
        return query.sql(range.isBackward() ? " DESC, f.path, f.indices" : ", f.path, f.indices");
    }

    private Flowable<Fragment> fragments(Transaction transaction, String namespace, String entity, Query query) {
        Connection connection = connectionOf(transaction);
        return Flowable.using(
                () -> query.prepare(connection),
                statement -> {
                    statement.setFetchSize(FETCH_SIZE);
                    return Flowable.fromPublisher(new ResultSetPublisher(statement.executeQuery()))
                            .map(row -> toFragment(row, namespace, entity));
                },
                PreparedStatement::close);
    }

    private static Completable update(Transaction transaction, Query query) {
        Connection connection = connectionOf(transaction);
        return Completable.fromAction(() -> {
            try (PreparedStatement statement = query.prepare(connection)) {
                statement.executeUpdate();
            }
        });
    }

    private static Connection connectionOf(Transaction transaction) {
        return ((PostgresTransaction) transaction).connection;
    }

    private static Fragment toFragment(ResultSet row, String namespace, String entity) throws SQLException {
        String id = row.getString(1);
        ZonedDateTime timestamp = ZonedDateTime.ofInstant(row.getObject(2, OffsetDateTime.class).toInstant(), UTC);
        String indexUnawarePath = row.getString(3);
        Array indices = row.getArray(4);
        String path = Fragment.computePathFromIndexUnawarePathAndIndices(indexUnawarePath, List.of((Integer[]) indices.getArray()));
        short type = row.getShort(5);
        byte[] value = row.getBytes(6);
        return new Fragment(new DocumentKey(namespace, entity, id, timestamp), path, FragmentType.fromTypeCode((byte) type), 0, value);
    }

    /**
     * A version as it is stored and compared: the instant, cut to whole milliseconds.
     */
    private static OffsetDateTime instantOf(ZonedDateTime timestamp) {
        return timestamp.toInstant().truncatedTo(ChronoUnit.MILLIS).atOffset(ZoneOffset.UTC);
    }

    /**
     * The text of a statement and the values of its parameters, built side by side. Only text written in this
     * class becomes part of the statement; every value that comes from outside is a parameter.
     */
    private static final class Query {
        private final StringBuilder sql = new StringBuilder();
        private final List<Object> values = new ArrayList<>();

        private Query sql(String text) {
            sql.append(text);
            return this;
        }

        private Query value(Object value) {
            sql.append('?');
            values.add(value);
            return this;
        }

        private PreparedStatement prepare(Connection connection) throws SQLException {
            PreparedStatement statement = connection.prepareStatement(sql.toString());
            try {
                for (int i = 0; i < values.size(); i++) {
                    switch (values.get(i)) {
                        case String text -> statement.setString(i + 1, text);
                        case byte[] bytes -> statement.setBytes(i + 1, bytes);
                        case Integer number -> statement.setInt(i + 1, number);
                        case Object other -> statement.setObject(i + 1, other);
                    }
                }
                return statement;
            } catch (SQLException | RuntimeException e) {
                statement.close();
                throw e;
            }
        }
    }
}
