package io.descoped.lds.graphql.fetcher;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.graphql.GraphQLContext;
import io.reactivex.rxjava3.core.Maybe;
import io.undertow.server.HttpServerExchange;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A single link: the document it points at, read as of the snapshot of the request, or null.
 */
class PersistenceLinkFetcherTest {

    private static final ZonedDateTime SNAPSHOT = ZonedDateTime.parse("2026-11-22T12:00:00Z");

    private final List<String> read = new ArrayList<>();

    /**
     * A store that holds the pets p1 and p2, and writes down what it is asked for.
     */
    private RxJsonPersistence persistence() {
        Transaction transaction = (Transaction) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Transaction.class}, (_, _, _) -> null);
        return (RxJsonPersistence) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RxJsonPersistence.class}, (_, method, args) -> switch (method.getName()) {
                    case "createTransaction" -> transaction;
                    case "readDocument" -> {
                        String entity = (String) args[3];
                        String id = (String) args[4];
                        read.add(args[2] + "/" + entity + "/" + id + "@" + args[1]);
                        yield id.startsWith("p")
                                ? Maybe.just(new JsonDocument(new DocumentKey("ns", entity, id, SNAPSHOT), Map.<String, Object>of("name", "pet " + id)))
                                : Maybe.empty();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static DataFetchingEnvironment environment(Map<String, Object> source) {
        GraphQLContext context = new GraphQLContext() {
            @Override
            public HttpServerExchange getExchange() {
                return null;
            }

            @Override
            public ZonedDateTime getSnapshot() {
                return SNAPSHOT;
            }
        };
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .source(source)
                .graphQLContext(graphql.GraphQLContext.of(Map.of(GraphQLContext.class, context)))
                .build();
    }

    private final PersistenceLinkFetcher fetcher = new PersistenceLinkFetcher(persistence(), "ns", "favorite", "Pet");

    @Test
    void theDocumentTheLinkPointsAtIsReadAsOfTheSnapshotOfTheRequest() {
        Map<String, Object> pet = fetcher.get(environment(Map.of("favorite", "/Pet/p1")));

        assertThat(pet).containsEntry("name", "pet p1");
        assertThat(((DocumentKey) pet.get("__graphql_internal_document_key")).id()).isEqualTo("p1");
        assertThat(read).containsExactly("ns/Pet/p1@" + SNAPSHOT);
    }

    @Test
    void aDocumentWithoutTheLinkLinksToNothing() {
        assertThat(fetcher.get(environment(new HashMap<>()))).isNull();
        assertThat(read).isEmpty();
    }

    @Test
    void aLinkFieldThatHoldsSomethingElseLinksToNothing() {
        assertThat(fetcher.get(environment(Map.of("favorite", 42)))).isNull();
        assertThat(fetcher.get(environment(Map.of("favorite", List.of("/Pet/p1"))))).isNull();
        assertThat(read).isEmpty();
    }

    @Test
    void aLinkToADocumentThatDoesNotExistLinksToNothing() {
        assertThat(fetcher.get(environment(Map.of("favorite", "/Pet/gone")))).isNull();
        assertThat(read).containsExactly("ns/Pet/gone@" + SNAPSHOT);
    }

    @Test
    void aLinkToAnotherEntityLinksToNothing() {
        assertThat(fetcher.get(environment(Map.of("favorite", "/House/h1")))).isNull();
        assertThat(read).isEmpty();
    }
}
