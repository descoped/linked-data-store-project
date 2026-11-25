package io.descoped.lds.graphql.fetcher;

import graphql.relay.Connection;
import graphql.relay.DefaultConnection;
import graphql.relay.DefaultPageInfo;
import graphql.relay.Edge;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLUnionType;
import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.IdOrder;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.LinkQuery;
import io.descoped.lds.api.persistence.reactivex.Range;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fetcher that support relay style connection parameters and type.
 */
public class PersistenceLinksConnectionFetcher extends ConnectionFetcher<Map<String, Object>> {

    // Field name containing the ids of the target.
    private final JsonNavigationPath relationPath;

    private final String sourceEntityName;

    // Target entity name.
    private final String targetEntityName;

    // Name space
    private final String nameSpace;

    private final RxJsonPersistence persistence;

    private static final Pattern LINK = Pattern.compile("/(?<entity>[^/]*)/(?<id>[^/]*)");

    public static final Comparator<JsonDocument> BY_ID = Comparator.comparing(a -> a.key().id(), IdOrder.CODE_POINTS);

    public PersistenceLinksConnectionFetcher(RxJsonPersistence persistence, String nameSpace, String sourceEntityName, JsonNavigationPath path, String targetEntityName) {
        this.persistence = Objects.requireNonNull(persistence);
        this.nameSpace = Objects.requireNonNull(nameSpace);
        this.sourceEntityName = Objects.requireNonNull(sourceEntityName);
        this.relationPath = Objects.requireNonNull(path);
        this.targetEntityName = Objects.requireNonNull(targetEntityName);
    }

    /**
     * Extracts the id from the source object in the environment.
     */
    private static String getIdFromSource(DataFetchingEnvironment environment) {
        Map<String, Object> source = environment.getSource();
        return ((DocumentKey) source.get(DOCUMENT_KEY)).id();
    }

    private List<GraphQLNamedOutputType> getConcreteTypes(GraphQLSchema graphQLSchema, String typeName) {
        GraphQLType actualType = graphQLSchema.getType(typeName);
        if (actualType instanceof GraphQLUnionType unionType) {
            return unionType.getTypes();
        } else if (actualType instanceof GraphQLInterfaceType) {
            List<GraphQLNamedOutputType> types = new ArrayList<>();
            for (GraphQLNamedType concreteType : graphQLSchema.getAllTypesAsList()) {
                if (concreteType instanceof GraphQLOutputType &&
                        graphQLSchema.isPossibleType((GraphQLNamedType) actualType, (GraphQLObjectType) concreteType)) {
                    types.add((GraphQLObjectType) concreteType);
                }
            }
            return types;
        } else if (actualType instanceof GraphQLOutputType) {
            return List.of((GraphQLNamedOutputType) actualType);
        } else {
            throw new ClassCastException(
                    String.format("the type type %sd (%s) should be a GraphQLOutputType", typeName, actualType)
            );
        }
    }

    @Override
    Connection<Map<String, Object>> getConnection(DataFetchingEnvironment environment, ConnectionParameters parameters) {
        try (Transaction tx = persistence.createTransaction(true)) {

            List<GraphQLNamedOutputType> concreteTypes = getConcreteTypes(environment.getGraphQLSchema(), targetEntityName);
            Map<String, Object> source = environment.getSource();
            if (source.get(DOCUMENT_KEY) == null) {
                // an embedded object: the path of the field is that of the type it is in, and not one from the
                // document, which may hold the type in several places
                return embeddedConnection(environment, parameters, tx, source, concreteTypes);
            }
            String sourceId = getIdFromSource(environment);

            // In cases of union type, we need to make several calls.
            List<LinkQuery> queries = new ArrayList<>();
            for (GraphQLNamedOutputType concreteType : concreteTypes) {
                queries.add(new LinkQuery(nameSpace, sourceEntityName, sourceId, relationPath, concreteType.getName()));
            }
            Range<String> range = parameters.getRange();
            Flowable<JsonDocument> documents = Flowable.empty();
            for (LinkQuery query : queries) {
                documents = Flowable.merge(documents,
                        persistence.readTargetDocuments(tx, parameters.getSnapshot(), query, range));
            }
            // The page of several reads is taken in the order of the ids, as one read gives it: each read has its
            // limit, and the reads answer in any order.
            if (queries.size() > 1) {
                documents = documents.sorted(range.isBackward() ? BY_ID.reversed() : BY_ID);
                if (range.isLimited()) {
                    documents = documents.take(range.getLimit());
                }
            }
            List<Edge<Map<String, Object>>> edges = documents.map(document -> toEdge(document)).toList()
                    .blockingGet();

            if (edges.isEmpty()) {
                return EMPTY;
            }

            boolean hasPrevious = true;
            if (environment.getSelectionSet().contains("pageInfo/hasPreviousPage")) {
                hasPrevious = anyLinked(tx, parameters, queries,
                        Range.lastBefore(1, lowest(edges, parameters).getCursor().getValue()));
            }

            boolean hasNext = true;
            if (environment.getSelectionSet().contains("pageInfo/hasNextPage")) {
                hasNext = anyLinked(tx, parameters, queries,
                        Range.firstAfter(1, highest(edges, parameters).getCursor().getValue()));
            }

            return connection(edges, hasPrevious, hasNext);
        }
    }

