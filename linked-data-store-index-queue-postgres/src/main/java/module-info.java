import io.descoped.lds.api.search.IndexQueueProvider;
import io.descoped.lds.core.search.queue.postgres.PostgresIndexQueueProvider;

/**
 * The index queue {@code postgres}: the updates the search index has not taken, kept in a table of the PostgreSQL
 * database of the transaction log.
 */
// The PostgreSQL JDBC driver is an automatic module, with its name in the manifest.
@SuppressWarnings("requires-automatic")
module io.descoped.lds.index.queue.postgres {
    requires io.descoped.lds.search.api;
    requires java.sql;
    requires com.zaxxer.hikari;
    requires org.postgresql.jdbc;

    provides IndexQueueProvider with PostgresIndexQueueProvider;
}
