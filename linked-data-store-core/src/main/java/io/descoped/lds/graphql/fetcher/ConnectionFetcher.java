package io.descoped.lds.graphql.fetcher;

import graphql.relay.Connection;
import graphql.relay.DefaultConnectionCursor;
import graphql.relay.DefaultEdge;
import graphql.relay.Edge;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.Range;
import io.descoped.lds.graphql.GraphQLContext;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static java.lang.String.format;

public abstract class ConnectionFetcher<T> implements DataFetcher<Connection<T>> {

    /*
     * The key of a document, in the map GraphQL reads the document from; an embedded object has none.
     */
    static final String DOCUMENT_KEY = "__graphql_internal_document_key";

    private static final String AFTER_ARG_NAME = "after";
    private static final String BEFORE_ARG_NAME = "before";
    private static final String FIRST_ARG_NAME = "first";
    private static final String LAST_ARG_NAME = "last";

    private static String getAfterFrom(DataFetchingEnvironment environment) {
        return environment.getArgument(AFTER_ARG_NAME);
    }

    private static String getBeforeFrom(DataFetchingEnvironment environment) {
        return environment.getArgument(BEFORE_ARG_NAME);
    }

    private static ZonedDateTime getSnapshotFrom(DataFetchingEnvironment environment) {
        GraphQLContext context = environment.getGraphQlContext().get(GraphQLContext.class);
        return context.getSnapshot();
    }

    private static Integer getLastFrom(DataFetchingEnvironment environment) {
        Integer last = environment.getArgument(LAST_ARG_NAME);
        if (last != null && last < 0) {
            throw new IllegalArgumentException(format("The page size must not be negative: 'last'=%s", last));
        }
        return last;
    }

    private static Integer getFirstFrom(DataFetchingEnvironment environment) {
        Integer first = environment.getArgument(FIRST_ARG_NAME);
        if (first != null && first < 0) {
            throw new IllegalArgumentException(format("The page size must not be negative: 'first'=%s", first));
        }
        return first;
    }

    protected static Edge<Map<String, Object>> toEdge(JsonDocument document) {
        Map<String, Object> map = document.toMap();
        map.put(DOCUMENT_KEY, document.key());
        return new DefaultEdge<>(map, new DefaultConnectionCursor(document.key().id())
        );
    }

    /*
     * The edge of the smallest id of a page that is not empty. A backward page lists its documents from the largest
     * id down, so that is its last edge.
     */
    static <E> Edge<E> lowest(List<Edge<E>> edges, ConnectionParameters parameters) {
        return parameters.getRange().isBackward() ? edges.getLast() : edges.getFirst();
    }

    /*
     * The edge of the largest id of a page that is not empty: the first edge of a backward page.
     */
    static <E> Edge<E> highest(List<Edge<E>> edges, ConnectionParameters parameters) {
        return parameters.getRange().isBackward() ? edges.getFirst() : edges.getLast();
    }

    @Override
    public Connection<T> get(DataFetchingEnvironment environment) throws Exception {
        return getConnection(environment, parameters(environment));
    }

    /*
     * The snapshot and the range a connection field is asked for.
     */
    static ConnectionParameters parameters(DataFetchingEnvironment environment) {
        return new ConnectionParameters(getSnapshotFrom(environment), getAfterFrom(environment),
                getBeforeFrom(environment), getLastFrom(environment), getFirstFrom(environment));
    }

    abstract Connection<T> getConnection(DataFetchingEnvironment environment, ConnectionParameters connectionParameters);

    public static class ConnectionParameters {
        private final ZonedDateTime snapshot;
        private final String after;
        private final String before;
        private final Integer last;
        private final Integer first;

        private ConnectionParameters(ZonedDateTime snapshot, String after, String before, Integer last, Integer first) {
            this.snapshot = snapshot;
            this.after = after;
            this.before = before;
            this.last = last;
            this.first = first;
        }

        public ZonedDateTime getSnapshot() {
            return snapshot;
        }

        public String getAfter() {
            return after;
        }

        public String getBefore() {
            return before;
        }

        public Integer getLast() {
            return last;
        }

        public Integer getFirst() {
            return first;
        }

        public Range<String> getRange() {
            if (first != null) {
                return Range.firstBetween(first, after, before);
            } else if (last != null) {
                return Range.lastBetween(last, after, before);
            } else {
                return Range.between(after, before);
            }
        }
    }
}
