package io.descoped.lds.core.search.solr;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.search.SearchResponse;
import org.apache.solr.client.solrj.RemoteSolrException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The Solr provider on a real Solr 10, reached over HTTP as LDS reaches it. A write is in the index within the
 * five seconds the provider gives Solr to commit it, so the tests wait for what they search for.
 */
class SolrSearchIndexTest {

    private static final Duration COMMIT_WAIT = Duration.ofSeconds(30);

    private static SearchIndex searchIndex;

    @BeforeAll
    static void start() {
        searchIndex = new SolrProvider().getSearchIndex(Solr10.configuration());
    }

    @BeforeEach
    void emptyTheIndex() {
        searchIndex.deleteAll().blockingAwait();
    }

    @Test
    void aDocumentIsFoundByItsTextAndNotAfterItIsDeleted() throws IOException {
        JsonDocument person = document("UnitDataSet", "UnitDataSet_Person_1.json");

        searchIndex.createOrOverwrite(person).blockingAwait();

        SearchResponse found = awaitHits(() -> searchIndex.search("Norway", Set.of("UnitDataSet"), 0, 10).blockingGet(), 1);
        assertThat(found.getTotalHits()).isEqualTo(1);
        DocumentKey key = found.getResults().iterator().next().getDocumentKey();
        assertThat(key.entity()).isEqualTo("UnitDataSet");
        assertThat(key.id()).isEqualTo("b9c10b86-5867-4270-b56e-ee7439fe381e");
        assertThat(key.namespace()).isEqualTo("ns");
        assertThat(key.timestamp()).isEqualTo(person.key().timestamp());

        searchIndex.delete(person).blockingAwait();

        assertThat(awaitHits(() -> searchIndex.search("Norway", null, 0, 10).blockingGet(), 0).getTotalHits()).isZero();
    }

    @Test
    void aSearchIsNarrowedToTheEntitiesItNamesAndToItsPage() throws IOException {
        searchIndex.createOrOverwrite(List.of(
                document("UnitDataSet", "UnitDataSet_Person_1.json"),
                document("UnitDataSet", "UnitDataSet_Family_1.json"),
                document("LogicalRecord", "LogicalRecord_Person_1.json")
        )).blockingAwait();

        SearchResponse all = awaitHits(() -> searchIndex.search("*:*", null, 0, 10).blockingGet(), 3);
        assertThat(all.getResults()).extracting(result -> result.getDocumentKey().entity())
                .containsExactlyInAnyOrder("UnitDataSet", "UnitDataSet", "LogicalRecord");

        SearchResponse dataSets = searchIndex.search("*:*", Set.of("UnitDataSet"), 0, 10).blockingGet();
        assertThat(dataSets.getTotalHits()).isEqualTo(2);
        assertThat(dataSets.getResults()).extracting(result -> result.getDocumentKey().entity()).containsOnly("UnitDataSet");

        SearchResponse either = searchIndex.search("*:*", Set.of("UnitDataSet", "LogicalRecord"), 0, 10).blockingGet();
        assertThat(either.getTotalHits()).isEqualTo(3);

        SearchResponse secondPage = searchIndex.search("*:*", null, 2, 2).blockingGet();
        assertThat(secondPage.getTotalHits()).isEqualTo(3);
        assertThat(secondPage.getResults()).hasSize(1);
    }

    @Test
    void aWriteAfterSolrHasClosedTheIdleConnectionStillReachesTheIndex() throws InterruptedException {
        searchIndex.createOrOverwrite(text("UnitDataSet", "first", "before the pause")).blockingAwait();
        awaitHits(() -> searchIndex.search("pause", null, 0, 10).blockingGet(), 1);

        Thread.sleep(Solr10.IDLE_TIMEOUT.multipliedBy(2).toMillis());

        searchIndex.createOrOverwrite(text("UnitDataSet", "second", "after the pause")).blockingAwait();
        assertThat(awaitHits(() -> searchIndex.search("pause", null, 0, 10).blockingGet(), 2).getResults())
                .extracting(result -> result.getDocumentKey().id()).containsExactlyInAnyOrder("first", "second");
        assertThat(((SolrSearchIndex) searchIndex).failedUpdates()).isZero();
    }

    @Test
    void theOperatorsOfTheTextDoNotLoosenTheEntityFilter() {
        searchIndex.createOrOverwrite(List.of(
                text("UnitDataSet", "plural", "memories"),
                text("UnitDataSet", "singular", "memory"),
                text("LogicalRecord", "other", "memory")
        )).blockingAwait();
        awaitHits(() -> searchIndex.search("memories OR memory", null, 0, 10).blockingGet(), 3);

        SearchResponse dataSets = searchIndex.search("memories OR memory", Set.of("UnitDataSet"), 0, 10).blockingGet();

        assertThat(dataSets.getResults()).extracting(result -> result.getDocumentKey().id())
                .containsExactlyInAnyOrder("plural", "singular");
    }

