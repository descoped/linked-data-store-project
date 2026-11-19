import io.descoped.lds.api.persistence.PersistenceInitializer;
import io.descoped.lds.core.persistence.postgres.PostgresDbInitializer;

/**
 * The persistence provider {@code postgres}: documents as fragments in a PostgreSQL database.
 */
// The PostgreSQL JDBC driver is an automatic module, with its name in the manifest.
@SuppressWarnings("requires-automatic")
module io.descoped.lds.persistence.postgres {
    requires io.descoped.lds.persistence.api;
    requires java.sql;
    requires com.zaxxer.hikari;
    requires org.postgresql.jdbc;
    requires java.logging;
    requires jul.to.slf4j;
    requires io.reactivex.rxjava3;

    provides PersistenceInitializer with PostgresDbInitializer;
}
