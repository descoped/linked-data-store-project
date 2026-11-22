package io.descoped.lds.core.search.queue.postgres;

import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.IndexQueueProvider;
import io.descoped.lds.api.search.IndexUpdate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queue in PostgreSQL 18: found by the service loader under the name of the transaction log provider, durable,
 * taken in order, held for a lease, shared by several LDS, put off with one attempt more.
 */
class PostgresIndexQueueTest {

    private static final PostgreSQLContainer POSTGRES = start();

    @SuppressWarnings("resource") // stopped by Testcontainers when the JVM ends
    private static PostgreSQLContainer start() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:18-alpine")
                .withDatabaseName("txlog").withUsername("lds").withPassword("lds");
        container.start();
        LoopbackPorts.check(container);
        return container;
    }

    private static Map<String, String> configuration() {
        return Map.of(
                PostgresIndexQueueProvider.HOST, POSTGRES.getHost(),
                PostgresIndexQueueProvider.PORT, String.valueOf(POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)),
                PostgresIndexQueueProvider.USER, POSTGRES.getUsername(),
                PostgresIndexQueueProvider.PASSWORD, POSTGRES.getPassword(),
                PostgresIndexQueueProvider.DATABASE, POSTGRES.getDatabaseName());
    }

    private static IndexQueue open() {
        return new PostgresIndexQueueProvider().indexQueue(configuration());
    }

    private IndexQueue queue;

    @BeforeEach
    void emptyQueue() throws SQLException {
        queue = open();
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE lds_index_queue");
        }
    }

    @AfterEach
    void close() {
        queue.close();
    }

    private static IndexUpdate update(String id, IndexUpdate.Operation operation) {
        return new IndexUpdate("ns", "Person", id, ZonedDateTime.parse("2026-01-01T01:00:00.123456+01:00[Europe/Oslo]"),
                operation);
    }

    private List<String> taken(IndexQueue from, int max, Duration lease) {
        return from.take(max, lease).blockingGet().stream().map(entry -> entry.update().id()).toList();
    }

    @Test
    void theProviderIsFoundUnderTheNameOfTheTransactionLogProvider() {
        List<String> names = ServiceLoader.load(IndexQueueProvider.class).stream()
                .map(provider -> provider.get().providerId()).toList();

        assertThat(names).contains("postgres");
        assertThat(new PostgresIndexQueueProvider().configurationKeys()).allMatch(key -> key.startsWith("txlog.config.postgres."));
        assertThat(queue.durable()).isTrue();
    }

    @Test
    void anUpdateComesBackAsItWasAddedWithTheZoneOfItsVersion() {
        IndexUpdate deleted = update("gone", IndexUpdate.Operation.DELETE);
        queue.add(deleted).blockingAwait();

        List<IndexQueue.Entry> entries = queue.take(10, Duration.ofMinutes(1)).blockingGet();

        assertThat(entries).extracting(IndexQueue.Entry::update).containsExactly(deleted);
        assertThat(entries.getFirst().update().version().getZone().getId()).isEqualTo("Europe/Oslo");
        assertThat(entries.getFirst().attempts()).isZero();
    }

    @Test
    void updatesAreTakenInOrderAtMostTheNumberAskedAndNotAgainWhileTheirLeaseLasts() throws InterruptedException {
        for (String id : List.of("c", "a", "b")) {
            queue.add(update(id, IndexUpdate.Operation.CREATE_OR_OVERWRITE)).blockingAwait();
        }

        assertThat(taken(queue, 2, Duration.ofMillis(500))).containsExactly("c", "a");
        assertThat(taken(queue, 10, Duration.ofMinutes(1))).containsExactly("b");
        assertThat(taken(queue, 10, Duration.ofMinutes(1))).isEmpty();
        Thread.sleep(700);
        assertThat(taken(queue, 10, Duration.ofMinutes(1))).containsExactly("c", "a");
    }

    @Test
    void twoLdsOnOneDatabaseTakeDifferentUpdates() {
        for (int i = 0; i < 6; i++) {
            queue.add(update("p" + i, IndexUpdate.Operation.CREATE_OR_OVERWRITE)).blockingAwait();
        }
        try (IndexQueue other = open()) {
            List<String> first = taken(queue, 3, Duration.ofMinutes(1));
            List<String> second = taken(other, 10, Duration.ofMinutes(1));

            Set<String> all = new HashSet<>(first);
            all.addAll(second);
            assertThat(first).hasSize(3);
            assertThat(second).hasSize(3);
            assertThat(all).hasSize(6);
        }
    }

    @Test
    void anUpdateThatIsPutOffWaitsAndHasOneAttemptMoreAndOneThatIsRemovedIsGone() throws InterruptedException {
        queue.add(update("a", IndexUpdate.Operation.CREATE_OR_OVERWRITE)).blockingAwait();
        queue.add(update("b", IndexUpdate.Operation.CREATE_OR_OVERWRITE)).blockingAwait();
        List<IndexQueue.Entry> entries = queue.take(10, Duration.ofMinutes(1)).blockingGet();

        queue.remove(List.of(entries.get(1))).blockingAwait();
        queue.postpone(List.of(entries.getFirst()), Duration.ofMillis(500)).blockingAwait();

        assertThat(taken(queue, 10, Duration.ofMinutes(1))).isEmpty();
        Thread.sleep(700);
        assertThat(queue.take(10, Duration.ofMinutes(1)).blockingGet())
                .extracting(entry -> entry.update().id(), IndexQueue.Entry::attempts)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a", 1));
        queue.remove(List.of()).blockingAwait();
        queue.postpone(List.of(), Duration.ofSeconds(1)).blockingAwait();
    }

    @Test
    void theLagCountsTheUpdatesAndTellsWhenTheOldestWasAdded() {
        assertThat(queue.lag().blockingGet()).isEqualTo(new IndexQueue.Lag(0, null));
        Instant before = Instant.now().minusSeconds(5);
        queue.add(update("a", IndexUpdate.Operation.CREATE_OR_OVERWRITE)).blockingAwait();
        queue.add(update("b", IndexUpdate.Operation.DELETE)).blockingAwait();

        IndexQueue.Lag lag = queue.lag().blockingGet();

        assertThat(lag.updates()).isEqualTo(2);
        assertThat(lag.oldest()).isAfter(before);
    }

    @Test
    void theQueueIsKeptWhenLdsStopsAndTheTableIsMadeOnlyOnce() {
        queue.add(update("kept", IndexUpdate.Operation.CREATE_OR_OVERWRITE)).blockingAwait();
        queue.close();

        queue = open();

        assertThat(taken(queue, 10, Duration.ofMinutes(1))).containsExactly("kept");
    }
}
