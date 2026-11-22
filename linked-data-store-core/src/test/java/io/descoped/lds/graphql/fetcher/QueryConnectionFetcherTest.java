package io.descoped.lds.graphql.fetcher;

import graphql.relay.Connection;
import graphql.relay.Edge;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.Range;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.search.SearchResponse;
import io.descoped.lds.api.search.SearchResult;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.LongStream;

import static io.descoped.lds.graphql.fetcher.QueryConnectionFetcher.IndexBasedRange.fromRange;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pages of a search: which places of the index's result a range asks for, and the cursors of the edges.
 */
class QueryConnectionFetcherTest {

    private static final ZonedDateTime SNAPSHOT = ZonedDateTime.parse("2026-11-22T12:00:00Z");
    private static final LongSupplier SIXTY = () -> 60;
    private static final LongSupplier NOT_ASKED = () -> {
        throw new AssertionError("the total is not needed");
    };

    private static String cursor(long place) {
        return new QueryConnectionCursor(place).getValue();
    }

    private static void assertPlaces(QueryConnectionFetcher.IndexBasedRange range, long from, long size) {
        assertThat(range.getFrom()).as("from").isEqualTo(from);
        assertThat(range.getSize()).as("size").isEqualTo(size);
    }

    @Test
    void firstStartsAtTheBeginningOrAfterItsCursor() {
        assertPlaces(fromRange(Range.first(10), NOT_ASKED), 0, 10);
        assertPlaces(fromRange(Range.firstAfter(10, cursor(9)), NOT_ASKED), 10, 10);
    }

    @Test
    void lastEndsAtTheEndOfTheResultOrBeforeItsCursor() {
        assertPlaces(fromRange(Range.last(10), SIXTY), 50, 10);
        assertPlaces(fromRange(Range.lastBefore(5, cursor(20)), NOT_ASKED), 15, 5);
        assertPlaces(fromRange(Range.lastBefore(5, cursor(2)), NOT_ASKED), 0, 2);
        assertPlaces(fromRange(Range.last(10), () -> 3), 0, 3);
    }

    @Test
    void afterAndBeforeLeaveTheirOwnResultsOut() {
        assertPlaces(fromRange(Range.between(cursor(10), cursor(12)), NOT_ASKED), 11, 1);
        assertPlaces(fromRange(Range.firstBetween(5, cursor(10), cursor(12)), NOT_ASKED), 11, 1);
        assertPlaces(fromRange(Range.lastBetween(5, cursor(10), cursor(20)), NOT_ASKED), 15, 5);
        assertPlaces(fromRange(Range.lastBetween(5, cursor(10), cursor(12)), NOT_ASKED), 11, 1);
    }

    @Test
    void withNeitherFirstNorLastAPageIsTheDefaultSize() {
        assertPlaces(fromRange(Range.unbounded(), NOT_ASKED), 0, QueryConnectionFetcher.DEFAULT_PAGE_SIZE);
        assertPlaces(fromRange(Range.between(cursor(4), null), NOT_ASKED), 5, QueryConnectionFetcher.DEFAULT_PAGE_SIZE);
    }

    @Test
    void aPageIsAtMostTheLargestSize() {
        assertPlaces(fromRange(Range.first(1000), NOT_ASKED), 0, QueryConnectionFetcher.MAX_PAGE_SIZE);
        assertPlaces(fromRange(Range.last(1000), () -> 500), 400, QueryConnectionFetcher.MAX_PAGE_SIZE);
    }

    @Test
    void theNextPageStartsAfterTheLastEdgeOfThePageBefore() {
        QueryConnectionFetcher fetcher = new QueryConnectionFetcher(index(5), persistence(Set.of()), "ns", "Search");

        Connection<Map<String, Object>> page1 = fetcher.search("text", null, SNAPSHOT, Range.first(2));
        Connection<Map<String, Object>> page2 = fetcher.search("text", null, SNAPSHOT,
                Range.firstAfter(2, page1.getPageInfo().getEndCursor().getValue()));
        Connection<Map<String, Object>> page3 = fetcher.search("text", null, SNAPSHOT,
                Range.firstAfter(2, page2.getPageInfo().getEndCursor().getValue()));

        assertThat(ids(page1)).containsExactly("d0", "d1");
        assertThat(ids(page2)).containsExactly("d2", "d3");
        assertThat(ids(page3)).containsExactly("d4");
        assertThat(page2.getPageInfo().isHasNextPage()).isTrue();
        assertThat(page3.getPageInfo().isHasNextPage()).isFalse();
    }

    @Test
    void lastWithoutBeforeGivesTheEndOfTheResult() {
        QueryConnectionFetcher fetcher = new QueryConnectionFetcher(index(25), persistence(Set.of()), "ns", "Search");

        assertThat(ids(fetcher.search("text", null, SNAPSHOT, Range.last(2)))).containsExactly("d23", "d24");
    }

    @Test
    void aResultThatIsLeftOutDoesNotMoveTheCursorsAfterIt() {
        QueryConnectionFetcher fetcher = new QueryConnectionFetcher(index(4), persistence(Set.of("d1")), "ns", "Search");

        Connection<Map<String, Object>> page = fetcher.search("text", null, SNAPSHOT, Range.first(4));

        assertThat(ids(page)).containsExactly("d0", "d2", "d3");
        assertThat(page.getEdges()).extracting(edge -> QueryConnectionCursor.fromValue(edge.getCursor().getValue()).getIndex())
                .containsExactly(0L, 2L, 3L);
    }

    private static List<String> ids(Connection<Map<String, Object>> connection) {
        return connection.getEdges().stream().map(Edge::getNode)
                .map(node -> ((DocumentKey) node.get("__graphql_internal_document_key")).id()).toList();
    }

    /**
     * An index whose result for any text is the documents d0 to d{total - 1}, in that order.
     */
    private static SearchIndex index(long total) {
        return new SearchIndex() {
            @Override
            public Single<SearchResponse> search(String query, Set<String> typeFilter, long from, long size) {
                List<SearchResult> results = LongStream.range(from, Math.min(total, from + size))
                        .mapToObj(place -> new SearchResult(new DocumentKey("ns", "Thing", "d" + place, SNAPSHOT)))
                        .toList();
                return Single.just(new SearchResponse(total, results, from, size));
            }

            @Override
            public Completable createOrOverwrite(JsonDocument document) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Completable createOrOverwrite(Collection<JsonDocument> documents) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Completable delete(JsonDocument document) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Completable deleteAll() {
                throw new UnsupportedOperationException();
            }
        };
    }

    /**
     * A store that holds every document but those named, as they are at the snapshot.
     */
    private static RxJsonPersistence persistence(Set<String> missing) {
        Transaction transaction = (Transaction) Proxy.newProxyInstance(QueryConnectionFetcherTest.class.getClassLoader(),
                new Class<?>[]{Transaction.class}, (_, _, _) -> null);
        return (RxJsonPersistence) Proxy.newProxyInstance(QueryConnectionFetcherTest.class.getClassLoader(),
                new Class<?>[]{RxJsonPersistence.class}, (_, method, args) -> switch (method.getName()) {
                    case "createTransaction" -> transaction;
                    case "readDocument" -> {
                        String id = (String) args[4];
                        yield missing.contains(id)
                                ? Maybe.empty()
                                : Maybe.just(new JsonDocument(new DocumentKey("ns", (String) args[3], id, SNAPSHOT),
                                Map.<String, Object>of("name", id)));
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
