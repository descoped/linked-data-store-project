package io.descoped.lds.persistence.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.PersistenceDeletePolicy;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.PersistenceAdapter;
import io.descoped.lds.api.persistence.reactivex.Range;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The adapter that core uses until it moves to the 3.0 interface, on the memory provider.
 */
class PersistenceAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PersistenceDeletePolicy POLICY = PersistenceDeletePolicy.FAIL_IF_INCOMING_LINKS;

    private final RxJsonPersistence persistence = new PersistenceAdapter(new MemoryPersistenceProvider().open(Map.of(), null));

    private static JsonDocument person(String id, String version, String firstname, String... addresses) {
        ObjectNode json = JSON.createObjectNode().put("firstname", firstname);
        for (String address : addresses) {
            json.withArray("addresses").add(address);
        }
        return new JsonDocument(new DocumentKey("ns", "Person", id, ZonedDateTime.parse(version)), json);
    }

    private void write(JsonDocument... documents) {
        try (Transaction tx = persistence.createTransaction(false)) {
            for (JsonDocument document : documents) {
                persistence.createOrOverwrite(tx, document, null).blockingAwait();
            }
        }
    }

    @Test
    void aVersionWrittenWithAnOffsetIsTheSameInstantInAnyZone() {
        write(person("z", "2020-06-01T12:00:00+02:00", "Zone"));

        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(persistence.readDocument(tx, ZonedDateTime.parse("2020-06-01T09:59:59Z"), "ns", "Person", "z").blockingGet()).isNull();
            JsonDocument atTen = persistence.readDocument(tx, ZonedDateTime.parse("2020-06-01T10:00:00Z"), "ns", "Person", "z").blockingGet();
            assertThat(atTen.key().timestamp()).isEqualTo(ZonedDateTime.parse("2020-06-01T10:00:00Z[Etc/UTC]"));
            assertThat(persistence.readDocument(tx, ZonedDateTime.parse("2020-06-01T05:30:00-05:00"), "ns", "Person", "z").blockingGet()).isNotNull();
        }

        write(person("z", "2020-06-01T06:00:00-04:00", "Same instant, other zone"));
        try (Transaction tx = persistence.createTransaction(true)) {
            List<JsonDocument> versions = persistence.readDocumentVersions(tx, "ns", "Person", "z", Range.unbounded()).toList().blockingGet();
            assertThat(versions).hasSize(1);
            assertThat(versions.getFirst().jackson().get("firstname").textValue()).isEqualTo("Same instant, other zone");
        }
    }

    @Test
    void closingATransactionCommitsAsCoreExpectsAndCancelRollsBack() {
        write(person("kept", "2020-01-01T00:00:00Z", "Kept"));
        Transaction cancelled = persistence.createTransaction(false);
        persistence.createOrOverwrite(cancelled, person("dropped", "2020-01-01T00:00:00Z", "Dropped"), null).blockingAwait();
        cancelled.cancel().join();

        try (Transaction tx = persistence.createTransaction(true)) {
            ZonedDateTime now = ZonedDateTime.parse("2021-01-01T00:00:00Z");
            assertThat(persistence.readDocument(tx, now, "ns", "Person", "kept").blockingGet()).isNotNull();
            assertThat(persistence.readDocument(tx, now, "ns", "Person", "dropped").blockingGet()).isNull();
            assertThat(persistence.hasNext(tx, now, "ns", "Person", "a").blockingGet()).isTrue();
            assertThat(persistence.hasPrevious(tx, now, "ns", "Person", "a").blockingGet()).isFalse();
        }
    }

    @Test
    void deleteMarkersAreDeletedDocumentsInTheVersionList() {
        write(person("john", "2020-01-01T00:00:00Z", "John"));
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.markDocumentDeleted(tx, "ns", "Person", "john", ZonedDateTime.parse("2020-02-01T00:00:00Z"), POLICY).blockingAwait();
        }
        try (Transaction tx = persistence.createTransaction(false)) {
            List<JsonDocument> versions = persistence.readDocumentVersions(tx, "ns", "Person", "john", Range.unbounded()).toList().blockingGet();
            assertThat(versions).extracting(JsonDocument::deleted).containsExactly(false, true);
            assertThat(persistence.readDocument(tx, ZonedDateTime.parse("2020-03-01T00:00:00Z"), "ns", "Person", "john").blockingGet()).isNull();

            persistence.deleteDocument(tx, "ns", "Person", "john", ZonedDateTime.parse("2020-02-01T00:00:00Z"), POLICY).blockingAwait();
            assertThat(persistence.readDocument(tx, ZonedDateTime.parse("2020-03-01T00:00:00Z"), "ns", "Person", "john").blockingGet()).isNotNull();
            persistence.deleteAllDocumentVersions(tx, "ns", "Person", "john", POLICY).blockingAwait();
            assertThat(persistence.readDocumentVersions(tx, "ns", "Person", "john", Range.unbounded()).toList().blockingGet()).isEmpty();
        }
    }

    @Test
    void rangesAndLinksKeepTheirPre3Meaning() {
        ZonedDateTime at = ZonedDateTime.parse("2020-01-01T00:00:00Z");
        write(person("a", "2020-01-01T00:00:00Z", "A", "/Person/b", "/Person/c"), person("b", "2020-01-01T00:00:00Z", "B", "/Person/c"),
                person("c", "2020-01-01T00:00:00Z", "C"), person("d", "2020-01-01T00:00:00Z", "C"));
        JsonNavigationPath addresses = JsonNavigationPath.from("$.addresses[]");

        try (Transaction tx = persistence.createTransaction(false)) {
            assertThat(ids(persistence.readDocuments(tx, at, "ns", "Person", Range.unbounded()).toList().blockingGet())).containsExactly("a", "b", "c", "d");
            assertThat(ids(persistence.readDocuments(tx, at, "ns", "Person", Range.firstAfter(2, "a")).toList().blockingGet())).containsExactly("b", "c");
            assertThat(ids(persistence.readDocuments(tx, at, "ns", "Person", Range.last(2)).toList().blockingGet())).containsExactly("d", "c");
            // an upper bound without a lower one reads backward, as before 3.0
            assertThat(ids(persistence.readDocuments(tx, at, "ns", "Person", Range.between(null, "c")).toList().blockingGet())).containsExactly("b", "a");
            assertThat(ids(persistence.findDocument(tx, at, "ns", "Person", JsonNavigationPath.from("$.firstname"), "C", Range.unbounded()).toList().blockingGet())).containsExactly("c", "d");
            assertThat(ids(persistence.readTargetDocuments(tx, at, "ns", "Person", "a", addresses, "Person", Range.unbounded()).toList().blockingGet())).containsExactly("b", "c");
            assertThat(ids(persistence.readSourceDocuments(tx, at, "ns", "Person", "c", addresses, "Person", Range.unbounded()).toList().blockingGet())).containsExactly("a", "b");

            persistence.deleteAllEntities(tx, "ns", "Person", null).blockingAwait();
            assertThat(persistence.readDocuments(tx, at, "ns", "Person", Range.unbounded()).toList().blockingGet()).isEmpty();
        }
        persistence.close();
    }

    private static List<String> ids(List<JsonDocument> documents) {
        return documents.stream().map(document -> document.key().id()).toList();
    }
}
