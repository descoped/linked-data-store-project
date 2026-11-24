package io.descoped.lds.graphql.fetcher;

import graphql.relay.Connection;
import graphql.relay.Edge;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.Range;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.search.IndexEntry;
import io.descoped.lds.api.search.SearchHit;
import io.descoped.lds.api.search.SearchPage;
import io.descoped.lds.api.search.SearchQuery;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.ZonedDateTime;
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

        Connection<Map<String, Object>> page1 = page(fetcher, Range.first(2));
        Connection<Map<String, Object>> page2 = page(fetcher,
                Range.firstAfter(2, page1.getPageInfo().getEndCursor().getValue()));
        Connection<Map<String, Object>> page3 = page(fetcher,
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

        assertThat(ids(page(fetcher, Range.last(2)))).containsExactly("d23", "d24");
    }

    @Test
    void aResultThatIsLeftOutDoesNotMoveTheCursorsAfterIt() {
        QueryConnectionFetcher fetcher = new QueryConnectionFetcher(index(4), persistence(Set.of("d1")), "ns", "Search");

        Connection<Map<String, Object>> page = page(fetcher, Range.first(4));

        assertThat(ids(page)).containsExactly("d0", "d2", "d3");
        assertThat(page.getEdges()).extracting(edge -> QueryConnectionCursor.fromValue(edge.getCursor().getValue()).getIndex())
                .containsExactly(0L, 2L, 3L);
    }

    @Test
    void aPageWhoseResultsAreAllLeftOutStillLeadsToTheNextPage() {
        QueryConnectionFetcher fetcher = new QueryConnectionFetcher(index(4), persistence(Set.of("d0", "d1")), "ns", "Search");

        Connection<Map<String, Object>> page = page(fetcher, Range.first(2));

        assertThat(page.getEdges()).isEmpty();
        assertThat(page.getPageInfo().isHasNextPage()).isTrue();
        assertThat(page.getPageInfo().isHasPreviousPage()).isFalse();
        String endCursor = page.getPageInfo().getEndCursor().getValue();
        assertThat(QueryConnectionCursor.fromValue(endCursor).getIndex()).isEqualTo(1L);
        assertThat(ids(page(fetcher, Range.firstAfter(2, endCursor)))).containsExactly("d2", "d3");
        // past the end of the result there is no page
        Connection<Map<String, Object>> beyond = page(fetcher, Range.firstAfter(2,
                new QueryConnectionCursor(3).getValue()));
        assertThat(beyond.getEdges()).isEmpty();
        assertThat(beyond.getPageInfo().isHasNextPage()).isFalse();
    }

    /* The page the fetcher gives for the words "text" at the snapshot. */
    private static Connection<Map<String, Object>> page(QueryConnectionFetcher fetcher, Range<String> range) {
        return fetcher.search("text", null, false, SNAPSHOT, range).blockingGet();
    }

    private static List<String> ids(Connection<Map<String, Object>> connection) {
        return connection.getEdges().stream().map(Edge::getNode)
                .map(node -> ((DocumentKey) node.get("__graphql_internal_document_key")).id()).toList();
    }

    /**
     * An index whose result for any text is the documents d0 to d{total - 1}, in that order, each at a version a
     * second before the one before it.
     */
    private static SearchIndex index(long total) {
        return new SearchIndex() {
            @Override
            public Single<SearchPage> search(SearchQuery query) {
                List<SearchHit> hits = LongStream.range(query.from(), Math.min(total, query.from() + query.size()))
                        .mapToObj(place -> new SearchHit(new DocumentKey("ns", "Thing", "d" + place,
                                SNAPSHOT.minusSeconds(place + 1))))
                        .toList();
                return Single.just(new SearchPage(total, hits));
            }

            @Override
            public Completable put(List<IndexEntry> entries) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Completable removeOlderThan(String namespace, Duration age) {
                return Completable.complete();
            }

            @Override
            public Completable refresh() {
                return Completable.complete();
            }

            @Override
            public Completable health() {
                return Completable.complete();
            }
        };
    }

    @Test
    void aHitIsReadAtTheVersionThatMatched() {
        QueryConnectionFetcher fetcher = new QueryConnectionFetcher(index(2), persistence(Set.of()), "ns", "Search");

        assertThat(page(fetcher, Range.first(2)).getEdges()).extracting(Edge::getNode)
                .extracting(node -> ((DocumentKey) node.get("__graphql_internal_document_key")).timestamp())
                .containsExactly(SNAPSHOT.minusSeconds(1), SNAPSHOT.minusSeconds(2));
    }

    /**
     * A store that holds every document but those named, under the version it is read at.
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
                                : Maybe.just(new JsonDocument(new DocumentKey("ns", (String) args[3], id,
                                (java.time.ZonedDateTime) args[1]),
                                Map.<String, Object>of("name", id)));
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
