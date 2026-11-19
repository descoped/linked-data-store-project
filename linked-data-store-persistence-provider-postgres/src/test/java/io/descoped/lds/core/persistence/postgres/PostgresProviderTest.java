package io.descoped.lds.core.persistence.postgres;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.PersistenceException;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.Range;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.persistence.streaming.Fragment;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.descoped.lds.api.persistence.json.JsonTools.mapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the PostgreSQL provider must do beyond the shared suite, on a real PostgreSQL. Each test uses an entity of
 * its own, so the tests do not see each other's documents.
 */
class PostgresProviderTest {

    private static final ZoneId UTC = ZoneId.of("Etc/UTC");
    private static final ZonedDateTime T1 = ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, UTC);
    private static final ZonedDateTime T2 = ZonedDateTime.of(2021, 1, 1, 0, 0, 0, 0, UTC);
    private static final ZonedDateTime T3 = ZonedDateTime.of(2022, 1, 1, 0, 0, 0, 0, UTC);
    private static final ZonedDateTime NOW = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, UTC);
    private static final String NS = "provider-test";
    private static final JsonNavigationPath CITY = JsonNavigationPath.from("$.city");

    private static PostgresDbInitializer initializer;
    private static RxJsonPersistence persistence;
    private static PostgresPersistence fragments;

    @BeforeAll
    static void start() {
        initializer = new PostgresDbInitializer();
        persistence = initializer.initialize(NS, Postgres18.configuration(), Set.of());
        fragments = initializer.getPostgresPersistence();
    }

    @AfterAll
    static void stop() {
        if (persistence != null) {
            persistence.close();
        }
    }

    // ---- start

    @Test
    void startingLeavesNoConnectionInUse() {
        assertThat(fragments.transactionFactory.dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void instancesThatStartTogetherOnAnEmptyDatabaseAllStart() throws InterruptedException {
        int instances = 4;
        List<Throwable> failures = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            Map<String, String> configuration = Postgres18.configurationOfNewDatabase(round);
            CountDownLatch together = new CountDownLatch(1);
            try (ExecutorService threads = Executors.newFixedThreadPool(instances)) {
                List<Future<RxJsonPersistence>> starting = new ArrayList<>();
                for (int i = 0; i < instances; i++) {
                    starting.add(threads.submit(() -> {
                        together.await();
                        return new PostgresDbInitializer().initialize(NS, configuration, Set.of());
                    }));
                }
                together.countDown();
                for (Future<RxJsonPersistence> start : starting) {
                    try {
                        start.get().close();
                    } catch (ExecutionException e) {
                        failures.add(e.getCause());
                    }
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    // ---- namespaces, ids, versions

    @Test
    void aDocumentIsSeenInItsOwnNamespaceOnly() {
        write("ns-a", "Separate", "same", T1, city("from a"));
        write("ns-b", "Separate", "same", T1, city("from b"));
        write("ns-a", "Separate", "only-a", T1, city("from a"));

        assertThat(read("ns-a", "Separate", "same", NOW).jackson().get("city").asText()).isEqualTo("from a");
        assertThat(read("ns-b", "Separate", "same", NOW).jackson().get("city").asText()).isEqualTo("from b");
        assertThat(read("ns-c", "Separate", "same", NOW)).isNull();
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(ids(persistence.readDocuments(tx, NOW, "ns-a", "Separate", Range.unbounded()))).containsExactly("only-a", "same");
            assertThat(ids(persistence.readDocuments(tx, NOW, "ns-b", "Separate", Range.unbounded()))).containsExactly("same");
            assertThat(ids(persistence.readDocuments(tx, NOW, "ns-c", "Separate", Range.unbounded()))).isEmpty();
            assertThat(ids(persistence.findDocument(tx, NOW, "ns-b", "Separate", CITY, "from a", Range.unbounded()))).isEmpty();
            assertThat(ids(persistence.findDocument(tx, NOW, "ns-a", "Separate", CITY, "from a", Range.unbounded()))).containsExactly("only-a", "same");
            assertThat(persistence.readDocumentVersions(tx, "ns-c", "Separate", "same", Range.unbounded()).toList().blockingGet()).isEmpty();
        }
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.deleteAllDocumentVersions(tx, "ns-b", "Separate", "same", null).blockingAwait();
        }
        assertThat(read("ns-b", "Separate", "same", NOW)).isNull();
        assertThat(read("ns-a", "Separate", "same", NOW)).isNotNull();
    }

    @Test
    void anUnboundedListHoldsEveryId() {
        List<String> written = List.of("abc", "zzz", "Ærlig", "~tilde", "ÅÅÅ", "日本", "!bang", " space");
        for (String id : written) {
            write(NS, "AnyId", id, T1, city(id));
        }
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(ids(persistence.readDocuments(tx, NOW, NS, "AnyId", Range.unbounded())))
                    .containsExactlyElementsOf(written.stream().sorted().toList());
            assertThat(persistence.hasNext(tx, NOW, NS, "AnyId", "zzz").blockingGet()).isTrue();
            assertThat(persistence.hasNext(tx, NOW, NS, "AnyId", "日本").blockingGet()).isFalse();
            assertThat(persistence.hasPrevious(tx, NOW, NS, "AnyId", "!bang").blockingGet()).isTrue();
            assertThat(persistence.hasPrevious(tx, NOW, NS, "AnyId", " space").blockingGet()).isFalse();
        }
    }

    @Test
    void aVersionIsTheInstantWhateverZoneItWasWrittenWith() {
        ZonedDateTime noonInOslo = ZonedDateTime.of(2020, 6, 1, 12, 0, 0, 0, ZoneId.of("Europe/Oslo"));
        write(NS, "Zoned", "z", noonInOslo, city("Oslo"));

        assertThat(read(NS, "Zoned", "z", ZonedDateTime.of(2020, 6, 1, 9, 59, 59, 0, UTC))).isNull();
        JsonDocument atTen = read(NS, "Zoned", "z", ZonedDateTime.of(2020, 6, 1, 10, 0, 0, 0, UTC));
        assertThat(atTen).isNotNull();
        assertThat(atTen.key().timestamp()).isEqualTo(ZonedDateTime.of(2020, 6, 1, 10, 0, 0, 0, UTC));
    }

    // ---- find

    @Test
    void findReturnsEveryMatchAndHonoursTheRange() {
        List<JsonDocument> documents = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            documents.add(document(NS, "Many", String.format("f%04d", i), T1, city("Oslo")));
        }
        documents.add(document(NS, "Many", "other", T1, city("Bergen")));
        writeAll(documents);

        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(ids(persistence.findDocument(tx, NOW, NS, "Many", CITY, "Oslo", Range.unbounded()))).hasSize(250);
            assertThat(ids(persistence.findDocument(tx, NOW, NS, "Many", CITY, "Oslo", Range.first(3))))
                    .containsExactly("f0000", "f0001", "f0002");
            assertThat(ids(persistence.findDocument(tx, NOW, NS, "Many", CITY, "Oslo", Range.firstAfter(3, "f0200"))))
                    .containsExactly("f0201", "f0202", "f0203");
            assertThat(ids(persistence.findDocument(tx, NOW, NS, "Many", CITY, "Oslo", Range.last(2))))
                    .containsExactly("f0249", "f0248");
            assertThat(ids(persistence.findDocument(tx, NOW, NS, "Many", CITY, "Oslo", Range.lastBefore(2, "f0100"))))
                    .containsExactly("f0099", "f0098");

            // the provider itself: the limit counts documents, and a backward range is read from the end
            assertThat(documentIds(fragments.find(tx, NOW, NS, "Many", "$.city", "Oslo".getBytes(), Range.first(3))))
                    .containsExactly("f0000", "f0001", "f0002");
            assertThat(documentIds(fragments.find(tx, NOW, NS, "Many", "$.city", "Oslo".getBytes(), Range.last(2))))
                    .containsExactly("f0249", "f0248");
            assertThat(documentIds(fragments.find(tx, NOW, NS, "Many", "$.city", "Oslo".getBytes(), Range.between("f0010", "f0013"))))
                    .containsExactly("f0011", "f0012");
        }
    }

    @Test
    void findLooksAtTheVersionValidAtTheSnapshot() {
        write(NS, "Moved", "m", T1, city("Oslo"));
        write(NS, "Moved", "m", T2, city("Bergen"));

        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(ids(persistence.findDocument(tx, NOW, NS, "Moved", CITY, "Oslo", Range.unbounded()))).isEmpty();
            assertThat(ids(persistence.findDocument(tx, NOW, NS, "Moved", CITY, "Bergen", Range.unbounded()))).containsExactly("m");
            // back in time, the document is in Oslo
            List<JsonDocument> then = persistence.findDocument(tx, T1.plusDays(1), NS, "Moved", CITY, "Oslo", Range.unbounded())
                    .toList().blockingGet();
            assertThat(then).hasSize(1);
            assertThat(then.getFirst().key().timestamp()).isEqualTo(T1);
            assertThat(ids(persistence.findDocument(tx, T1.plusDays(1), NS, "Moved", CITY, "Bergen", Range.unbounded()))).isEmpty();
            assertThat(ids(persistence.findDocument(tx, T1.minusDays(1), NS, "Moved", CITY, "Oslo", Range.unbounded()))).isEmpty();
        }
    }

    @Test
    void findDoesNotReturnADocumentMarkedAsDeleted() {
        write(NS, "Gone", "g", T1, city("Oslo"));
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.markDocumentDeleted(tx, NS, "Gone", "g", T2, null).blockingAwait();
        }

        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(ids(persistence.findDocument(tx, NOW, NS, "Gone", CITY, "Oslo", Range.unbounded()))).isEmpty();
            // before it was deleted, it is found
            assertThat(ids(persistence.findDocument(tx, T1.plusDays(1), NS, "Gone", CITY, "Oslo", Range.unbounded()))).containsExactly("g");
        }
    }

    @Test
    void findSeesAValueInsideAnArrayOnce() {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("tags").add("red").add("red").add("blue");
        write(NS, "Tagged", "t", T1, body);

        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(documentIds(fragments.find(tx, NOW, NS, "Tagged", "$.tags[]", "red".getBytes(), Range.unbounded())))
                    .containsExactly("t");
        }
    }

    // ---- long values

    @Test
    void aTextOfThreeByteCharactersIsStored() {
        for (int length : new int[]{300, 5_000}) {
            String text = "€".repeat(length);
            write(NS, "Text", "euro" + length, T1, mapper.createObjectNode().put("text", text));
            assertThat(read(NS, "Text", "euro" + length, NOW).jackson().get("text").asText()).isEqualTo(text);
        }
    }

    @Test
    void aLongTextIsStoredAndFound() {
        Random random = new Random(7);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            text.append((char) ('!' + random.nextInt(90)));
        }
        write(NS, "Text", "long", T1, city(text.toString()));
        write(NS, "Text", "almost", T1, city(text.substring(0, text.length() - 1) + "å"));

        assertThat(read(NS, "Text", "long", NOW).jackson().get("city").asText()).isEqualTo(text.toString());
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(ids(persistence.findDocument(tx, NOW, NS, "Text", CITY, text.toString(), Range.unbounded()))).containsExactly("long");
        }
    }

    // ---- large results

    @Test
    void aDocumentOfAnySizeIsRead() {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode values = body.putArray("values");
        for (int i = 0; i < 100_000; i++) {
            values.add("v" + i);
        }
        write(NS, "Large", "document", T1, body);

        JsonDocument read = read(NS, "Large", "document", NOW);
        assertThat(read.jackson().get("values")).hasSize(100_000);
        assertThat(read.jackson().get("values").get(99_999).asText()).isEqualTo("v99999");
    }

    @Test
    void aListOfAnySizeIsRead() {
        List<JsonDocument> documents = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            documents.add(document(NS, "LargeList", String.format("d%06d", i), T1, mapper.createObjectNode().put("n", i)));
        }
        writeAll(documents);

        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(persistence.readDocuments(tx, NOW, NS, "LargeList", Range.unbounded())
                    .count().timeout(5, TimeUnit.MINUTES).blockingGet()).isEqualTo(100_000);
            assertThat(ids(persistence.readDocuments(tx, NOW, NS, "LargeList", Range.first(2)))).containsExactly("d000000", "d000001");
            assertThat(ids(persistence.readDocuments(tx, NOW, NS, "LargeList", Range.last(2)))).containsExactly("d099999", "d099998");
        }
    }

    @Test
    void aMillionRowsAreWalkedWithUnboundedDemand() throws SQLException {
        PostgresTransaction tx = fragments.transactionFactory.createTransaction(true);
        try (PreparedStatement statement = tx.connection.prepareStatement("SELECT * FROM generate_series(1, 1000000)")) {
            statement.setFetchSize(1_000);
            long[] sum = new long[1];
            long rows = Flowable.fromPublisher(new ResultSetPublisher(statement.executeQuery()))
                    .doOnNext(row -> sum[0] += row.getInt(1))
                    .count().blockingGet();
            assertThat(rows).isEqualTo(1_000_000);
            assertThat(sum[0]).isEqualTo(500_000_500_000L);
        } finally {
            tx.cancel().join();
        }
    }

    // ---- the provider's own reads: order and limit

    @Test
    void theLimitOfAListCountsDocumentsAndABackwardRangeIsReadFromTheEnd() {
        for (String id : List.of("a", "b", "c", "d")) {
            write(NS, "Listed", id, T1, mapper.createObjectNode().put("one", 1).put("two", 2).put("three", 3));
        }
        write(NS, "Listed", "b", T2, mapper.createObjectNode().put("one", 1).put("two", 2));
        write(NS, "Listed", "later", T3, city("not there at T2"));

        try (Transaction tx = persistence.createTransaction(true)) {
            List<Fragment> firstTwo = fragments.readAll(tx, T2, NS, "Listed", Range.first(2)).toList().blockingGet();
            assertThat(firstTwo).hasSize(5); // a has three values, and b two in its version of T2
            assertThat(documentIds(Flowable.fromIterable(firstTwo))).containsExactly("a", "b");
            assertThat(firstTwo.get(3).timestamp()).isEqualTo(T2);

            assertThat(documentIds(fragments.readAll(tx, T2, NS, "Listed", Range.unbounded()))).containsExactly("a", "b", "c", "d");
            assertThat(documentIds(fragments.readAll(tx, T2, NS, "Listed", Range.last(2)))).containsExactly("d", "c");
            assertThat(documentIds(fragments.readAll(tx, T2, NS, "Listed", Range.firstAfter(2, "a")))).containsExactly("b", "c");
            assertThat(documentIds(fragments.readAll(tx, T2, NS, "Listed", Range.lastBefore(1, "c")))).containsExactly("b");
            assertThat(documentIds(fragments.readAll(tx, T2, NS, "Listed", Range.between("a", "d")))).containsExactly("b", "c");
            assertThat(documentIds(fragments.readAll(tx, T1.minusDays(1), NS, "Listed", Range.unbounded()))).isEmpty();
        }
    }

    @Test
    void theVersionsOfADocumentAreReadInOrderOfTime() {
        for (ZonedDateTime version : List.of(T1, T2, T3)) {
            write(NS, "Versioned", "v", version, mapper.createObjectNode().put("one", 1).put("two", 2));
        }

        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(versions(fragments.readVersions(tx, NS, "Versioned", "v", Range.unbounded()))).containsExactly(T1, T2, T3);
            assertThat(versions(fragments.readVersions(tx, NS, "Versioned", "v", Range.first(2)))).containsExactly(T1, T2);
            assertThat(versions(fragments.readVersions(tx, NS, "Versioned", "v", Range.last(2)))).containsExactly(T3, T2);
            // the version valid at "after" is the first one; "before" is exclusive
            assertThat(versions(fragments.readVersions(tx, NS, "Versioned", "v", Range.between(T2.plusDays(1), T3)))).containsExactly(T2);
            assertThat(versions(fragments.readVersions(tx, NS, "Versioned", "v", Range.between(T1.minusDays(1), T3)))).containsExactly(T1, T2);

            assertThat(persistence.readDocumentVersions(tx, NS, "Versioned", "v", Range.unbounded())
                    .map(document -> document.key().timestamp()).toList().blockingGet()).containsExactly(T1, T2, T3);
        }
    }

    // ---- statements and transactions

    @Test
    void aReadLeavesNoCursorOpenOnTheServer() throws SQLException {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode values = body.putArray("values");
        for (int i = 0; i < 50; i++) {
            values.add(i);
        }
        write(NS, "Cursor", "one", T1, body);
        write(NS, "Cursor", "two", T1, body);

        PostgresTransaction tx = fragments.transactionFactory.createTransaction(true);
        try {
            // the check itself: a statement read in batches and left open holds a cursor
            try (PreparedStatement open = tx.connection.prepareStatement("SELECT * FROM generate_series(1, 100)")) {
                open.setFetchSize(10);
                assertThat(open.executeQuery().next()).isTrue();
                assertThat(openCursors(tx.connection)).isOne();
            }
            assertThat(openCursors(tx.connection)).isZero();
            // read to the end
            assertThat(fragments.readAll(tx, NOW, NS, "Cursor", Range.unbounded()).count().blockingGet()).isEqualTo(100);
            assertThat(openCursors(tx.connection)).isZero();
            // stopped after the first of a hundred rows
            assertThat(fragments.readAll(tx, NOW, NS, "Cursor", Range.unbounded()).firstElement().blockingGet()).isNotNull();
            assertThat(openCursors(tx.connection)).isZero();
            assertThat(persistence.hasNext(tx, NOW, NS, "Cursor", "one").blockingGet()).isTrue();
            assertThat(openCursors(tx.connection)).isZero();
        } finally {
            tx.cancel().join();
        }
    }

    @Test
    void aCancelledTransactionWritesNothing() {
        Transaction tx = persistence.createTransaction(false);
        persistence.createOrOverwrite(tx, document(NS, "Cancelled", "c", T1, city("Oslo")), null).blockingAwait();
        assertThat(persistence.readDocument(tx, NOW, NS, "Cancelled", "c").blockingGet()).isNotNull();
        tx.cancel().join();

        assertThat(read(NS, "Cancelled", "c", NOW)).isNull();
    }

    @Test
    void endingATransactionAgainDoesNothing() {
        Transaction tx = persistence.createTransaction(false);
        persistence.createOrOverwrite(tx, document(NS, "Ended", "e", T1, city("Oslo")), null).blockingAwait();
        tx.commit().join();
        tx.cancel().join();
        tx.commit().join();
        tx.close();

        assertThat(read(NS, "Ended", "e", NOW)).isNotNull();
        assertThat(fragments.transactionFactory.dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void aCommitThatFailsFailsItsFuture() throws SQLException {
        PostgresTransaction tx = fragments.transactionFactory.createTransaction(false);
        tx.connection.close(); // the connection is lost under the transaction

        assertThatThrownBy(() -> tx.commit().join()).hasCauseInstanceOf(PersistenceException.class);
        tx.cancel().join();
    }

    // ---- helpers

    private static ObjectNode city(String city) {
        return mapper.createObjectNode().put("city", city);
    }

    private static JsonDocument document(String namespace, String entity, String id, ZonedDateTime version, ObjectNode body) {
        return new JsonDocument(new DocumentKey(namespace, entity, id, version), body);
    }

    private static void write(String namespace, String entity, String id, ZonedDateTime version, ObjectNode body) {
        writeAll(List.of(document(namespace, entity, id, version, body)));
    }

    private static void writeAll(List<JsonDocument> documents) {
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.createOrOverwrite(tx, Flowable.fromIterable(documents), null).blockingAwait();
        }
    }

    private static JsonDocument read(String namespace, String entity, String id, ZonedDateTime snapshot) {
        try (Transaction tx = persistence.createTransaction(true)) {
            return persistence.readDocument(tx, snapshot, namespace, entity, id).blockingGet();
        }
    }

    private static List<String> ids(Flowable<JsonDocument> documents) {
        return documents.map(document -> document.key().id()).toList().blockingGet();
    }

    /**
     * The ids of the documents a fragment stream holds, in the order they come.
     */
    private static List<String> documentIds(Flowable<Fragment> stream) {
        return stream.map(Fragment::id).distinctUntilChanged().toList().blockingGet();
    }

    private static List<ZonedDateTime> versions(Flowable<Fragment> stream) {
        return stream.map(Fragment::timestamp).distinctUntilChanged().toList().blockingGet();
    }

    /**
     * The named cursors the server holds open for the connection. A statement that is read in batches has one
     * until it is closed.
     */
    private static int openCursors(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT count(*) FROM pg_cursors WHERE name <> ''")) {
            row.next();
            return row.getInt(1);
        }
    }
}
