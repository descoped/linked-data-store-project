package io.descoped.lds.core.persistence.neo4j;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.api.specification.SpecificationElementType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import static io.descoped.lds.api.persistence.json.JsonTools.mapper;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.arrayNode;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.arrayRefNode;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.createSpecificationAndRoot;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.numericNode;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.objectNode;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.refNode;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.stringNode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents of different shapes, written to a real Neo4j and read back.
 */
class Neo4jRoundTripTest {

    private static final ZonedDateTime VERSION = ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneId.of("Etc/UTC"));

    private static final Specification SPECIFICATION = createSpecificationAndRoot(Set.of(
            objectNode(SpecificationElementType.MANAGED, "MixMapAndArray", Set.of(
                    arrayNode("child", objectNode("[]", Set.of(
                            stringNode("name"),
                            numericNode("born"),
                            arrayNode("friend", objectNode("[]", Set.of(
                                    stringNode("since"),
                                    stringNode("link")
                            )))
                    ))),
                    objectNode("name", Set.of(
                            stringNode("first"),
                            stringNode("last")
                    ))
            )),
            objectNode(SpecificationElementType.MANAGED, "SimpleMap", Set.of(
                    stringNode("one"),
                    stringNode("two"),
                    stringNode("three")
            )),
            objectNode(SpecificationElementType.MANAGED, "DeeplyNestedMap", Set.of(
                    objectNode("the", Set.of(
                            objectNode("very", Set.of(
                                    objectNode("nested", Set.of(
                                            stringNode("one"),
                                            stringNode("two"),
                                            stringNode("three")
                                    ))
                            ))
                    ))
            )),
            objectNode(SpecificationElementType.MANAGED, "SimpleArray", Set.of(
                    arrayNode("the", stringNode("[]"))
            )),
            objectNode(SpecificationElementType.MANAGED, "SimpleRef", Set.of(
                    refNode("foo", Set.of("Target"))
            )),
            objectNode(SpecificationElementType.MANAGED, "NestedSimpleRef", Set.of(
                    objectNode("here", Set.of(
                            refNode("foo", Set.of("Target"))
                    ))
            )),
            objectNode(SpecificationElementType.MANAGED, "ArrayRef", Set.of(
                    arrayRefNode("foo", Set.of("Target"), stringNode("[]"))
            )),
            objectNode(SpecificationElementType.MANAGED, "NestedArrayRef", Set.of(
                    objectNode("here", Set.of(
                            arrayRefNode("foo", Set.of("Target"), stringNode("[]"))
                    ))
            )),
            objectNode(SpecificationElementType.MANAGED, "ArrayOfObjects", Set.of(
                    arrayNode("the", objectNode("[]", Set.of(
                            stringNode("one"),
                            stringNode("two")
                    ))),
                    stringNode("name")
            )),
            objectNode(SpecificationElementType.MANAGED, "DeeplyNestedArray", Set.of(
                    arrayNode("the", arrayNode("[]", arrayNode("[]", stringNode("[]"))))
            )),
            objectNode(SpecificationElementType.MANAGED, "MissingAndNull", Set.of(
                    arrayNode("the", arrayNode("[]", objectNode("[]", Set.of(
                            stringNode("key"),
                            stringNode("value")
                    ))))
            )),
            objectNode(SpecificationElementType.MANAGED, "Target", Set.of(
                    stringNode("name")
            ))
    ));

    private static RxJsonPersistence persistence;

    @BeforeAll
    static void start() {
        persistence = new Neo4jInitializer().initialize("round-trip", Neo4j2026.configuration(), SPECIFICATION.getManagedDomains());
    }

    @AfterAll
    static void stop() {
        if (persistence != null) {
            persistence.close();
        }
    }

    @Test
    void objectsAndArraysInEachOther() throws IOException {
        assertThat(writeAndRead("MixMapAndArray", "mix-map-and-array.json")).isEqualTo(load("mix-map-and-array.json"));
    }

    @Test
    void anObjectWithANull() throws IOException {
        assertThat(writeAndRead("SimpleMap", "simple-map.json")).isEqualTo(load("simple-map.json"));
    }

    @Test
    void objectsInObjects() throws IOException {
        assertThat(writeAndRead("DeeplyNestedMap", "deeply-nested-map.json")).isEqualTo(load("deeply-nested-map.json"));
    }

    @Test
    void anArrayOfTexts() throws IOException {
        assertThat(writeAndRead("SimpleArray", "simple-array.json")).isEqualTo(load("simple-array.json"));
    }

    @Test
    void aLink() throws IOException {
        assertThat(writeAndRead("SimpleRef", "simple-ref.json")).isEqualTo(load("simple-ref.json"));
    }

    @Test
    void aLinkInAnObject() throws IOException {
        assertThat(writeAndRead("NestedSimpleRef", "nested-simple-ref.json")).isEqualTo(load("nested-simple-ref.json"));
    }

    @Test
    void anArrayOfLinks() throws IOException {
        assertThat(writeAndRead("ArrayRef", "array-ref.json")).isEqualTo(load("array-ref.json"));
    }

    @Test
    void anArrayOfLinksInAnObject() throws IOException {
        assertThat(writeAndRead("NestedArrayRef", "nested-array-ref.json")).isEqualTo(load("nested-array-ref.json"));
    }

    @Test
    void anEmptyArray() throws IOException {
        assertThat(writeAndRead("ArrayOfObjects", "empty-array.json")).isEqualTo(load("empty-array.json"));
    }

    @Test
    void anArrayThatIsNotThere() throws IOException {
        assertThat(writeAndRead("ArrayOfObjects", "missing-array.json")).isEqualTo(load("missing-array.json"));
    }

    @Test
    void anEmptyDocument() throws IOException {
        assertThat(writeAndRead("ArrayOfObjects", "empty-document.json")).isEqualTo(load("empty-document.json"));
    }

    /**
     * A null where the specification says array is read as the null it is.
     */
    @Test
    void aNullInPlaceOfAnArrayIsReadAsNull() throws IOException {
        assertThat(writeAndRead("ArrayOfObjects", "null-array.json")).isEqualTo(load("null-array.json"));
    }

    @Test
    void arraysInArrays() throws IOException {
        assertThat(writeAndRead("DeeplyNestedArray", "deeply-nested-array.json")).isEqualTo(load("deeply-nested-array.json"));
    }

    @Test
    void objectsInArraysInArrays() throws IOException {
        assertThat(writeAndRead("MissingAndNull", "missing-and-null.json")).isEqualTo(load("missing-and-null.json"));
    }

    private static JsonNode load(String resource) throws IOException {
        try (InputStream json = ClassLoader.getSystemResourceAsStream(resource)) {
            return mapper.readTree(json);
        }
    }

    /**
     * Writes the document of a file as an entity, the name of the file as its id, and reads it back.
     */
    private static JsonNode writeAndRead(String entity, String resource) throws IOException {
        return writeAndRead(entity, resource, load(resource));
    }

    private static JsonNode writeAndRead(String entity, String id, JsonNode written) {
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.createOrOverwrite(tx, new JsonDocument(new DocumentKey("round-trip", entity, id, VERSION), written), SPECIFICATION).blockingAwait();
        }
        try (Transaction tx = persistence.createTransaction(true)) {
            JsonDocument read = persistence.readDocument(tx, VERSION, "round-trip", entity, id).blockingGet();
            return read == null ? null : read.jackson();
        }
    }
}