    private static final Connection<Map<String, Object>> EMPTY = new DefaultConnection<>(Collections.emptyList(),
            new DefaultPageInfo(null, null, false, false));

    private static Connection<Map<String, Object>> connection(List<Edge<Map<String, Object>>> edges,
                                                              boolean hasPrevious, boolean hasNext) {
        return new DefaultConnection<>(edges, new DefaultPageInfo(edges.getFirst().getCursor(),
                edges.getLast().getCursor(), hasPrevious, hasNext));
    }

    /*
     * A link of the field of an embedded object: the entity type and the id it names.
     */
    private record Link(String entity, String id) {
    }

    /*
     * The page of the links an embedded object holds in its field, read as a page of the links of a document is:
     * the links to the target types, by their ids, in the range. A link to a document that does not exist at the
     * snapshot is left out, and the page is filled with the next.
     */
    private Connection<Map<String, Object>> embeddedConnection(DataFetchingEnvironment environment,
                                                               ConnectionParameters parameters, Transaction tx,
                                                               Map<String, Object> source,
                                                               List<GraphQLNamedOutputType> concreteTypes) {
        Set<String> types = new HashSet<>();
        concreteTypes.forEach(type -> types.add(type.getName()));
        List<Link> links = new ArrayList<>();
        if (source.get(environment.getFieldDefinition().getName()) instanceof List<?> values) {
            for (Object value : values) {
                Matcher matcher = value instanceof String text ? LINK.matcher(text) : null;
                if (matcher != null && matcher.matches() && types.contains(matcher.group("entity"))) {
                    links.add(new Link(matcher.group("entity"), matcher.group("id")));
                }
            }
        }
        Range<String> range = parameters.getRange();
        Comparator<Link> order = Comparator.comparing(Link::id, IdOrder.CODE_POINTS);
        links.sort(range.isBackward() ? order.reversed() : order);

        Flowable<JsonDocument> documents = existing(tx, parameters, links.stream().filter(link ->
                (!range.hasAfter() || IdOrder.compare(link.id(), range.getAfter()) > 0)
                        && (!range.hasBefore() || IdOrder.compare(link.id(), range.getBefore()) < 0)).toList());
        if (range.isLimited()) {
            documents = documents.take(range.getLimit());
        }
        return documents.map(document -> toEdge(document)).toList().flatMap(edges -> {
            if (edges.isEmpty()) {
                return Single.just(EMPTY);
            }
            String lowest = lowest(edges, parameters).getCursor().getValue();
            String highest = highest(edges, parameters).getCursor().getValue();
            return Single.zip(
                    existing(tx, parameters, links.stream()
                            .filter(link -> IdOrder.compare(link.id(), lowest) < 0).toList()).isEmpty(),
                    existing(tx, parameters, links.stream()
                            .filter(link -> IdOrder.compare(link.id(), highest) > 0).toList()).isEmpty(),
                    (noneBefore, noneAfter) -> connection(edges, !noneBefore, !noneAfter));
        }).blockingGet();
    }

    /*
     * The documents the links name that exist at the snapshot, in the order of the links.
     */
    private Flowable<JsonDocument> existing(Transaction tx, ConnectionParameters parameters, List<Link> links) {
        return Flowable.fromIterable(links).concatMapMaybe(link ->
                persistence.readDocument(tx, parameters.getSnapshot(), nameSpace, link.entity(), link.id()));
    }

    /*
     * Whether the source links to a document of any of the entity types in the range.
     */
    private boolean anyLinked(Transaction tx, ConnectionParameters parameters, List<LinkQuery> queries,
                              Range<String> range) {
        Flowable<JsonDocument> linked = Flowable.empty();
        for (LinkQuery query : queries) {
            linked = linked.concatWith(persistence.readTargetDocuments(tx, parameters.getSnapshot(), query, range));
        }
        return linked.isEmpty().map(wasEmpty -> !wasEmpty).blockingGet();
    }
}
