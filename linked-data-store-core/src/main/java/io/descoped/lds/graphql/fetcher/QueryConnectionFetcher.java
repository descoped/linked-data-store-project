package io.descoped.lds.graphql.fetcher;

import graphql.relay.Connection;
import graphql.relay.ConnectionCursor;
import graphql.relay.DefaultConnection;
import graphql.relay.DefaultEdge;
import graphql.relay.DefaultPageInfo;
import graphql.relay.Edge;
import graphql.relay.PageInfo;
import graphql.schema.DataFetchingEnvironment;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.Range;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.search.SearchResponse;
import io.descoped.lds.api.search.SearchResult;
import io.descoped.lds.graphql.GraphQLContext;
import io.reactivex.rxjava3.core.Maybe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.LongSupplier;

/**
 * ConnectionFetcher that gets the data from {@link SearchIndex}.
 */
public class QueryConnectionFetcher extends ConnectionFetcher<Map<String, Object>> {

    private static final Logger LOG = LoggerFactory.getLogger(QueryConnectionFetcher.class);

    /**
     * The size of a page when the query gives neither {@code first} nor {@code last}.
     */
    static final int DEFAULT_PAGE_SIZE = 10;

    /**
     * The largest page: a larger {@code first} or {@code last} is taken as this.
     */
    static final int MAX_PAGE_SIZE = 100;

    private final SearchIndex searchIndex;
    private final RxJsonPersistence persistence;
    private final String nameSpace;
    private final String entity;

    /**
     * Create a new instance
     *
     * @param searchIndex the searchIndex to use.
     * @param persistence the persistence provider to use
     * @param nameSpace   the namespace.
     * @param entity      the entity name.
     */
    public QueryConnectionFetcher(SearchIndex searchIndex, RxJsonPersistence persistence, String nameSpace, String entity) {
        this.searchIndex = Objects.requireNonNull(searchIndex);
        this.persistence = Objects.requireNonNull(persistence);
        this.nameSpace = Objects.requireNonNull(nameSpace);
        this.entity = Objects.requireNonNull(entity);
    }

    @Override
    Connection<Map<String, Object>> getConnection(DataFetchingEnvironment environment, ConnectionParameters connectionParameters) {
        GraphQLContext context = environment.getGraphQlContext().get(GraphQLContext.class);
        return search(environment.getArgument("query"), environment.getArgument("filter"), context.getSnapshot(),
                connectionParameters.getRange());
    }

    /**
     * Internal class for converting a {@link Range} to index based pagination.
     */
    static class IndexBasedRange {
        private final long from;
        private final long size;

        public IndexBasedRange(long from, long size) {
            this.from = from;
            this.size = size;
        }

        public long getFrom() {
            return from;
        }

        public long getSize() {
            return size;
        }

        /**
         * Converts a relay style range to the places of the results to ask the index for. A cursor is the place of
         * its result, and {@code after} and {@code before} leave their own result out. {@code first} and
         * {@code last} are at most {@link #MAX_PAGE_SIZE}; with neither, a page is {@link #DEFAULT_PAGE_SIZE}.
         *
         * @param range the relay style range
         * @param total the number of results in all, asked for only by {@code last} without {@code before}
         * @return the place of the first result and the number of results
         */
        static IndexBasedRange fromRange(Range<String> range, LongSupplier total) {
            long start = range.hasAfter() ? getIndex(range.getAfter()) + 1 : 0;
            if (range.hasLast()) {
                long end = range.hasBefore() ? getIndex(range.getBefore()) : total.getAsLong();
                long from = Math.max(start, end - Math.min(range.getLast(), MAX_PAGE_SIZE));
                return new IndexBasedRange(from, Math.max(0, end - from));
            }
            long size = range.hasFirst() ? Math.min(range.getFirst(), MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
            if (range.hasBefore()) {
                size = Math.min(size, Math.max(0, getIndex(range.getBefore()) - start));
            }
            return new IndexBasedRange(start, size);
        }

        static long getIndex(String cursorValue) {
            return QueryConnectionCursor.fromValue(cursorValue).getIndex();
        }

    }

    Connection<Map<String, Object>> search(String query, List<String> typeFilter,
                                                   ZonedDateTime snapshot, Range<String> range) {
        HashSet<String> filter = typeFilter != null ? new HashSet<>(typeFilter) : null;
        IndexBasedRange settings = IndexBasedRange.fromRange(range,
                () -> searchIndex.search(query, filter, 0, 0).blockingGet().getTotalHits());
        SearchResponse response = searchIndex.search(query, filter, settings.from, settings.size).blockingGet();

        LOG.debug("Search query '{}' resulted in {} hits from search settings. Fetching results from {} to {}", query,
                response.getTotalHits(), settings.from, settings.from + settings.size);
        // The cursor of an edge is the place of its result in the index, also when a result before it is left out
        List<Edge<Map<String, Object>>> edges = new ArrayList<>();
        long place = settings.from;
        for (SearchResult result : response.getResults()) {
            QueryConnectionCursor cursor = new QueryConnectionCursor(place++);
            readDocument(result.getDocumentKey(), snapshot).ifPresent(document -> edges.add(toEdge(document, cursor)));
        }

        if (place == settings.from) {
            LOG.debug("Search query resulted in 0 documents.");
            PageInfo pageInfo = new DefaultPageInfo(null, null, false, false);
            return new DefaultConnection<>(Collections.emptyList(), pageInfo);
        }

        boolean hasPrevious = settings.from > 0;
        boolean hasNext = settings.from + settings.size < response.getTotalHits();

        // When every result of the page is gone at the snapshot, the cursors are the places of the page in the index,
        // so that the page after it can still be asked for.
        PageInfo pageInfo = new DefaultPageInfo(
                edges.isEmpty() ? new QueryConnectionCursor(settings.from) : edges.getFirst().getCursor(),
                edges.isEmpty() ? new QueryConnectionCursor(place - 1) : edges.getLast().getCursor(),
                hasPrevious,
                hasNext
        );

        return new DefaultConnection<>(
                edges,
                pageInfo
        );
    }

    private static Edge<Map<String, Object>> toEdge(JsonDocument document, ConnectionCursor connectionCursor) {
        Map<String, Object> map = document.toMap();
        map.put("__graphql_internal_document_key", document.key());
        return new DefaultEdge<>(map, connectionCursor);
    }

    private Optional<JsonDocument> readDocument(DocumentKey documentKey, ZonedDateTime snapshot) {
        try (Transaction tx = persistence.createTransaction(true)) {
            Maybe<JsonDocument> jsonDocumentMaybe = persistence.readDocument(tx, snapshot, documentKey.namespace(),
                    documentKey.entity(), documentKey.id());
            JsonDocument jsonDocument = jsonDocumentMaybe.blockingGet();
            if (jsonDocument == null) {
                LOG.error("Cound not find document for key {}", documentKey);
                return Optional.empty();
            }
            return Optional.of(jsonDocument);
        }
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", QueryConnectionFetcher.class.getSimpleName() + "[", "]")
                .add("searchIndex=" + searchIndex)
                .add("nameSpace='" + nameSpace + "'")
                .add("entity='" + entity + "'")
                .toString();
    }
}
