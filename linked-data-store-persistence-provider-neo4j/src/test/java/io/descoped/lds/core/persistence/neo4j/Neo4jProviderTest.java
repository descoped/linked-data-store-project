package io.descoped.lds.core.persistence.neo4j;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.Range;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.api.specification.SpecificationElementType;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.descoped.lds.api.persistence.json.JsonTools.mapper;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.arrayRefNode;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.booleanNode;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.createSpecificationAndRoot;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.numericNode;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.objectNode;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.refNode;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.stringNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the Neo4j provider must do beyond the shared suite, on a real Neo4j. Each test uses namespaces of its own,
 * so the tests do not see each other's documents.
 */
class Neo4jProviderTest {

    private static final ZoneId UTC = ZoneId.of("Etc/UTC");
    private static final ZonedDateTime T1 = ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, UTC);
    private static final ZonedDateTime T2 = ZonedDateTime.of(2021, 1, 1, 0, 0, 0, 0, UTC);
    private static final ZonedDateTime NOW = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, UTC);
    private static final JsonNavigationPath NAME = JsonNavigationPath.from("$.name");
    private static final JsonNavigationPath HOME = JsonNavigationPath.from("$.home");
    private static final JsonNavigationPath EARLIER = JsonNavigationPath.from("$.earlier[]");
    /* Names with the characters that end a name or a text in Cypher. */
    private static final String ODD_ENTITY = "Odd `entity`) DETACH DELETE (r";
    private static final String ODD_FIELD = "it's a \\ field";

    private static final Specification SPECIFICATION = createSpecificationAndRoot(Set.of(
            objectNode(SpecificationElementType.MANAGED, "City", Set.of(
                    stringNode("name"),
                    numericNode("people"),
                    booleanNode("capital")
            )),
            objectNode(SpecificationElementType.MANAGED, "Citizen", Set.of(
                    stringNode("name"),
                    refNode("home", Set.of("City")),
                    arrayRefNode("earlier", Set.of("City"), stringNode("[]"))
            )),
            objectNode(SpecificationElementType.MANAGED, ODD_ENTITY, Set.of(
                    stringNode(ODD_FIELD),
                    refNode("where", Set.of(ODD_ENTITY))
            ))
    ));

    private static RxJsonPersistence persistence;

    @BeforeAll
    static void start() {
        persistence = new Neo4jInitializer().initialize("provider-test", Neo4j2026.configuration(), SPECIFICATION.getManagedDomains());
    }

    @AfterAll
    static void stop() {
        if (persistence != null) {
            persistence.close();
        }
    }

    // ---- namespaces

    @Test
    void aDocumentIsSeenInItsOwnNamespaceOnly() {
        write("seen-a", "City", "same", T1, city("from a"));
        write("seen-b", "City", "same", T1, city("from b"));
        write("seen-a", "City", "only-a", T1, city("from a"));

        assertThat(name(read("seen-a", "City", "same"))).isEqualTo("from a");
        assertThat(name(read("seen-b", "City", "same"))).isEqualTo("from b");
        assertThat(read("seen-c", "City", "same")).isNull();
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(ids(persistence.readDocuments(tx, NOW, "seen-a", "City", Range.unbounded()))).containsExactly("only-a", "same");
            assertThat(ids(persistence.readDocuments(tx, NOW, "seen-b", "City", Range.unbounded()))).containsExactly("same");
            assertThat(ids(persistence.readDocuments(tx, NOW, "seen-c", "City", Range.unbounded()))).isEmpty();
            assertThat(ids(persistence.findDocument(tx, NOW, "seen-a", "City", NAME, "from a", Range.unbounded()))).containsExactly("only-a", "same");
            assertThat(ids(persistence.findDocument(tx, NOW, "seen-b", "City", NAME, "from a", Range.unbounded()))).isEmpty();
            assertThat(ids(persistence.readDocumentVersions(tx, "seen-b", "City", "same", Range.unbounded()))).containsExactly("same");
            assertThat(ids(persistence.readDocumentVersions(tx, "seen-b", "City", "only-a", Range.unbounded()))).isEmpty();
            assertThat(persistence.hasNext(tx, NOW, "seen-a", "City", "only-a").blockingGet()).isTrue();
            assertThat(persistence.hasPrevious(tx, NOW, "seen-a", "City", "same").blockingGet()).isTrue();
            assertThat(persistence.hasPrevious(tx, NOW, "seen-b", "City", "same").blockingGet()).isFalse();
            assertThat(persistence.hasNext(tx, NOW, "seen-c", "City", "a").blockingGet()).isFalse();
        }
    }

    @Test
    void aNewVersionInOneNamespaceLeavesTheVersionsOfTheOthers() {
        write("version-a", "City", "oslo", T1, city("Christiania"));
        write("version-b", "City", "oslo", T1, city("Christiania"));
        write("version-b", "City", "oslo", T2, city("Oslo"));

        assertThat(name(read("version-a", "City", "oslo"))).isEqualTo("Christiania");
        assertThat(name(read("version-b", "City", "oslo"))).isEqualTo("Oslo");
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(versions(persistence.readDocumentVersions(tx, "version-a", "City", "oslo", Range.unbounded()))).containsExactly(T1);
            assertThat(versions(persistence.readDocumentVersions(tx, "version-b", "City", "oslo", Range.unbounded()))).containsExactly(T1, T2);
        }
    }

    @Test
    void aLinkPointsAtTheDocumentOfItsOwnNamespace() {
        write("link-a", "City", "oslo", T1, city("Oslo of a"));
        write("link-b", "City", "oslo", T1, city("Oslo of b"));
        write("link-b", "City", "bergen", T1, city("Bergen of b"));
        write("link-a", "Citizen", "kari", T1, citizen("Kari", "/City/oslo", List.of("/City/bergen")));
        write("link-b", "Citizen", "ola", T1, citizen("Ola", "/City/oslo", List.of("/City/oslo", "/City/bergen")));

        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(names(persistence.readTargetDocuments(tx, NOW, "link-a", "Citizen", "kari", HOME, "City", Range.unbounded())))
                    .containsExactly("Oslo of a");
            // bergen is not written in namespace a: the link is there, its target is not (yet)
            assertThat(names(persistence.readTargetDocuments(tx, NOW, "link-a", "Citizen", "kari", EARLIER, "City", Range.unbounded())))
                    .isEmpty();
            assertThat(names(persistence.readTargetDocuments(tx, NOW, "link-b", "Citizen", "ola", EARLIER, "City", Range.unbounded())))
                    .containsExactly("Bergen of b", "Oslo of b");
            assertThat(ids(persistence.readTargetDocuments(tx, NOW, "link-b", "Citizen", "kari", HOME, "City", Range.unbounded())))
                    .isEmpty();

            assertThat(ids(persistence.readSourceDocuments(tx, NOW, "link-a", "City", "oslo", HOME, "Citizen", Range.unbounded())))
                    .containsExactly("kari");
            assertThat(ids(persistence.readSourceDocuments(tx, NOW, "link-b", "City", "oslo", HOME, "Citizen", Range.unbounded())))
                    .containsExactly("ola");
            assertThat(ids(persistence.readSourceDocuments(tx, NOW, "link-b", "City", "bergen", EARLIER, "Citizen", Range.unbounded())))
                    .containsExactly("ola");
            assertThat(ids(persistence.readSourceDocuments(tx, NOW, "link-a", "City", "bergen", EARLIER, "Citizen", Range.unbounded())))
                    .containsExactly("kari");
        }

        // the target comes into being in namespace a, after the link to it
        write("link-a", "City", "bergen", T1, city("Bergen of a"));
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(names(persistence.readTargetDocuments(tx, NOW, "link-a", "Citizen", "kari", EARLIER, "City", Range.unbounded())))
                    .containsExactly("Bergen of a");
        }
    }

    // ---- links

    @Test
    void aLinkAtTheTopOfADocumentIsFollowed() {
        write("top-link", "City", "oslo", T1, city("Oslo"));
        write("top-link", "City", "bergen", T1, city("Bergen"));
        write("top-link", "Citizen", "kari", T1, citizen("Kari", "/City/oslo", List.of("/City/bergen")));

        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(names(persistence.readTargetDocuments(tx, NOW, "top-link", "Citizen", "kari", HOME, "City", Range.unbounded())))
                    .containsExactly("Oslo");
            assertThat(names(persistence.readTargetDocuments(tx, NOW, "top-link", "Citizen", "kari", EARLIER, "City", Range.unbounded())))
                    .containsExactly("Bergen");
            assertThat(ids(persistence.readSourceDocuments(tx, NOW, "top-link", "City", "oslo", HOME, "Citizen", Range.unbounded())))
                    .containsExactly("kari");
        }
    }

    @Test
    void theLimitOfLinkedDocumentsCountsThoseThatAreWritten() {
        write("link-limit", "City", "c", T1, city("C"));
        write("link-limit", "City", "d", T1, city("D"));
        // a and b are linked to and not written
        write("link-limit", "Citizen", "kari", T1, citizen("Kari", "/City/c", List.of("/City/a", "/City/b", "/City/c", "/City/d")));

        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(ids(persistence.readTargetDocuments(tx, NOW, "link-limit", "Citizen", "kari", EARLIER, "City", Range.first(1))))
                    .containsExactly("c");
            assertThat(ids(persistence.readTargetDocuments(tx, NOW, "link-limit", "Citizen", "kari", EARLIER, "City", Range.firstAfter(1, "a"))))
                    .containsExactly("c");
            assertThat(ids(persistence.readTargetDocuments(tx, NOW, "link-limit", "Citizen", "kari", EARLIER, "City", Range.last(1))))
                    .containsExactly("d");
        }
    }

    @Test
    void aDocumentThatIsLinkedTwiceIsReadOnce() {
        write("link-twice", "City", "oslo", T1, city("Oslo"));
        write("link-twice", "City", "bergen", T1, city("Bergen"));
        write("link-twice", "Citizen", "kari", T1, citizen("Kari", "/City/oslo", List.of("/City/bergen", "/City/oslo", "/City/bergen")));

        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(ids(persistence.readTargetDocuments(tx, NOW, "link-twice", "Citizen", "kari", EARLIER, "City", Range.unbounded())))
                    .containsExactly("bergen", "oslo");
            // the limit counts documents, not links
            assertThat(ids(persistence.readTargetDocuments(tx, NOW, "link-twice", "Citizen", "kari", EARLIER, "City", Range.first(2))))
                    .containsExactly("bergen", "oslo");
            assertThat(ids(persistence.readSourceDocuments(tx, NOW, "link-twice", "City", "bergen", EARLIER, "Citizen", Range.unbounded())))
                    .containsExactly("kari");
        }
        assertThat(read("link-twice", "Citizen", "kari").jackson().get("earlier")).hasSize(3);
    }

    // ---- values

    @Test
    void aNullIsReadAsNull() {
        ObjectNode written = mapper.createObjectNode();
        written.putNull("name").putNull("people").putNull("capital");
        write("null", "City", "nowhere", T1, written);

        assertThat(read("null", "City", "nowhere").jackson()).isEqualTo(written);
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(ids(persistence.readDocuments(tx, NOW, "null", "City", Range.unbounded()))).containsExactly("nowhere");
        }
    }

    @Test
    void aNameOfAnyCharactersStaysAName() {
        ObjectNode written = mapper.createObjectNode().put(ODD_FIELD, "it's 'quoted' \\ and `ticked`").put("where", "/" + ODD_ENTITY + "/there");
        write("odd", ODD_ENTITY, "here", T1, written);
        write("odd", ODD_ENTITY, "there", T1, mapper.createObjectNode().put(ODD_FIELD, "there"));
        write("odd", "City", "untouched", T1, city("Untouched"));

        assertThat(read("odd", ODD_ENTITY, "here").jackson()).isEqualTo(written);
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(ids(persistence.findDocument(tx, NOW, "odd", ODD_ENTITY, JsonNavigationPath.from("$", ODD_FIELD), "there", Range.unbounded())))
                    .containsExactly("there");
            assertThat(ids(persistence.readTargetDocuments(tx, NOW, "odd", ODD_ENTITY, "here", JsonNavigationPath.from("$", "where"), ODD_ENTITY, Range.unbounded())))
                    .containsExactly("there");
            assertThat(ids(persistence.readSourceDocuments(tx, NOW, "odd", ODD_ENTITY, "there", JsonNavigationPath.from("$", "where"), ODD_ENTITY, Range.unbounded())))
                    .containsExactly("here");
            assertThat(ids(persistence.readDocumentVersions(tx, "odd", ODD_ENTITY, "here", Range.unbounded()))).containsExactly("here");
        }
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.markDocumentDeleted(tx, "odd", ODD_ENTITY, "here", T2, null).blockingAwait();
            persistence.deleteDocument(tx, "odd", ODD_ENTITY, "here", T2, null).blockingAwait();
            persistence.deleteAllDocumentVersions(tx, "odd", ODD_ENTITY, "here", null).blockingAwait();
            persistence.deleteAllEntities(tx, "odd", ODD_ENTITY, SPECIFICATION).blockingAwait();
        }
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(ids(persistence.readDocuments(tx, NOW, "odd", ODD_ENTITY, Range.unbounded()))).isEmpty();
            assertThat(ids(persistence.readDocuments(tx, NOW, "odd", "City", Range.unbounded()))).containsExactly("untouched");
        }
    }

    // ---- find

    @Test
    void findHonoursTheRange() {
        for (String id : List.of("a", "b", "c", "d", "e")) {
            write("find-range", "City", id, T1, city("same"));
        }
        write("find-range", "City", "other", T1, city("other"));

        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(ids(persistence.findDocument(tx, NOW, "find-range", "City", NAME, "same", Range.unbounded()))).containsExactly("a", "b", "c", "d", "e");
            assertThat(ids(persistence.findDocument(tx, NOW, "find-range", "City", NAME, "same", Range.first(2)))).containsExactly("a", "b");
            assertThat(ids(persistence.findDocument(tx, NOW, "find-range", "City", NAME, "same", Range.firstAfter(2, "b")))).containsExactly("c", "d");
            assertThat(ids(persistence.findDocument(tx, NOW, "find-range", "City", NAME, "same", Range.between("a", "d")))).containsExactly("b", "c");
            assertThat(ids(persistence.findDocument(tx, NOW, "find-range", "City", NAME, "same", Range.last(2)))).containsExactly("e", "d");
            assertThat(ids(persistence.findDocument(tx, NOW, "find-range", "City", NAME, "same", Range.lastBefore(2, "c")))).containsExactly("b", "a");
        }
    }

    // ---- lists

    @Test
    void aListOfAnySizeIsRead() {
        int documents = 1000;
        List<JsonDocument> cities = new ArrayList<>();
        for (int i = 0; i < documents; i++) {
            cities.add(new JsonDocument(new DocumentKey("long-list", "City", String.format("city-%04d", i), T1), city("City " + i)));
        }
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.createOrOverwrite(tx, Flowable.fromIterable(cities), SPECIFICATION).blockingAwait();
        }
        try (Transaction tx = persistence.createTransaction(true)) {
            List<String> ids = ids(persistence.readDocuments(tx, NOW, "long-list", "City", Range.unbounded()));
            assertThat(ids).hasSize(documents).isSorted();
            assertThat(ids(persistence.readDocuments(tx, NOW, "long-list", "City", Range.last(3))))
                    .containsExactly("city-0999", "city-0998", "city-0997");
        }
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.deleteAllEntities(tx, "long-list", "City", SPECIFICATION).blockingAwait();
        }
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(ids(persistence.readDocuments(tx, NOW, "long-list", "City", Range.unbounded()))).isEmpty();
        }
    }

    @Test
    void aDocumentIsTakenFromTheStoreWhenItIsAskedFor() {
        for (int i = 0; i < 10; i++) {
            write("demand", "City", "city-" + i, T1, city("City " + i));
        }
        try (Transaction tx = persistence.createTransaction(true)) {
            TestSubscriber<JsonDocument> subscriber = persistence.readDocuments(tx, NOW, "demand", "City", Range.unbounded()).test(0);
            subscriber.assertNoValues();
            subscriber.requestMore(1);
            subscriber.assertValueCount(1);
            subscriber.requestMore(2);
            subscriber.assertValueCount(3).assertNotComplete();
            subscriber.cancel();
            // the rest of the cancelled read is not in the way of the next one
            assertThat(ids(persistence.readDocuments(tx, NOW, "demand", "City", Range.first(2)))).containsExactly("city-0", "city-1");
        }
    }

    // ---- versions

    @Test
    void aVersionThatIsWrittenAgainIsStillRead() {
        // nothing below the root of the version: an empty document, and one that holds a link only
        ObjectNode empty = mapper.createObjectNode();
        ObjectNode linkOnly = mapper.createObjectNode().put("home", "/City/oslo");
        for (int times = 0; times < 2; times++) {
            write("again", "City", "empty", T1, empty);
            write("again", "Citizen", "link-only", T1, linkOnly);
        }

        assertThat(read("again", "City", "empty").jackson()).isEqualTo(empty);
        assertThat(read("again", "Citizen", "link-only").jackson()).isEqualTo(linkOnly);
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(versions(persistence.readDocumentVersions(tx, "again", "Citizen", "link-only", Range.unbounded()))).containsExactly(T1);
        }
    }

    @Test
    void aDocumentWrittenAtTheTimeItWasMarkedAsDeletedIsRead() {
        write("again-marker", "City", "oslo", T1, city("Christiania"));
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.markDocumentDeleted(tx, "again-marker", "City", "oslo", T2, null).blockingAwait();
        }
        write("again-marker", "City", "oslo", T2, city("Oslo"));

        assertThat(name(read("again-marker", "City", "oslo"))).isEqualTo("Oslo");
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(names(persistence.readDocumentVersions(tx, "again-marker", "City", "oslo", Range.unbounded()))).containsExactly("Christiania", "Oslo");
        }
    }

    @Test
    void aVersionIsTheInstantWhateverZoneItWasWrittenWith() {
        ZonedDateTime inOslo = T1.withZoneSameInstant(ZoneId.of("Europe/Oslo"));
        write("zone", "City", "oslo", T1, city("written as UTC"));
        write("zone", "City", "oslo", inOslo, city("written as Oslo time"));

        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(names(persistence.readDocumentVersions(tx, "zone", "City", "oslo", Range.unbounded())))
                    .containsExactly("written as Oslo time");
        }
        assertThat(name(read("zone", "City", "oslo"))).isEqualTo("written as Oslo time");
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.deleteDocument(tx, "zone", "City", "oslo", inOslo.withZoneSameInstant(ZoneId.of("Asia/Tokyo")), null).blockingAwait();
        }
        assertThat(read("zone", "City", "oslo")).isNull();
    }

    // ---- transactions

    @Test
    void aCancelledTransactionWritesNothing() {
        Transaction tx = persistence.createTransaction(false);
        persistence.createOrOverwrite(tx, new JsonDocument(new DocumentKey("cancelled", "City", "oslo", T1), city("Oslo")), SPECIFICATION)
                .blockingAwait();
        tx.cancel().join();

        assertThat(read("cancelled", "City", "oslo")).isNull();
    }

    @Test
    void endingATransactionAgainDoesNothing() {
        Transaction tx = persistence.createTransaction(false);
        persistence.createOrOverwrite(tx, new JsonDocument(new DocumentKey("ended", "City", "oslo", T1), city("Oslo")), SPECIFICATION)
                .blockingAwait();
        tx.commit().join();
        tx.commit().join();
        tx.cancel().join();
        tx.close();

        assertThat(name(read("ended", "City", "oslo"))).isEqualTo("Oslo");
    }

    // ---- start

    @Test
    void instancesThatStartTogetherAllStart() throws InterruptedException {
        int instances = 4;
        List<Throwable> failures = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            Set<String> entities = Set.of("Together" + round, "Also" + round);
            CountDownLatch together = new CountDownLatch(1);
            try (ExecutorService threads = Executors.newFixedThreadPool(instances)) {
                List<Future<RxJsonPersistence>> starting = new ArrayList<>();
                for (int i = 0; i < instances; i++) {
                    starting.add(threads.submit(() -> {
                        together.await();
                        return new Neo4jInitializer().initialize("provider-test", Neo4j2026.configuration(), entities);
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

    @Test
    void aPlainIndexDoesNotStandInForTheConstraint() {
        try (Driver driver = Neo4j2026.driver(); Session session = driver.session()) {
            session.run("CREATE INDEX FOR (n:PlainIndex) ON (n.namespace, n.id)").consume();
        }

        // Neo4j refuses the constraint while the index is there, so the start fails and says why
        assertThatThrownBy(() -> new Neo4jInitializer().initialize("provider-test", Neo4j2026.configuration(), Set.of("PlainIndex")))
                .hasMessageContaining("PlainIndex");
    }

    // ---- namespaces, deletes

    @Test
    void deletingOneVersionLeavesTheOtherNamespaces() {
        write("delete-version-a", "City", "oslo", T1, city("Oslo of a"));
        write("delete-version-b", "City", "oslo", T1, city("Oslo of b"));
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.deleteDocument(tx, "delete-version-b", "City", "oslo", T1, null).blockingAwait();
        }
        assertThat(read("delete-version-b", "City", "oslo")).isNull();
        assertThat(name(read("delete-version-a", "City", "oslo"))).isEqualTo("Oslo of a");
    }

    @Test
    void deletingEveryVersionLeavesTheOtherNamespaces() {
        write("delete-versions-a", "City", "oslo", T1, city("Oslo of a"));
        write("delete-versions-b", "City", "oslo", T1, city("Oslo of b"));
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.deleteAllDocumentVersions(tx, "delete-versions-b", "City", "oslo", null).blockingAwait();
        }
        assertThat(read("delete-versions-b", "City", "oslo")).isNull();
        assertThat(name(read("delete-versions-a", "City", "oslo"))).isEqualTo("Oslo of a");
    }

    @Test
    void deletingAnEntityLeavesTheOtherNamespaces() {
        write("delete-entity-a", "City", "oslo", T1, city("Oslo of a"));
        write("delete-entity-b", "City", "oslo", T1, city("Oslo of b"));
        write("delete-entity-b", "City", "bergen", T1, city("Bergen of b"));
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.deleteAllEntities(tx, "delete-entity-b", "City", SPECIFICATION).blockingAwait();
        }
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(ids(persistence.readDocuments(tx, NOW, "delete-entity-b", "City", Range.unbounded()))).isEmpty();
            assertThat(names(persistence.readDocuments(tx, NOW, "delete-entity-a", "City", Range.unbounded()))).containsExactly("Oslo of a");
        }
    }

    @Test
    void markingADocumentAsDeletedLeavesTheOtherNamespaces() {
        write("mark-a", "City", "oslo", T1, city("Oslo of a"));
        write("mark-b", "City", "oslo", T1, city("Oslo of b"));
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.markDocumentDeleted(tx, "mark-b", "City", "oslo", T2, null).blockingAwait();
        }
        assertThat(name(read("mark-a", "City", "oslo"))).isEqualTo("Oslo of a");
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(versions(persistence.readDocumentVersions(tx, "mark-a", "City", "oslo", Range.unbounded()))).containsExactly(T1);
            assertThat(versions(persistence.readDocumentVersions(tx, "mark-b", "City", "oslo", Range.unbounded()))).containsExactly(T1, T2);
            assertThat(ids(persistence.findDocument(tx, NOW, "mark-a", "City", NAME, "Oslo of a", Range.unbounded()))).containsExactly("oslo");
        }
    }

    // ---- helpers

    private static ObjectNode city(String name) {
        return mapper.createObjectNode().put("name", name);
    }

    private static ObjectNode citizen(String name, String home, List<String> earlier) {
        ObjectNode citizen = mapper.createObjectNode().put("name", name).put("home", home);
        ArrayNode links = citizen.putArray("earlier");
        earlier.forEach(links::add);
        return citizen;
    }

    private static void write(String namespace, String entity, String id, ZonedDateTime version, ObjectNode body) {
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.createOrOverwrite(tx, new JsonDocument(new DocumentKey(namespace, entity, id, version), body), SPECIFICATION)
                    .blockingAwait();
        }
    }

    private static JsonDocument read(String namespace, String entity, String id) {
        try (Transaction tx = persistence.createTransaction(true)) {
            return persistence.readDocument(tx, NOW, namespace, entity, id).blockingGet();
        }
    }

    private static String name(JsonDocument document) {
        return document.jackson().get("name").asText();
    }

    private static List<String> ids(Flowable<JsonDocument> documents) {
        return documents.map(document -> document.key().id()).toList().blockingGet();
    }

    private static List<String> names(Flowable<JsonDocument> documents) {
        return documents.map(Neo4jProviderTest::name).toList().blockingGet();
    }

    private static List<ZonedDateTime> versions(Flowable<JsonDocument> documents) {
        return documents.map(document -> document.key().timestamp().withZoneSameInstant(UTC)).toList().blockingGet();
    }
}
