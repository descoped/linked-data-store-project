package io.descoped.lds.api.persistence.reactivex;

import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonToFlattenedDocument;
import io.descoped.lds.api.persistence.streaming.Fragment;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static io.descoped.lds.api.persistence.json.JsonTools.mapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reads along a link that the bridge makes from the reads of fragments: the documents a document links to
 * ({@link RxJsonPersistenceBridge#readTargetDocuments}) and the documents that link to a document
 * ({@link RxJsonPersistenceBridge#readSourceDocuments}). The fragments come from a store that holds a few documents
 * and records the {@code find} it is asked.
 */
class RxJsonPersistenceBridgeLinkTest {

    private static final int CAPACITY = 128;
    private static final ZonedDateTime TIME = ZonedDateTime.parse("2020-01-01T00:00:00Z");

    private final Map<String, JsonDocument> documents = new HashMap<>();
    private final List<Object[]> finds = new ArrayList<>();
    private final RxJsonPersistenceBridge bridge = new RxJsonPersistenceBridge(store(), CAPACITY);

    @Test
    void theTargetsAreTheDocumentsOfTheTargetTypeThatTheLinksAtThePathName() {
        document("Company", "acme", company("Acme"));
        document("Company", "nordlys", company("Nordlys"));
        ObjectNode ola = mapper.createObjectNode();
        ola.putArray("employers").add("/Company/nordlys").add("/Person/kari").add("not a link").add("/Company/acme");
        document("Person", "ola", ola);

        LinkQuery query = new LinkQuery("ns", "Person", "ola", JsonNavigationPath.from("$.employers[]"), "Company");

        assertThat(bridge.readTargetDocuments(null, TIME, query, Range.unbounded()).blockingIterable())
                .extracting(document -> document.key().id())
                .containsExactly("acme", "nordlys");
        assertThat(bridge.readTargetDocuments(null, TIME, query, Range.last(1)).blockingIterable())
                .extracting(document -> document.key().id())
                .containsExactly("nordlys");
    }

    @Test
    void theSourcesAreFoundByTheLinkToTheDocumentAtThePath() {
        ObjectNode ola = mapper.createObjectNode();
        ola.put("employer", "/Company/acme");
        document("Person", "ola", ola);

        LinkQuery query = new LinkQuery("ns", "Company", "acme", JsonNavigationPath.from("$.employer"), "Person");

        assertThat(bridge.readSourceDocuments(null, TIME, query, Range.unbounded()).blockingIterable())
                .extracting(document -> document.key().id())
                .containsExactly("ola");
        assertThat(finds).singleElement().satisfies(find -> {
            assertThat(find[3]).isEqualTo("Person");
            assertThat(find[4]).isEqualTo("$.employer");
            assertThat(new String((byte[]) find[5], StandardCharsets.UTF_8)).isEqualTo("/Company/acme");
        });
    }

    private static ObjectNode company(String name) {
        ObjectNode company = mapper.createObjectNode();
        company.put("name", name);
        return company;
    }

    private void document(String entity, String id, ObjectNode json) {
        documents.put(entity + "/" + id, new JsonDocument(new DocumentKey("ns", entity, id, TIME), json));
    }

    private Flowable<Fragment> fragmentsOf(Iterable<JsonDocument> selected) {
        TreeSet<Fragment> fragments = new TreeSet<>();
        for (JsonDocument document : selected) {
            DocumentKey key = document.key();
            new JsonToFlattenedDocument(key.namespace(), key.entity(), key.id(), key.timestamp(), document.jackson(),
                    CAPACITY).toDocument().fragmentIterator().forEachRemaining(fragments::add);
        }
        return Flowable.fromIterable(fragments);
    }

    /** A store with {@code read} of one document and {@code find}, which gives every document of the entity type */
    private RxPersistence store() {
        return (RxPersistence) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RxPersistence.class},
                (_, method, args) -> switch (method.getName()) {
                    case "read" -> fragmentsOf(documents.containsKey(args[3] + "/" + args[4])
                            ? List.of(documents.get(args[3] + "/" + args[4])) : List.of());
                    case "find" -> {
                        finds.add(args);
                        yield fragmentsOf(documents.values().stream()
                                .filter(document -> document.key().entity().equals(args[3])).toList());
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
