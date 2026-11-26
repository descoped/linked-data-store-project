package io.descoped.lds.core.persistence.neo4j;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.IdOrder;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.api.specification.SpecificationElement;
import io.descoped.lds.api.specification.SpecificationElementType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.arrayRefNode;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.createSpecificationAndRoot;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.objectNode;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.refNode;
import static io.descoped.lds.core.persistence.neo4j.SpecificationBuilder.stringNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a document is as the parameters of the statement that writes it, and the statement. No Neo4j is needed.
 */
class Neo4jDocumentWriterTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Specification SPECIFICATION = createSpecificationAndRoot(Set.of(
            objectNode(SpecificationElementType.MANAGED, "Person", Set.of(
                    stringNode("name"),
                    refNode("home", Set.of("House", "Boat")),
                    objectNode("history", Set.of(
                            arrayRefNode("lived", Set.of("House"), stringNode("[]"))
                    ))
            )),
            objectNode(SpecificationElementType.MANAGED, "House", Set.of(stringNode("street"))),
            objectNode(SpecificationElementType.MANAGED, "Boat", Set.of(stringNode("name")))
    ));

    private static final SpecificationElement PERSON = SPECIFICATION.getRootElement().getProperties().get("Person");

    private static final List<String> TARGETS = List.of("Boat", "House");

    private static Map<String, Object> parametersOf(String json) throws Exception {
        JsonNode document = mapper.readTree(json.replace('\'', '"'));
        DocumentKey key = new DocumentKey("ns", "Person", "p1", ZonedDateTime.of(2020, 1, 1, 12, 0, 0, 0, ZoneId.of("Europe/Oslo")));
        return Neo4jDocumentWriter.documentParameters(new JsonDocument(key, document), PERSON, TARGETS);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodes(Map<String, Object> parameters) {
        return (List<Map<String, Object>>) parameters.get("nodes");
    }

    @SuppressWarnings("unchecked")
    private static List<List<Map<String, Object>>> links(Map<String, Object> parameters) {
        return (List<List<Map<String, Object>>>) parameters.get("links");
    }

    @Test
    void theEntitiesADocumentMayLinkToAreThoseOfEveryLinkFieldSorted() {
        assertThat(Neo4jDocumentWriter.linkTargets(PERSON)).containsExactly("Boat", "House");
        assertThat(Neo4jDocumentWriter.linkTargets(SPECIFICATION.getRootElement().getProperties().get("House"))).isEmpty();
    }

    @Test
    void aDocumentIsItsKeyWithTheVersionInUtc() throws Exception {
        Map<String, Object> parameters = parametersOf("{}");

        assertThat(parameters).containsEntry("namespace", "ns").containsEntry("id", "p1")
                .containsEntry("version", ZonedDateTime.of(2020, 1, 1, 11, 0, 0, 0, ZoneId.of("Etc/UTC")));
        assertThat(nodes(parameters)).isEmpty();
        assertThat(links(parameters)).containsExactly(List.of(), List.of());
    }

    @Test
    void theNodesAreInTheOrderOfTheDocumentAndEachNamesItsParentByItsPlace() throws Exception {
        Map<String, Object> parameters = parametersOf("{'name':'N','more':{'list':[true,{'deep':1.5}],'nothing':null},'count':7}");

        assertThat(nodes(parameters)).containsExactly(
                Map.of("parent", 0, "name", "name", "type", "string", "path", "$.name", "value", "N"),
                Map.of("parent", 0, "name", "more", "type", "map", "path", "$.more"),
                Map.of("parent", 2, "name", "list", "type", "array", "path", "$.more.list"),
                Map.of("parent", 3, "name", "[]", "index", 0, "type", "boolean", "path", "$.more.list[]", "value", true),
                Map.of("parent", 3, "name", "[]", "index", 1, "type", "map", "path", "$.more.list[]"),
                Map.of("parent", 5, "name", "deep", "type", "number", "path", "$.more.list[].deep", "value", 1.5),
                Map.of("parent", 2, "name", "nothing", "type", "null", "path", "$.more.nothing"),
                Map.of("parent", 0, "name", "count", "type", "number", "path", "$.count", "value", 7L));
    }

    /**
     * A node is joined to its parent by the name of its field as it is, and its path has the name with an escape
     * before each character that is the syntax of a path (FieldName), as every provider writes a path.
     */
    @Test
    void aNameIsThePathOfItsNodeWithTheSyntaxOfAPathEscaped() throws Exception {
        Map<String, Object> parameters = parametersOf("{'a.b':{'x[1]':['v']},'[]':1}");

        assertThat(nodes(parameters)).extracting(node -> node.get("name") + " " + node.get("path")).containsExactly(
                "a.b $.a\\.b",
                "x[1] $.a\\.b.x\\[1\\]",
                "[] $.a\\.b.x\\[1\\][]",
                "[] $.\\[\\]");
    }

    @Test
    void aTextInALinkFieldThatNamesAnEntityItMayLinkToIsALink() throws Exception {
        Map<String, Object> parameters = parametersOf("{'home':'/Boat/b1','history':{'lived':['/House/h1','/House/h2']}}");

        assertThat(links(parameters).get(0)).containsExactly(Map.of("parent", 0, "name", "home", "id", "b1", "idOrder", "b1"));
        assertThat(links(parameters).get(1)).containsExactly(
                Map.of("parent", 2, "name", "[]", "index", 0, "id", "h1", "idOrder", "h1"),
                Map.of("parent", 2, "name", "[]", "index", 1, "id", "h2", "idOrder", "h2"));
        assertThat(nodes(parameters)).extracting(node -> node.get("path")).containsExactly("$.history", "$.history.lived");
    }

    @Test
    void anythingElseInALinkFieldIsTheValueItIs() throws Exception {
        // no link, a link to an entity the field may not link to, a link without its first slash, a link without an
        // id, a number, nothing; and a link in a field that is no link field
        Map<String, Object> parameters = parametersOf("{'home':'nowhere','history':{'lived':['/Boat/b1','House/h1','/House/',5,null]},'name':'/House/h1'}");

        assertThat(links(parameters)).containsExactly(List.of(), List.of());
        assertThat(nodes(parameters)).extracting(node -> node.get("type") + " " + node.get("path") + " " + node.get("value")).containsExactly(
                "string $.home nowhere",
                "map $.history null",
                "array $.history.lived null",
                "string $.history.lived[] /Boat/b1",
                "string $.history.lived[] House/h1",
                "string $.history.lived[] /House/",
                "number $.history.lived[] 5",
                "null $.history.lived[] null",
                "string $.name /House/h1");
    }

    @Test
    void aDocumentOfAnEntityTheSpecificationDoesNotHaveHasNoLinks() throws Exception {
        JsonDocument document = new JsonDocument(new DocumentKey("ns", "Stranger", "s1", ZonedDateTime.now()), mapper.readTree("{\"at\":\"/House/h1\"}"));

        Neo4jQueryAndParams write = new Neo4jDocumentWriter().write(SPECIFICATION, "Stranger", List.of(document));

        assertThat(write.query()).contains("MERGE (r:`Stranger` ").doesNotContain("REF");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> batch = (List<Map<String, Object>>) write.params().get("batch");
        assertThat(nodes(batch.get(0))).extracting(node -> node.get("value")).containsExactly("/House/h1");
    }

    @Test
    void aLongTextIsLookedUpByItsHashAndAShortOneAsItIs() throws Exception {
        String forty = "0123456789012345678901234567890123456789";
        Map<String, Object> parameters = parametersOf("{'short':'" + forty + "','long':'" + forty + "1'}");

        assertThat(nodes(parameters).get(0)).containsEntry("value", forty).doesNotContainKey("hash");
        assertThat(nodes(parameters).get(1)).containsEntry("value", forty + "1").containsEntry("hash", Neo4jDocumentWriter.hashOf(forty + "1"));
        assertThat(Neo4jDocumentWriter.hashOf("abc")).isEqualTo("a9993e364706816aba3e25717850c26c9cd0d89d");
    }

    @Test
    void aWholeNumberThatALongDoesNotHoldIsWrittenAsADouble() throws Exception {
        Map<String, Object> parameters = parametersOf("{'big':12345678901234567890,'most':9223372036854775807}");

        assertThat(nodes(parameters).get(0)).containsEntry("value", 1.2345678901234567E19);
        assertThat(nodes(parameters).get(1)).containsEntry("value", Long.MAX_VALUE);
    }

    @Test
    void aDocumentThatIsNoObjectIsRefused() {
        JsonDocument document = new JsonDocument(new DocumentKey("ns", "Person", "p1", ZonedDateTime.now()), mapper.createArrayNode());

        assertThatThrownBy(() -> Neo4jDocumentWriter.documentParameters(document, PERSON, TARGETS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JSON object");
    }

    @Test
    void theDocumentAndEveryTargetGetTheOrderKeyOfTheirId() {
        JsonNode document = mapper.readTree("{\"home\":\"/Boat/ﬀ😀\"}");
        DocumentKey key = new DocumentKey("ns", "Person", "Ａ😀", ZonedDateTime.of(2020, 1, 1, 12, 0, 0, 0, ZoneId.of("Etc/UTC")));
        Map<String, Object> parameters = Neo4jDocumentWriter.documentParameters(new JsonDocument(key, document), PERSON, TARGETS);

        assertThat(parameters).containsEntry("id", "Ａ😀").containsEntry("idOrder", IdOrder.key("Ａ😀"));
        assertThat(links(parameters).get(0).getFirst()).containsEntry("id", "ﬀ😀").containsEntry("idOrder", IdOrder.key("ﬀ😀"));
        assertThat(Neo4jDocumentWriter.statement("Person", List.of("Boat")).cypher())
                .contains("MERGE (r:`Person` {namespace: record.namespace, id: record.id})\n  SET r.idOrder = record.idOrder")
                .contains("MERGE (target:`Boat` {namespace: record.namespace, id: link.id})\n    SET target.idOrder = link.idOrder");
    }

    @Test
    void theStatementHasOnePartForEachEntityThatMayBeLinkedToAndNoNameButLabels() {
        Neo4jDocumentWriter.Statement statement = Neo4jDocumentWriter.statement("Per`son", List.of("Boat", "Ho use"));

        assertThat(statement.linkTargets()).containsExactly("Boat", "Ho use");
        assertThat(statement.cypher())
                .contains("MERGE (r:`Per``son` {namespace: record.namespace, id: record.id})")
                .contains("(m:`Per``son_E` {type: 'map', path: '$'})")
                .contains("WITH i, x ORDER BY i\n    RETURN collect(x) AS created")
                .contains("UNWIND record.links[0] AS link\n    MERGE (target:`Boat` {namespace: record.namespace, id: link.id})")
                .contains("UNWIND record.links[1] AS link\n    MERGE (target:`Ho use` {namespace: record.namespace, id: link.id})");
        assertThat(Neo4jDocumentWriter.statement("Person", List.of()).cypher()).doesNotContain("REF");
    }

    @Test
    void theStatementOfAnEntityIsMadeOnce() {
        Neo4jDocumentWriter writer = new Neo4jDocumentWriter();
        JsonDocument document = new JsonDocument(new DocumentKey("ns", "Person", "p1", ZonedDateTime.now()), mapper.createObjectNode());

        assertThat(writer.write(SPECIFICATION, "Person", List.of(document)).query())
                .isSameAs(writer.write(SPECIFICATION, "Person", List.of(document, document)).query());

        // also for another specification made from the same model
        Specification again = createSpecificationAndRoot(Set.of(
                objectNode(SpecificationElementType.MANAGED, "Person", Set.of(stringNode("other"), refNode("at", Set.of("Boat", "House"))))));
        assertThat(writer.write(again, "Person", List.of(document)).query())
                .isSameAs(writer.write(SPECIFICATION, "Person", List.of(document)).query());
    }
}
