package io.descoped.lds.persistence.tck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.api.specification.SpecificationElementType;
import io.descoped.lds.core.persistence.test.SpecificationBuilder;
import io.descoped.lds.persistence.api.Document;
import io.descoped.lds.persistence.api.DocumentKey;
import io.descoped.lds.persistence.api.Identifiers;
import io.descoped.lds.persistence.api.Persistence;
import io.descoped.lds.persistence.api.Range;
import io.descoped.lds.persistence.api.Transaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import static io.descoped.lds.core.persistence.test.SpecificationBuilder.arrayRefNode;
import static io.descoped.lds.core.persistence.test.SpecificationBuilder.booleanNode;
import static io.descoped.lds.core.persistence.test.SpecificationBuilder.numericNode;
import static io.descoped.lds.core.persistence.test.SpecificationBuilder.objectNode;
import static io.descoped.lds.core.persistence.test.SpecificationBuilder.refNode;
import static io.descoped.lds.core.persistence.test.SpecificationBuilder.stringNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The persistence contract as tests. Each test works in a namespace of its own, so tests do not see each other's
 * documents and nothing has to be cleaned up in between.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class PersistenceTck {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger NAMESPACES = new AtomicInteger();
    private static final String RUN = Long.toString(System.currentTimeMillis(), 36);

    /** A point in time the tests count from. */
    protected static final Instant T0 = Instant.parse("2020-01-01T00:00:00Z");

    /** The persistence under test. */
    protected Persistence persistence;

    /** The namespace of the running test. */
    protected String namespace;

    /**
     * Creates the test class.
     */
    protected PersistenceTck() {
    }

    /**
     * Opens the provider under test against its engine.
     *
     * @param specification the model the tests use: {@code Person}, {@code Address} and {@code Office}
     * @return the persistence; the test class closes it when the tests are done
     */
    protected abstract Persistence open(Specification specification);

    @BeforeAll
    void openPersistence() {
        persistence = open(specification());
    }

    @AfterAll
    void closePersistence() {
        if (persistence != null) {
            persistence.close();
        }
    }

    @BeforeEach
    void newNamespace() {
        namespace = "tck_" + RUN + "_" + NAMESPACES.incrementAndGet();
    }

    private static Specification specification() {
        return SpecificationBuilder.createSpecificationAndRoot(Set.of(
                objectNode(SpecificationElementType.MANAGED, "Person", Set.of(
                        stringNode("firstname"),
                        stringNode("lastname"),
                        numericNode("born"),
                        booleanNode("isHuman"),
                        objectNode("history", Set.of(
                                refNode("currentAddress", Set.of("Address", "Office")),
                                refNode("workAddress", Set.of("Address", "Office")),
                                arrayRefNode("previousAddresses", Set.of("Address", "Office"), stringNode("[]"))
                        ))
                )),
                objectNode(SpecificationElementType.MANAGED, "Address", Set.of(
                        stringNode("city"), stringNode("country"))),
                objectNode(SpecificationElementType.MANAGED, "Office", Set.of(
                        stringNode("city"), stringNode("country")))
        ));
    }

    // ---- what a version is

    @Test
    void readReturnsWhatWasWritten() {
        Document written = person("john", T0, "John", "Smith");
        commit(tx -> persistence.write(tx, List.of(written)));

        Document read = read(T0, "Person", "john").orElseThrow();

        assertThat(read.key()).isEqualTo(written.key());
        assertSameJson(read.json(), written.json());
        assertThat(read.deleted()).isFalse();
        assertThat(read("Person", "jane")).isEmpty();
        assertThat(read("Address", "john")).isEmpty();
    }

    @Test
    void readSeesTheNewestVersionNotAfterTheSnapshot() {
        Instant first = T0;
        Instant second = T0.plus(400, ChronoUnit.DAYS);
        Instant third = T0.plus(800, ChronoUnit.DAYS);
        // written out of order on purpose
        commit(tx -> persistence.write(tx, List.of(person("john", third, "Third", "Smith"))));
        commit(tx -> persistence.write(tx, List.of(person("john", first, "First", "Smith"),
                person("john", second, "Second", "Smith"))));

        assertThat(read(first.minusNanos(1000), "Person", "john")).isEmpty();
        assertThat(firstname(read(first, "Person", "john"))).isEqualTo("First");
        assertThat(firstname(read(second.minusNanos(1000), "Person", "john"))).isEqualTo("First");
        assertThat(firstname(read(second, "Person", "john"))).isEqualTo("Second");
        assertThat(firstname(read(third.plusSeconds(1), "Person", "john"))).isEqualTo("Third");
        assertThat(read(second.plusSeconds(1), "Person", "john").orElseThrow().key().version()).isEqualTo(second);
    }

    @Test
    void writingAVersionAgainReplacesIt() {
        commit(tx -> persistence.write(tx, List.of(person("john", T0, "Jimmy", "Smith"))));
        commit(tx -> persistence.write(tx, List.of(person("john", T0, "John", "Smith"))));

        assertThat(versions("Person", "john", Range.unbounded())).hasSize(1);
        assertThat(firstname(read("Person", "john"))).isEqualTo("John");
    }

    @Test
    void versionsAreInstantsCutToMicroseconds() {
        Instant precise = T0.plusNanos(123_456_789);
        Instant kept = T0.plusNanos(123_456_000);
        commit(tx -> persistence.write(tx, List.of(person("john", precise, "John", "Smith"))));

        assertThat(read(kept, "Person", "john").orElseThrow().key().version()).isEqualTo(kept);
        assertThat(read(kept.minusNanos(1000), "Person", "john")).isEmpty();

        commit(tx -> persistence.write(tx, List.of(person("john", T0.plusNanos(123_456_999), "Johnny", "Smith"))));
        assertThat(versions("Person", "john", Range.unbounded())).hasSize(1);
        assertThat(firstname(read("Person", "john"))).isEqualTo("Johnny");
    }

    @Test
    void versionsFarFromTodayWork() {
        Instant y1624 = Instant.parse("1624-01-01T12:00:00Z");
        Instant y1664 = Instant.parse("1664-01-01T12:00:00Z");
        Instant y9000 = Instant.parse("9000-06-01T00:00:00.000001Z");
        commit(tx -> persistence.write(tx, List.of(address("newyork", y1624, "New Amsterdam"),
                address("newyork", y1664, "New York"), address("newyork", y9000, "Newer York"))));

        assertThat(read(Instant.parse("1623-12-31T00:00:00Z"), "Address", "newyork")).isEmpty();
        assertThat(city(read(Instant.parse("1650-01-01T00:00:00Z"), "Address", "newyork"))).isEqualTo("New Amsterdam");
        assertThat(city(read(T0, "Address", "newyork"))).isEqualTo("New York");
        assertThat(city(read(y9000, "Address", "newyork"))).isEqualTo("Newer York");
        assertThat(versions("Address", "newyork", Range.unbounded())).extracting(d -> d.key().version())
                .containsExactly(y1624, y1664, y9000);
    }

    @Test
    void versionsListsInTimeOrderAndHonoursTheRange() {
        List<Instant> months = IntStream.range(0, 12).mapToObj(i -> T0.plus(31L * i, ChronoUnit.DAYS)).toList();
        commit(tx -> persistence.write(tx, months.reversed().stream()
                .map(month -> person("john", month, "John " + month, "Smith")).toList()));
        Function<Range<Instant>, List<Instant>> listed = range -> versions("Person", "john", range).stream()
                .map(document -> document.key().version()).toList();

        assertThat(listed.apply(Range.unbounded())).isEqualTo(months);
        assertThat(listed.apply(Range.first(3))).isEqualTo(months.subList(0, 3));
        assertThat(listed.apply(Range.firstAfter(3, months.get(2)))).isEqualTo(months.subList(3, 6));
        assertThat(listed.apply(Range.firstBetween(2, months.get(5), months.get(9)))).isEqualTo(months.subList(6, 8));
        assertThat(listed.apply(Range.firstBetween(9, months.get(5), months.get(9)))).isEqualTo(months.subList(6, 9));
        assertThat(listed.apply(Range.between(months.get(5), months.get(7)))).containsExactly(months.get(6));
        assertThat(listed.apply(Range.last(3))).isEqualTo(months.subList(9, 12).reversed());
        assertThat(listed.apply(Range.lastBefore(3, months.get(9)))).isEqualTo(months.subList(6, 9).reversed());
        assertThat(listed.apply(Range.lastBetween(2, months.get(5), months.get(9)))).isEqualTo(months.subList(7, 9).reversed());
        assertThat(listed.apply(Range.lastBetween(9, months.get(5), months.get(9)))).isEqualTo(months.subList(6, 9).reversed());
        assertThat(listed.apply(Range.first(0))).isEmpty();
        assertThat(versions("Person", "jane", Range.unbounded())).isEmpty();
    }

    // ---- deleting

    @Test
    void aDeleteMarkerHidesTheDocumentFromItsInstantOn() {
        Instant created = T0;
        Instant deleted = T0.plus(10, ChronoUnit.DAYS);
        Instant recreated = T0.plus(20, ChronoUnit.DAYS);
        commit(tx -> persistence.write(tx, List.of(person("john", created, "John", "Smith"),
                person("jane", created, "Jane", "Smith"))));
        commit(tx -> persistence.markDeleted(tx, new DocumentKey(namespace, "Person", "john", deleted)));

        assertThat(firstname(read(deleted.minusSeconds(1), "Person", "john"))).isEqualTo("John");
        assertThat(read(deleted, "Person", "john")).isEmpty();
        assertThat(read(deleted.plusSeconds(1), "Person", "john")).isEmpty();
        assertThat(ids(list(deleted, "Person", Range.unbounded()))).containsExactly("jane");
        assertThat(ids(list(deleted.minusSeconds(1), "Person", Range.unbounded()))).containsExactly("jane", "john");
        assertThat(ids(find(deleted, "Person", "$.lastname", "Smith", Range.unbounded()))).containsExactly("jane");
        assertThat((boolean) inTransaction(tx -> persistence.hasNext(tx, deleted, namespace, "Person", "jane"))).isFalse();

        List<Document> versions = versions("Person", "john", Range.unbounded());
        assertThat(versions).extracting(Document::deleted).containsExactly(false, true);
        assertThat(versions.get(1).key().version()).isEqualTo(deleted);
        assertThat(versions.get(1).json()).isNull();

        commit(tx -> persistence.write(tx, List.of(person("john", recreated, "John II", "Smith"))));
        assertThat(read(recreated.minusSeconds(1), "Person", "john")).isEmpty();
        assertThat(firstname(read(recreated, "Person", "john"))).isEqualTo("John II");
        assertThat(versions("Person", "john", Range.unbounded())).extracting(Document::deleted)
                .containsExactly(false, true, false);
    }

    @Test
    void aDeleteMarkerAndContentReplaceEachOtherAtTheSameInstant() {
        DocumentKey key = new DocumentKey(namespace, "Person", "john", T0);
        commit(tx -> persistence.write(tx, List.of(person("john", T0, "John", "Smith"))));
        commit(tx -> persistence.markDeleted(tx, key));
        assertThat(read("Person", "john")).isEmpty();
        assertThat(versions("Person", "john", Range.unbounded())).extracting(Document::deleted).containsExactly(true);
        assertThat(find(T0, "Person", "$.firstname", "John", Range.unbounded())).isEmpty();

        commit(tx -> persistence.write(tx, List.of(person("john", T0, "Johnny", "Smith"))));
        assertThat(firstname(read("Person", "john"))).isEqualTo("Johnny");
        assertThat(versions("Person", "john", Range.unbounded())).extracting(Document::deleted).containsExactly(false);
    }

    @Test
    void deleteVersionRemovesOneVersion() {
        Instant later = T0.plusSeconds(60);
        Instant marker = T0.plusSeconds(120);
        commit(tx -> {
            persistence.write(tx, List.of(person("john", T0, "John", "Smith"), person("john", later, "Johnny", "Smith")));
            persistence.markDeleted(tx, new DocumentKey(namespace, "Person", "john", marker));
        });

        commit(tx -> persistence.deleteVersion(tx, new DocumentKey(namespace, "Person", "john", marker)));
        assertThat(firstname(read("Person", "john"))).isEqualTo("Johnny");

        commit(tx -> persistence.deleteVersion(tx, new DocumentKey(namespace, "Person", "john", later)));
        assertThat(firstname(read("Person", "john"))).isEqualTo("John");
        assertThat(find(marker, "Person", "$.firstname", "Johnny", Range.unbounded())).isEmpty();

        commit(tx -> persistence.deleteVersion(tx, new DocumentKey(namespace, "Person", "john", later)));
        commit(tx -> persistence.deleteVersion(tx, new DocumentKey(namespace, "Person", "nobody", later)));
        assertThat(versions("Person", "john", Range.unbounded())).hasSize(1);
    }

    @Test
    void deleteAllVersionsRemovesThatDocumentOnly() {
        commit(tx -> persistence.write(tx, List.of(person("john", T0, "John", "Smith"),
                person("john", T0.plusSeconds(1), "Johnny", "Smith"), person("johnny", T0, "Other", "Smith"),
                person("joh", T0, "Other", "Smith"), address("john", T0, "Oslo"))));

        commit(tx -> persistence.deleteAllVersions(tx, namespace, "Person", "john"));

        assertThat(versions("Person", "john", Range.unbounded())).isEmpty();
        assertThat(ids(list(T0.plusSeconds(9), "Person", Range.unbounded()))).containsExactly("joh", "johnny");
        assertThat(ids(find(T0.plusSeconds(9), "Person", "$.lastname", "Smith", Range.unbounded())))
                .containsExactly("joh", "johnny");
        assertThat(read("Address", "john")).isPresent();
        commit(tx -> persistence.deleteAllVersions(tx, namespace, "Person", "john"));
    }

    @Test
    void deleteEntityRemovesThatEntityInThatNamespaceOnly() {
        String other = namespace + "_other";
        commit(tx -> persistence.write(tx, List.of(person("john", T0, "John", "Smith"),
                person("jane", T0, "Jane", "Smith"), address("oslo", T0, "Oslo"),
                new Document(new DocumentKey(other, "Person", "john", T0), person("john", T0, "John", "Smith").json()))));

        commit(tx -> persistence.deleteEntity(tx, namespace, "Person"));

        assertThat(list(T0, "Person", Range.unbounded())).isEmpty();
        assertThat(find(T0, "Person", "$.lastname", "Smith", Range.unbounded())).isEmpty();
        assertThat(versions("Person", "john", Range.unbounded())).isEmpty();
        assertThat(read("Address", "oslo")).isPresent();
        Optional<Document> otherJohn = inTransaction(tx -> persistence.read(tx, T0, other, "Person", "john"));
        assertThat(otherJohn).isPresent();
        commit(tx -> persistence.deleteEntity(tx, namespace, "Person"));
    }

    // ---- listing

    @Test
    void listHonoursTheRange() {
        List<String> all = IntStream.range(0, 12).mapToObj(i -> String.format("person%02d", i)).toList();
        commit(tx -> persistence.write(tx, all.reversed().stream().map(id -> person(id, T0, id, "Smith")).toList()));
        Function<Range<String>, List<String>> listed = range -> ids(list(T0, "Person", range));

        assertThat(listed.apply(Range.unbounded())).isEqualTo(all);
        assertThat(listed.apply(Range.first(3))).isEqualTo(all.subList(0, 3));
        assertThat(listed.apply(Range.firstAfter(3, "person03"))).isEqualTo(all.subList(4, 7));
        assertThat(listed.apply(Range.firstAfter(3, "person033"))).isEqualTo(all.subList(4, 7));
        assertThat(listed.apply(Range.firstBetween(2, "person06", "person10"))).isEqualTo(all.subList(7, 9));
        assertThat(listed.apply(Range.firstBetween(9, "person06", "person10"))).isEqualTo(all.subList(7, 10));
        assertThat(listed.apply(Range.between("person06", "person08"))).containsExactly("person07");
        assertThat(listed.apply(Range.between("person07", "person07"))).isEmpty();
        assertThat(listed.apply(Range.between("person09", "person03"))).isEmpty();
        assertThat(listed.apply(Range.last(3))).isEqualTo(all.subList(9, 12).reversed());
        assertThat(listed.apply(Range.lastBefore(3, "person10"))).isEqualTo(all.subList(7, 10).reversed());
        assertThat(listed.apply(Range.lastBetween(2, "person06", "person10"))).isEqualTo(all.subList(8, 10).reversed());
        assertThat(listed.apply(Range.lastBetween(9, "person06", "person10"))).isEqualTo(all.subList(7, 10).reversed());
        assertThat(listed.apply(Range.firstAfter(3, "person11"))).isEmpty();
        assertThat(listed.apply(Range.lastBefore(3, "person00"))).isEmpty();
        assertThat(listed.apply(Range.first(0))).isEmpty();
        assertThat(list(T0, "Address", Range.unbounded())).isEmpty();
    }

    @Test
    void listPagesThroughManyDocumentsWithManyVersions() {
        List<String> all = IntStream.range(0, 250).mapToObj(i -> String.format("p%04d", i * 7)).toList();
        for (int version = 0; version < 3; version++) {
            Instant instant = T0.plusSeconds(version);
            String name = "v" + version;
            commit(tx -> persistence.write(tx, all.stream().map(id -> person(id, instant, name, "Smith")).toList()));
        }
        Instant snapshot = T0.plusSeconds(1);

        List<String> forward = new ArrayList<>();
        List<Document> page = list(snapshot, "Person", Range.first(40));
        while (!page.isEmpty()) {
            assertThat(page).allSatisfy(document -> assertThat(firstname(Optional.of(document))).isEqualTo("v1"));
            forward.addAll(ids(page));
            page = list(snapshot, "Person", Range.firstAfter(40, forward.getLast()));
        }
        assertThat(forward).isEqualTo(all);

        List<String> backward = new ArrayList<>();
        page = list(snapshot, "Person", Range.last(40));
        while (!page.isEmpty()) {
            assertThat(page).allSatisfy(document -> assertThat(firstname(Optional.of(document))).isEqualTo("v1"));
            backward.addAll(ids(page));
            page = list(snapshot, "Person", Range.lastBefore(40, backward.getLast()));
        }
        assertThat(backward).isEqualTo(all.reversed());
        assertThat(list(T0.minusSeconds(1), "Person", Range.unbounded())).isEmpty();
    }

    @Test
    void idsOfAnyScriptAreListedInCodePointOrder() {
        // U+FF5E sorts before U+1F600 by code point, but after it by UTF-16 code unit.
        List<String> ids = new ArrayList<>(List.of("abc", "Zed", "~tilde", "Ærlig", "ÅÅÅ", "日本", "10", "9", "a b",
                "a", "ab", "\uFF5Ewide", "\uD83D\uDE00smile", "zzz", " leading", "é", "e\u0301"));
        commit(tx -> persistence.write(tx, ids.stream().map(id -> person(id, T0, id, "Smith")).toList()));
        ids.sort(Identifiers.ID_ORDER);

        assertThat(ids.indexOf("\uFF5Ewide")).isLessThan(ids.indexOf("\uD83D\uDE00smile"));
        assertThat(ids(list(T0, "Person", Range.unbounded()))).isEqualTo(ids);
        assertThat(ids(list(T0, "Person", Range.last(Range.UNLIMITED)))).isEqualTo(ids.reversed());
        assertThat(ids(find(T0, "Person", "$.lastname", "Smith", Range.unbounded()))).isEqualTo(ids);
        for (String id : ids) {
            assertThat(firstname(read("Person", id))).isEqualTo(id);
            int index = ids.indexOf(id);
            assertThat(ids(list(T0, "Person", Range.firstAfter(1, id))))
                    .isEqualTo(index + 1 < ids.size() ? List.of(ids.get(index + 1)) : List.of());
            assertThat((boolean) inTransaction(tx -> persistence.hasNext(tx, T0, namespace, "Person", id)))
                    .isEqualTo(index + 1 < ids.size());
            assertThat((boolean) inTransaction(tx -> persistence.hasPrevious(tx, T0, namespace, "Person", id)))
                    .isEqualTo(index > 0);
        }
    }

    @Test
    void idsWithQuotesAndOperatorsAreJustIds() {
        List<String> ids = List.of("o'brien", "a\"b", "x; DROP TABLE document; --", "$(rm -rf)", "{a}", "%", "*:*",
                "back\\slash", "' OR '1'='1", "a`b", "MATCH (n) DETACH DELETE n", "..", "a?b#c&d=e", "<script>");
        commit(tx -> persistence.write(tx, ids.stream().map(id -> person(id, T0, id, id)).toList()));

        assertThat(list(T0, "Person", Range.unbounded())).hasSize(ids.size());
        for (String id : ids) {
            assertThat(firstname(read("Person", id))).isEqualTo(id);
            assertThat(ids(find(T0, "Person", "$.lastname", id, Range.unbounded()))).containsExactly(id);
            assertThat(versions("Person", id, Range.unbounded())).hasSize(1);
        }
        commit(tx -> persistence.deleteAllVersions(tx, namespace, "Person", "' OR '1'='1"));
        assertThat(list(T0, "Person", Range.unbounded())).hasSize(ids.size() - 1);
    }

    @Test
    void hasNextAndHasPreviousFollowTheList() {
        commit(tx -> persistence.write(tx, List.of(person("person01", T0, "One", "Smith"))));
        assertThat((boolean) inTransaction(tx -> persistence.hasNext(tx, T0, namespace, "Person", "person01"))).isFalse();
        assertThat((boolean) inTransaction(tx -> persistence.hasPrevious(tx, T0, namespace, "Person", "person01"))).isFalse();

        commit(tx -> persistence.write(tx, List.of(person("person00", T0, "Zero", "Smith"),
                person("person02", T0.plusSeconds(60), "Two", "Smith"))));
        assertThat((boolean) inTransaction(tx -> persistence.hasPrevious(tx, T0, namespace, "Person", "person01"))).isTrue();
        assertThat((boolean) inTransaction(tx -> persistence.hasNext(tx, T0, namespace, "Person", "person01"))).isFalse();
        assertThat((boolean) inTransaction(tx -> persistence.hasNext(tx, T0.plusSeconds(60), namespace, "Person", "person01"))).isTrue();
    }

    // ---- finding

    @Test
    void findMatchesAStringAtAPath() {
        ObjectNode nested = JSON.createObjectNode().put("firstname", "Nested");
        nested.putArray("jobs").addObject().put("title", "Smith").putArray("tags").add("x").add("Smith");
        nested.putArray("matrix").addArray().add("Smith");
        commit(tx -> persistence.write(tx, List.of(person("john", T0, "John", "Smith"),
                person("jane", T0, "Jane", "Doe"), person("smith", T0, "Smith", "Jones"),
                document("Person", "nested", T0, nested), address("smithtown", T0, "Smith"))));

        assertThat(ids(find(T0, "Person", "$.lastname", "Smith", Range.unbounded()))).containsExactly("john");
        assertThat(ids(find(T0, "Person", "$.firstname", "Smith", Range.unbounded()))).containsExactly("smith");
        assertThat(ids(find(T0, "Person", "$.jobs[].title", "Smith", Range.unbounded()))).containsExactly("nested");
        assertThat(ids(find(T0, "Person", "$.jobs[0].title", "Smith", Range.unbounded()))).containsExactly("nested");
        assertThat(find(T0, "Person", "$.jobs[1].title", "Smith", Range.unbounded())).isEmpty();
        assertThat(ids(find(T0, "Person", "$.jobs[].tags[]", "Smith", Range.unbounded()))).containsExactly("nested");
        assertThat(ids(find(T0, "Person", "$.matrix[][]", "Smith", Range.unbounded()))).containsExactly("nested");
        assertThat(find(T0, "Person", "$.lastname", "smith", Range.unbounded())).isEmpty();
        assertThat(find(T0, "Person", "$.lastname", "Smit", Range.unbounded())).isEmpty();
        assertThat(find(T0, "Person", "$.lastname", "", Range.unbounded())).isEmpty();
        assertThat(find(T0, "Person", "$.history.lastname", "Smith", Range.unbounded())).isEmpty();
        assertThat(find(T0, "Person", "$.born", "1998", Range.unbounded())).isEmpty();
        assertSameJson(find(T0, "Person", "$.lastname", "Smith", Range.unbounded()).getFirst().json(),
                person("john", T0, "John", "Smith").json());
    }

    @Test
    void findLooksAtTheVersionTheSnapshotSees() {
        Instant married = T0.plus(100, ChronoUnit.DAYS);
        commit(tx -> persistence.write(tx, List.of(person("jane", T0, "Jane", "Doe"),
                person("jane", married, "Jane", "Smith"), person("john", T0, "John", "Smith"),
                person("jack", married, "Jack", "Smith"))));

        assertThat(ids(find(T0, "Person", "$.lastname", "Smith", Range.unbounded()))).containsExactly("john");
        assertThat(ids(find(T0, "Person", "$.lastname", "Doe", Range.unbounded()))).containsExactly("jane");
        assertThat(ids(find(married, "Person", "$.lastname", "Smith", Range.unbounded())))
                .containsExactly("jack", "jane", "john");
        assertThat(find(married, "Person", "$.lastname", "Doe", Range.unbounded())).isEmpty();
        assertThat(find(married, "Person", "$.lastname", "Smith", Range.unbounded()).get(1).key().version())
                .isEqualTo(married);
    }

    @Test
    void findForgetsTheValuesOfAReplacedVersion() {
        commit(tx -> persistence.write(tx, List.of(person("john", T0, "John", "Smith"))));
        commit(tx -> persistence.write(tx, List.of(person("john", T0, "John", "Jones"))));

        assertThat(find(T0, "Person", "$.lastname", "Smith", Range.unbounded())).isEmpty();
        assertThat(ids(find(T0, "Person", "$.lastname", "Jones", Range.unbounded()))).containsExactly("john");
    }

    @Test
    void findHonoursTheRange() {
        List<String> all = IntStream.range(0, 12).mapToObj(i -> String.format("person%02d", i)).toList();
        commit(tx -> persistence.write(tx, all.stream()
                .map(id -> person(id, T0, id, id.endsWith("5") ? "Other" : "Smith")).toList()));
        List<String> smiths = all.stream().filter(id -> !id.endsWith("5")).toList();
        Function<Range<String>, List<String>> found = range -> ids(find(T0, "Person", "$.lastname", "Smith", range));

        assertThat(found.apply(Range.unbounded())).isEqualTo(smiths);
        assertThat(found.apply(Range.first(3))).isEqualTo(smiths.subList(0, 3));
        assertThat(found.apply(Range.firstAfter(3, "person03"))).containsExactly("person04", "person06", "person07");
        assertThat(found.apply(Range.lastBefore(2, "person06"))).containsExactly("person04", "person03");
        assertThat(found.apply(Range.last(2))).containsExactly("person11", "person10");
        assertThat(found.apply(Range.between("person04", "person07"))).containsExactly("person06");
    }

    @Test
    void findMatchesALongValueExactly() {
        String big = "12345678901234567890_".repeat(4096);
        commit(tx -> persistence.write(tx, List.of(address("big", T0, big), address("almost", T0, big + "1"),
                address("euro", T0, "€".repeat(3000)))));

        assertThat(ids(find(T0, "Address", "$.city", big, Range.unbounded()))).containsExactly("big");
        assertThat(ids(find(T0, "Address", "$.city", big + "1", Range.unbounded()))).containsExactly("almost");
        assertThat(find(T0, "Address", "$.city", big + "2", Range.unbounded())).isEmpty();
        assertThat(ids(find(T0, "Address", "$.city", "€".repeat(3000), Range.unbounded()))).containsExactly("euro");
        assertThat(city(read("Address", "big"))).isEqualTo(big);
    }

    // ---- links

    @Test
    void targetsFollowTheLinksAtAPathToOneEntity() {
        commit(tx -> persistence.write(tx, List.of(address("oslo", T0, "Oslo"), address("paris", T0, "Paris"),
                address("london", T0, "London"), office("hq", T0, "Trondheim"),
                person("jack", T0, "Jack", "Smith", "/Address/oslo", "/Office/hq",
                        List.of("/Address/paris", "/Address/london", "/Address/paris", "/Office/hq", "/Address/gone",
                                "not a link", "/Address/a/b")),
                person("jill", T0, "Jill", "Smith", "/Address/paris", "/Address/paris", List.of()))));

        assertThat(ids(targets(T0, "jack", "$.history.currentAddress", "Address", Range.unbounded()))).containsExactly("oslo");
        assertThat(targets(T0, "jack", "$.history.currentAddress", "Office", Range.unbounded())).isEmpty();
        assertThat(ids(targets(T0, "jack", "$.history.workAddress", "Office", Range.unbounded()))).containsExactly("hq");
        assertThat(ids(targets(T0, "jack", "$.history.previousAddresses[]", "Address", Range.unbounded())))
                .containsExactly("london", "paris");
        assertThat(ids(targets(T0, "jack", "$.history.previousAddresses[]", "Office", Range.unbounded()))).containsExactly("hq");
        assertThat(ids(targets(T0, "jack", "$.history.previousAddresses[0]", "Address", Range.unbounded()))).containsExactly("paris");
        assertThat(ids(targets(T0, "jack", "$.history.previousAddresses[]", "Address", Range.first(1)))).containsExactly("london");
        assertThat(ids(targets(T0, "jack", "$.history.previousAddresses[]", "Address", Range.firstAfter(5, "london")))).containsExactly("paris");
        assertThat(ids(targets(T0, "jack", "$.history.previousAddresses[]", "Address", Range.last(1)))).containsExactly("paris");
        assertThat(targets(T0, "jill", "$.history.previousAddresses[]", "Address", Range.unbounded())).isEmpty();
        assertThat(targets(T0, "nobody", "$.history.currentAddress", "Address", Range.unbounded())).isEmpty();
        assertThat(targets(T0, "jack", "$.firstname", "Address", Range.unbounded())).isEmpty();
        assertThat(city(targets(T0, "jack", "$.history.currentAddress", "Address", Range.unbounded()).stream().findFirst()))
                .isEqualTo("Oslo");
    }

    @Test
    void targetsAndSourcesAreReadAtTheSnapshot() {
        Instant moved = T0.plus(30, ChronoUnit.DAYS);
        commit(tx -> {
            persistence.write(tx, List.of(address("oslo", T0, "Christiania"), address("oslo", moved, "Oslo"),
                    address("paris", moved, "Paris"),
                    person("jack", T0, "Jack", "Smith", "/Address/oslo", "/Address/oslo", List.of()),
                    person("jack", moved, "Jack", "Smith", "/Address/paris", "/Address/oslo", List.of())));
        });

        assertThat(city(targets(T0, "jack", "$.history.currentAddress", "Address", Range.unbounded()).stream().findFirst()))
                .isEqualTo("Christiania");
        assertThat(ids(targets(moved, "jack", "$.history.currentAddress", "Address", Range.unbounded()))).containsExactly("paris");
        assertThat(ids(sources(T0, "Address", "oslo", "$.history.currentAddress", Range.unbounded()))).containsExactly("jack");
        assertThat(sources(moved, "Address", "oslo", "$.history.currentAddress", Range.unbounded())).isEmpty();
        assertThat(ids(sources(moved, "Address", "paris", "$.history.currentAddress", Range.unbounded()))).containsExactly("jack");
        assertThat(sources(T0, "Address", "paris", "$.history.currentAddress", Range.unbounded())).isEmpty();
    }

    @Test
    void sourcesFindTheDocumentsThatLinkToATarget() {
        List<Document> documents = new ArrayList<>(List.of(address("oslo", T0, "Oslo"), office("oslo", T0, "Oslo")));
        for (int i = 0; i < 11; i++) {
            documents.add(person(String.format("person%02d", i), T0, "P" + i, "Smith",
                    i % 2 == 0 ? "/Address/oslo" : "/Office/oslo", "/Office/oslo",
                    i % 3 == 0 ? List.of("/Address/paris", "/Address/oslo") : List.of("/Address/paris")));
        }
        commit(tx -> persistence.write(tx, documents));

        assertThat(ids(sources(T0, "Address", "oslo", "$.history.currentAddress", Range.unbounded())))
                .containsExactly("person00", "person02", "person04", "person06", "person08", "person10");
        assertThat(ids(sources(T0, "Office", "oslo", "$.history.currentAddress", Range.unbounded())))
                .containsExactly("person01", "person03", "person05", "person07", "person09");
        assertThat(sources(T0, "Office", "oslo", "$.history.workAddress", Range.unbounded())).hasSize(11);
        assertThat(ids(sources(T0, "Address", "oslo", "$.history.previousAddresses[]", Range.unbounded())))
                .containsExactly("person00", "person03", "person06", "person09");
        assertThat(ids(sources(T0, "Address", "oslo", "$.history.previousAddresses[]", Range.firstAfter(2, "person00"))))
                .containsExactly("person03", "person06");
        assertThat(ids(sources(T0, "Address", "oslo", "$.history.previousAddresses[]", Range.last(1))))
                .containsExactly("person09");
        assertThat(sources(T0, "Address", "bergen", "$.history.currentAddress", Range.unbounded())).isEmpty();
    }

    // ---- JSON

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"m\":[[1,2],[3],[],[[\"deep\"]]]}",
            "{\"a.b\":\"dotted\",\"a\":{\"b\":\"nested\"}}",
            "{\"k[0]\":\"bracket\",\"k\":[\"array\"],\"[]\":1,\"$\":2,\"\":3}",
            "{\"n\":null,\"s\":\"\",\"o\":{},\"a\":[],\"t\":true,\"f\":false}",
            "{\"a\":[null,{},[],\"\",0,false,{\"b\":[{\"c\":null}]}]}",
            "{\"text\":\"æøå 日本 😀 \\\" \\\\ / \\n \\t ' ; -- $ {} % \\u0001\"}",
            "{\"l1\":{\"l2\":{\"l3\":{\"l4\":{\"l5\":{\"l6\":{\"l7\":[{\"l8\":[[{\"l9\":\"deep\"}]]}]}}}}}}}",
            "{\"firstname\":\"John\",\"unknownField\":{\"notInTheModel\":[1,\"two\"]}}",
            "{}"
    })
    void jsonComesBackAsItWentIn(String json) throws Exception {
        JsonNode content = JSON.readTree(json);
        commit(tx -> persistence.write(tx, List.of(document("Person", "doc", T0, content))));

        assertSameJson(read("Person", "doc").orElseThrow().json(), content);
        assertSameJson(list(T0, "Person", Range.unbounded()).getFirst().json(), content);
        assertSameJson(versions("Person", "doc", Range.unbounded()).getFirst().json(), content);
    }

    @Test
    void numbersKeepTheirValue() {
        ObjectNode content = JsonNodeFactory.withExactBigDecimals(true).objectNode();
        content.put("int", 1998).put("negative", -42).put("zero", 0).put("long", 9_007_199_254_740_993L)
                .put("min", Long.MIN_VALUE).put("double", 3.82).put("small", 1.0e-7)
                .put("big", new BigInteger("12345678901234567890"))
                .put("precise", new BigDecimal("0.12345678901234567890"))
                .put("money", new BigDecimal("1000000000000000000000.01"));
        content.putArray("list").add(1).add(2.5).add(new BigInteger("-98765432109876543210"));
        commit(tx -> persistence.write(tx, List.of(document("Person", "numbers", T0, content))));

        JsonNode read = read("Person", "numbers").orElseThrow().json();

        assertSameJson(read, content);
        assertThat(read.get("big").bigIntegerValue()).isEqualTo(new BigInteger("12345678901234567890"));
        assertThat(read.get("long").longValue()).isEqualTo(9_007_199_254_740_993L);
        assertThat(read.get("precise").decimalValue()).isEqualByComparingTo("0.12345678901234567890");
    }

    @Test
    void whatIsReadAndWhatWasWrittenAreCopies() {
        ObjectNode content = (ObjectNode) person("john", T0, "John", "Smith").json();
        commit(tx -> persistence.write(tx, List.of(document("Person", "john", T0, content))));
        content.put("firstname", "Changed after the write");

        Document read = read("Person", "john").orElseThrow();
        assertThat(firstname(Optional.of(read))).isEqualTo("John");
        ((ObjectNode) read.json()).put("firstname", "Changed after the read");

        assertThat(firstname(read("Person", "john"))).isEqualTo("John");
        assertThat(ids(find(T0, "Person", "$.firstname", "John", Range.unbounded()))).containsExactly("john");
    }

    // ---- transactions

    @Test
    void workThatIsNotCommittedIsRolledBackWhenTheTransactionIsClosed() {
        commit(tx -> persistence.write(tx, List.of(person("kept", T0, "Kept", "Smith"))));

        try (Transaction tx = persistence.begin(false)) {
            persistence.write(tx, List.of(person("john", T0, "John", "Smith")));
            persistence.markDeleted(tx, new DocumentKey(namespace, "Person", "kept", T0.plusSeconds(1)));
            persistence.deleteAllVersions(tx, namespace, "Person", "kept");
        }

        assertThat(read("Person", "john")).isEmpty();
        assertThat(firstname(read(T0.plusSeconds(5), "Person", "kept"))).isEqualTo("Kept");
        assertThat(versions("Person", "kept", Range.unbounded())).hasSize(1);
        assertThat(find(T0, "Person", "$.firstname", "John", Range.unbounded())).isEmpty();
    }

    @Test
    void anExceptionInTheBlockRollsTheTransactionBack() {
        assertThatThrownBy(() -> {
            try (Transaction tx = persistence.begin(false)) {
                persistence.write(tx, List.of(person("john", T0, "John", "Smith")));
                throw new IllegalStateException("the second step of the unit of work failed");
            }
        }).hasMessageContaining("second step");

        assertThat(read("Person", "john")).isEmpty();
        assertThat(list(T0, "Person", Range.unbounded())).isEmpty();
    }

    @Test
    void aTransactionSeesItsOwnWritesAndOthersSeeThemAfterCommit() {
        try (Transaction writer = persistence.begin(false)) {
            persistence.write(writer, List.of(person("john", T0, "John", "Smith"), person("jane", T0, "Jane", "Smith")));
            persistence.deleteAllVersions(writer, namespace, "Person", "jane");

            assertThat(persistence.read(writer, T0, namespace, "Person", "john")).isPresent();
            assertThat(persistence.read(writer, T0, namespace, "Person", "jane")).isEmpty();
            assertThat(ids(persistence.list(writer, T0, namespace, "Person", Range.unbounded()))).containsExactly("john");
            assertThat(ids(persistence.find(writer, T0, namespace, "Person", JsonNavigationPath.from("$.lastname"),
                    "Smith", Range.unbounded()))).containsExactly("john");
            assertThat(persistence.versions(writer, namespace, "Person", "john", Range.unbounded())).hasSize(1);
            assertThat(read("Person", "john")).as("another transaction, before the commit").isEmpty();

            writer.commit();
        }
        assertThat(read("Person", "john")).as("another transaction, after the commit").isPresent();
        assertThat(read("Person", "jane")).isEmpty();
    }

    @Test
    void aTransactionCannotBeUsedAfterCommitOrClose() {
        Transaction committed = persistence.begin(false);
        persistence.write(committed, List.of(person("john", T0, "John", "Smith")));
        committed.commit();
        assertThatIllegalStateException().isThrownBy(committed::commit);
        assertThatIllegalStateException().isThrownBy(() -> persistence.read(committed, T0, namespace, "Person", "john"));
        assertThatIllegalStateException().isThrownBy(
                () -> persistence.write(committed, List.of(person("jane", T0, "Jane", "Smith"))));
        committed.close();
        committed.close();
        assertThat(read("Person", "john")).as("closing after the commit keeps the work").isPresent();

        Transaction closed = persistence.begin(false);
        persistence.write(closed, List.of(person("jane", T0, "Jane", "Smith")));
        closed.close();
        closed.close();
        assertThatIllegalStateException().isThrownBy(closed::commit);
        assertThatIllegalStateException().isThrownBy(() -> persistence.list(closed, T0, namespace, "Person", Range.unbounded()));
        assertThat(read("Person", "jane")).isEmpty();
    }

    @Test
    void aReadOnlyTransactionRejectsWrites() {
        commit(tx -> persistence.write(tx, List.of(person("john", T0, "John", "Smith"))));
        DocumentKey key = new DocumentKey(namespace, "Person", "john", T0);

        try (Transaction tx = persistence.begin(true)) {
            assertThatIllegalStateException().isThrownBy(() -> persistence.write(tx, List.of(person("jane", T0, "Jane", "Smith"))));
            assertThatIllegalStateException().isThrownBy(() -> persistence.markDeleted(tx, key));
            assertThatIllegalStateException().isThrownBy(() -> persistence.deleteVersion(tx, key));
            assertThatIllegalStateException().isThrownBy(() -> persistence.deleteAllVersions(tx, namespace, "Person", "john"));
            assertThatIllegalStateException().isThrownBy(() -> persistence.deleteEntity(tx, namespace, "Person"));
            assertThat(persistence.read(tx, T0, namespace, "Person", "john")).isPresent();
            tx.commit();
        }
        assertThat(ids(list(T0, "Person", Range.unbounded()))).containsExactly("john");
    }

    @Test
    void writersOfDifferentDocumentsAllCommit() throws Exception {
        int writers = 16;
        int each = 25;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (int writer = 0; writer < writers; writer++) {
                int number = writer;
                done.add(executor.submit(() -> {
                    for (int i = 0; i < each; i++) {
                        String id = String.format("w%02d-%02d", number, i);
                        commit(tx -> {
                            persistence.write(tx, List.of(person(id, T0, id, "Smith")));
                            assertThat(persistence.read(tx, T0, namespace, "Person", id)).isPresent();
                        });
                        assertThat(read("Person", id)).isPresent();
                    }
                }));
            }
            for (Future<?> future : done) {
                future.get();
            }
        }
        assertThat(list(T0, "Person", Range.unbounded())).hasSize(writers * each);
        assertThat(find(T0, "Person", "$.lastname", "Smith", Range.unbounded())).hasSize(writers * each);
    }

    // ---- namespaces and names

    @Test
    void namespacesDoNotSeeEachOther() {
        String other = namespace + "_other";
        commit(tx -> persistence.write(tx, List.of(person("john", T0, "John", "Smith"),
                new Document(new DocumentKey(other, "Person", "john", T0), person("x", T0, "Other John", "Smith").json()),
                new Document(new DocumentKey(other, "Person", "jane", T0), person("x", T0, "Jane", "Smith").json()))));

        assertThat(firstname(read("Person", "john"))).isEqualTo("John");
        assertThat(ids(list(T0, "Person", Range.unbounded()))).containsExactly("john");
        assertThat(ids(find(T0, "Person", "$.lastname", "Smith", Range.unbounded()))).containsExactly("john");
        List<String> otherIds = inTransaction(tx -> ids(persistence.list(tx, T0, other, "Person", Range.unbounded())));
        assertThat(otherIds).containsExactly("jane", "john");
        assertThat(firstname(inTransaction(tx -> persistence.read(tx, T0, other, "Person", "john")))).isEqualTo("Other John");

        commit(tx -> persistence.deleteAllVersions(tx, other, "Person", "john"));
        assertThat(read("Person", "john")).isPresent();
        commit(tx -> persistence.markDeleted(tx, new DocumentKey(namespace, "Person", "john", T0)));
        Optional<Document> otherJane = inTransaction(tx -> persistence.read(tx, T0, other, "Person", "jane"));
        assertThat(otherJane).isPresent();
    }

    @Test
    void namesThatAreNotValidAreRejected() {
        List<String> badNamespaces = Arrays.asList(null, "", "a/b", "a b", ".hidden", "-dash", "ns'", "ns\"", "ñ", "a\nb");
        List<String> badEntities = Arrays.asList(null, "", "Per son", "1Person", "Person/..", "Person-x", "Person.x", "Pérson", "P'");
        List<String> badIds = Arrays.asList(null, "", "a/b", "/", "a\nb", "a\u0000b", "tab\t", "\uD83D", "x".repeat(513));
        try (Transaction tx = persistence.begin(false)) {
            for (String bad : badNamespaces) {
                assertThatIllegalArgumentException().as("namespace %s", bad)
                        .isThrownBy(() -> persistence.read(tx, T0, bad, "Person", "john"));
                assertThatIllegalArgumentException().as("namespace %s", bad)
                        .isThrownBy(() -> persistence.list(tx, T0, bad, "Person", Range.unbounded()));
                assertThatIllegalArgumentException().as("namespace %s", bad)
                        .isThrownBy(() -> persistence.deleteEntity(tx, bad, "Person"));
                assertThatIllegalArgumentException().as("namespace %s", bad)
                        .isThrownBy(() -> new DocumentKey(bad, "Person", "john", T0));
            }
            for (String bad : badEntities) {
                assertThatIllegalArgumentException().as("entity %s", bad)
                        .isThrownBy(() -> persistence.read(tx, T0, namespace, bad, "john"));
                assertThatIllegalArgumentException().as("entity %s", bad)
                        .isThrownBy(() -> persistence.versions(tx, namespace, bad, "john", Range.unbounded()));
                assertThatIllegalArgumentException().as("entity %s", bad).isThrownBy(() -> persistence.find(tx, T0,
                        namespace, bad, JsonNavigationPath.from("$.lastname"), "Smith", Range.unbounded()));
                assertThatIllegalArgumentException().as("entity %s", bad).isThrownBy(() -> persistence.targets(tx, T0,
                        namespace, "Person", "john", JsonNavigationPath.from("$.history.currentAddress"), bad, Range.unbounded()));
                assertThatIllegalArgumentException().as("entity %s", bad)
                        .isThrownBy(() -> persistence.deleteEntity(tx, namespace, bad));
                assertThatIllegalArgumentException().as("entity %s", bad)
                        .isThrownBy(() -> new DocumentKey(namespace, bad, "john", T0));
            }
            for (String bad : badIds) {
                assertThatIllegalArgumentException().as("id %s", bad)
                        .isThrownBy(() -> persistence.read(tx, T0, namespace, "Person", bad));
                assertThatIllegalArgumentException().as("id %s", bad)
                        .isThrownBy(() -> persistence.deleteAllVersions(tx, namespace, "Person", bad));
                assertThatIllegalArgumentException().as("id %s", bad)
                        .isThrownBy(() -> new DocumentKey(namespace, "Person", bad, T0));
                if (bad != null) {
                    assertThatIllegalArgumentException().as("bound %s", bad)
                            .isThrownBy(() -> persistence.list(tx, T0, namespace, "Person", Range.firstAfter(1, bad)));
                }
            }
            assertThatIllegalArgumentException().isThrownBy(() -> persistence.write(tx,
                    List.of(Document.deleted(new DocumentKey(namespace, "Person", "john", T0)))));
        }
        assertThat(read("Person", "john")).isEmpty();
    }

    // ---- helpers

    /** Runs work in a transaction and commits it. */
    private void commit(java.util.function.Consumer<Transaction> work) {
        try (Transaction tx = persistence.begin(false)) {
            work.accept(tx);
            tx.commit();
        }
    }

    /** Runs a read in a read-only transaction of its own. */
    private <R> R inTransaction(Function<Transaction, ? extends R> read) {
        try (Transaction tx = persistence.begin(true)) {
            return read.apply(tx);
        }
    }

    private Optional<Document> read(String entity, String id) {
        return read(Instant.parse("9999-01-01T00:00:00Z"), entity, id);
    }

    private Optional<Document> read(Instant snapshot, String entity, String id) {
        return inTransaction(tx -> persistence.read(tx, snapshot, namespace, entity, id));
    }

    private List<Document> list(Instant snapshot, String entity, Range<String> range) {
        return inTransaction(tx -> persistence.list(tx, snapshot, namespace, entity, range));
    }

    private List<Document> versions(String entity, String id, Range<Instant> range) {
        return inTransaction(tx -> persistence.versions(tx, namespace, entity, id, range));
    }

    private List<Document> find(Instant snapshot, String entity, String path, String value, Range<String> range) {
        return inTransaction(tx -> persistence.find(tx, snapshot, namespace, entity, JsonNavigationPath.from(path), value, range));
    }

    private List<Document> targets(Instant snapshot, String personId, String path, String targetEntity, Range<String> range) {
        return inTransaction(tx -> persistence.targets(tx, snapshot, namespace, "Person", personId,
                JsonNavigationPath.from(path), targetEntity, range));
    }

    private List<Document> sources(Instant snapshot, String targetEntity, String targetId, String path, Range<String> range) {
        return inTransaction(tx -> persistence.sources(tx, snapshot, namespace, targetEntity, targetId,
                JsonNavigationPath.from(path), "Person", range));
    }

    private Document document(String entity, String id, Instant version, JsonNode content) {
        return new Document(new DocumentKey(namespace, entity, id, version), content);
    }

    private Document person(String id, Instant version, String firstname, String lastname) {
        return document("Person", id, version, JSON.createObjectNode().put("firstname", firstname)
                .put("lastname", lastname).put("born", 1998).put("isHuman", true));
    }

    private Document person(String id, Instant version, String firstname, String lastname, String currentAddress,
                            String workAddress, List<String> previousAddresses) {
        ObjectNode content = (ObjectNode) person(id, version, firstname, lastname).json();
        ArrayNode previous = content.putObject("history").put("currentAddress", currentAddress)
                .put("workAddress", workAddress).putArray("previousAddresses");
        previousAddresses.forEach(previous::add);
        return document("Person", id, version, content);
    }

    private Document address(String id, Instant version, String city) {
        return document("Address", id, version, JSON.createObjectNode().put("city", city).put("country", "Norway"));
    }

    private Document office(String id, Instant version, String city) {
        return document("Office", id, version, JSON.createObjectNode().put("city", city).put("country", "Norway"));
    }

    private static List<String> ids(List<Document> documents) {
        return documents.stream().map(document -> document.key().id()).toList();
    }

    private static String firstname(Optional<Document> document) {
        return document.orElseThrow().json().get("firstname").textValue();
    }

    private static String city(Optional<Document> document) {
        return document.orElseThrow().json().get("city").textValue();
    }

    /** JSON equality where field order does not matter and numbers are compared by value, not by Java type. */
    private static void assertSameJson(JsonNode actual, JsonNode expected) {
        assertThat(sameJson(actual, expected)).as("expected %s but was %s", expected, actual).isTrue();
    }

    private static boolean sameJson(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (Map.Entry<String, JsonNode> field : a.properties()) {
                if (!b.has(field.getKey()) || !sameJson(field.getValue(), b.get(field.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!sameJson(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }
}
