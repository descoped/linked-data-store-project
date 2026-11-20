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
 * The fetcher of a list of links that is not paged reads the documents the links point at, as of the snapshot of
 * the request.
 */
class PersistenceLinksFetcherTest {

    private static final ZonedDateTime SNAPSHOT = ZonedDateTime.parse("2020-01-01T00:00:00Z");

    private final List<String> read = new ArrayList<>();

    /**
     * A store that holds the pets p1 and p2, and writes down what it is asked for.
     */
    private RxJsonPersistence persistence() {
        Transaction transaction = (Transaction) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Transaction.class}, (proxy, method, args) -> null);
        return (RxJsonPersistence) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RxJsonPersistence.class}, (proxy, method, args) -> switch (method.getName()) {
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

    @Test
    void theDocumentsTheLinksPointAtAreReadAsOfTheSnapshotOfTheRequest() {
        PersistenceLinksFetcher fetcher = new PersistenceLinksFetcher(persistence(), "ns", "pets", "Pet");
        Map<String, Object> source = Map.of("pets", List.of("/Pet/p1", "/Pet/gone", "/House/h1", "/Pet/p2"));

        List<Map<String, Object>> pets = fetcher.get(environment(source));

        // a link to a document that is not there gives null, and a link to another entity is left out
        assertThat(pets).hasSize(3);
        assertThat(pets.get(0)).containsEntry("name", "pet p1");
        assertThat(pets.get(1)).isNull();
        assertThat(pets.get(2)).containsEntry("name", "pet p2");
        assertThat(read).containsExactly(
                "ns/Pet/p1@" + SNAPSHOT,
                "ns/Pet/gone@" + SNAPSHOT,
                "ns/Pet/p2@" + SNAPSHOT);
    }

    @Test
    void aDocumentWithoutTheFieldHasNoLinks() {
        PersistenceLinksFetcher fetcher = new PersistenceLinksFetcher(persistence(), "ns", "pets", "Pet");

        assertThat(fetcher.get(environment(new HashMap<>()))).isNull();
        assertThat(read).isEmpty();
    }
}