    @Test
    void aFieldNamedInTheTextDoesNotWidenTheEntityFilter() {
        searchIndex.createOrOverwrite(List.of(
                text("UnitDataSet", "brewery", "a brewery"),
                text("LogicalRecord", "record", "a record")
        )).blockingAwait();
        awaitHits(() -> searchIndex.search("*:*", null, 0, 10).blockingGet(), 2);

        SearchResponse dataSets = searchIndex.search("brewery OR entity:LogicalRecord", Set.of("UnitDataSet"), 0, 10)
                .blockingGet();

        assertThat(dataSets.getResults()).extracting(result -> result.getDocumentKey().entity()).containsOnly("UnitDataSet");
        assertThat(dataSets.getTotalHits()).isEqualTo(1);
    }

    @Test
    void anUpdateSolrRefusesIsCountedForEachOfItsDocumentsAndTheWriteGoesOn() {
        // Solr answers 404 for a core it does not have; SolrJ then names the documents of the update
        SolrSearchIndex missingCore = new SolrSearchIndex(Solr10.url().replace("/lds-index", "/no-such-core"));

        missingCore.createOrOverwrite(List.of(text("UnitDataSet", "a", "one"), text("UnitDataSet", "b", "two")))
                .blockingAwait();

        await().atMost(COMMIT_WAIT).until(() -> missingCore.failedUpdates() == 2);
    }

    @Test
    void aDeleteSolrRefusesFailsForItsCaller() {
        // SolrJ sends an update without documents at once rather than queue it (ConcurrentUpdateBaseSolrClient.request,
        // streamDeletes false), so the caller gets Solr's answer and nothing is left to count
        SolrSearchIndex missingCore = new SolrSearchIndex(Solr10.url().replace("/lds-index", "/no-such-core"));

        assertThatThrownBy(() -> missingCore.delete(text("UnitDataSet", "a", "one")).blockingAwait())
                .isInstanceOf(RemoteSolrException.class).hasMessageContaining("404");
        assertThat(missingCore.failedUpdates()).isZero();
    }

    @Test
    void anUpdateThatCannotReachSolrIsCounted() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        SolrSearchIndex unreachable = new SolrSearchIndex("http://127.0.0.1:" + closedPort + "/solr/lds-index");

        unreachable.createOrOverwrite(text("UnitDataSet", "a", "one")).blockingAwait();

        await().atMost(COMMIT_WAIT).until(() -> unreachable.failedUpdates() >= 1);
    }

    @Test
    void aSearchWithAnEmptySetOfEntitiesIsNotNarrowed() {
        searchIndex.createOrOverwrite(List.of(
                text("UnitDataSet", "one", "fjord"),
                text("LogicalRecord", "two", "fjord")
        )).blockingAwait();
        awaitHits(() -> searchIndex.search("fjord", null, 0, 10).blockingGet(), 2);

        assertThat(searchIndex.search("fjord", Set.of(), 0, 10).blockingGet().getTotalHits()).isEqualTo(2);
    }

    @Test
    void anEntityNameWithTheSyntaxOfAQueryIsMatchedAsAName() {
        // the names come from the enum TypeFilters of the GraphQL schema, so they have no space
        searchIndex.createOrOverwrite(text("UnitDataSet", "one", "fjord")).blockingAwait();
        awaitHits(() -> searchIndex.search("fjord", null, 0, 10).blockingGet(), 1);

        assertThat(searchIndex.search("fjord", Set.of("*"), 0, 10).blockingGet().getTotalHits()).isZero();
        assertThat(searchIndex.search("fjord", Set.of("Unit*"), 0, 10).blockingGet().getTotalHits()).isZero();
        assertThat(searchIndex.search("fjord", Set.of("UnitDataSe?"), 0, 10).blockingGet().getTotalHits()).isZero();
    }

    /**
     * Searches until the index has the number of hits the test expects, and fails when the time a commit may take is
     * over first. A search fails until the first commit after Solr starts, which makes the field it searches in
     * (the core is schemaless), so a failure counts as not found yet.
     */
    private static SearchResponse awaitHits(Supplier<SearchResponse> search, long hits) {
        AtomicReference<SearchResponse> response = new AtomicReference<>();
        await().atMost(COMMIT_WAIT).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(100)).ignoreExceptions()
                .untilAsserted(() -> {
            response.set(search.get());
            assertThat(response.get().getTotalHits()).as("hits").isEqualTo(hits);
        });
        return response.get();
    }

    /**
     * A document whose one searched text is the given one.
     */
    private static JsonDocument text(String entity, String id, String text) {
        JsonNode json = JsonTools.toJsonNode("""
                {"id": "%s", "name": [{"languageCode": "en", "languageText": "%s"}]}""".formatted(id, text));
        return new JsonDocument(new DocumentKey("ns", entity, id, ZonedDateTime.now()), json);
    }

    private static JsonDocument document(String entity, String resource) throws IOException {
        try (InputStream in = SolrSearchIndexTest.class.getResourceAsStream("/" + resource)) {
            JsonNode json = JsonTools.toJsonNode(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return new JsonDocument(new DocumentKey("ns", entity, JsonTools.text(json.get("id")), ZonedDateTime.now()), json);
        }
    }
}
