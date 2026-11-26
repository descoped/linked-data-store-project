package io.descoped.lds.graphql.fetcher;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.idl.SchemaParser;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.core.persistence.memory.MemoryInitializer;
import io.descoped.lds.graphql.GraphQLContext;
import io.descoped.lds.graphql.schemas.GraphQLSchemaBuilder;
import io.undertow.server.HttpServerExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pages of a connection: of the documents of an entity type, of the links of a document (also to a union, and
 * inside an embedded object) and of its reverse links, with what the page says of the pages before and after it.
 * The documents of a backward page come from the largest id down.
 */
class ConnectionPagesTest {

    private static final ZonedDateTime WRITTEN = ZonedDateTime.parse("2026-01-01T00:00:00Z");

    private static final String MODEL = """
            directive @domain on OBJECT
            directive @link(pagination: Boolean, reverseName: String) on FIELD_DEFINITION
            type Person @domain { name: String pets: [Pet] @link home: Home }
            type Home { cars: [Car] @link }
            type Car @domain { make: String owners: [Person] @link(reverseName: "owned") }
            type Dog @domain { name: String }
            type Cat @domain { name: String }
            union Pet = Dog | Cat
            type Query { x: String }
            """;

    private RxJsonPersistence persistence;
    private GraphQL graphQl;

    @BeforeEach
    void model() {
        persistence = new MemoryInitializer().initialize("ns", Map.of(), Set.of("Person", "Car", "Dog", "Cat"));
        graphQl = GraphQL.newGraphQL(new GraphQLSchemaBuilder("ns", persistence, null, null)
                .getGraphQL(GraphQLSchemaBuilder.parseSchema(new SchemaParser().parse(MODEL)))).build();
    }

    @AfterEach
    void close() {
        persistence.close();
    }

    private void write(String entity, String id, String json) {
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.createOrOverwrite(tx, new JsonDocument(new DocumentKey("ns", entity, id, WRITTEN),
                    JsonTools.toJsonNode(json.replace('\'', '"'))), null).blockingAwait();
        }
    }

    private JsonNode query(String query) {
        ZonedDateTime now = ZonedDateTime.now();
        ExecutionResult result = graphQl.execute(ExecutionInput.newExecutionInput().query(query)
                .graphQLContext(Map.of(GraphQLContext.class, new GraphQLContext() {
                    @Override
                    public HttpServerExchange getExchange() {
                        return null;
                    }

                    @Override
                    public ZonedDateTime getSnapshot() {
                        return now;
                    }
                })));
        assertThat(result.getErrors()).as(query).isEmpty();
        return JsonTools.toJsonNode(result.toSpecification()).get("data");
    }

    private static List<String> names(JsonNode connection, String field) {
        List<String> names = new ArrayList<>();
        connection.get("edges").forEach(edge -> names.add(edge.get("node").get(field).stringValue()));
        return names;
    }

    private static List<Boolean> previousAndNext(JsonNode connection) {
        JsonNode pageInfo = connection.get("pageInfo");
        return List.of(pageInfo.get("hasPreviousPage").booleanValue(), pageInfo.get("hasNextPage").booleanValue());
    }

    private void persons() {
        for (String name : List.of("a", "b", "c", "d", "e")) {
            write("Person", name, "{'name':'" + name + "'}");
        }
    }

    @Test
    void aBackwardPageSaysWhatComesBeforeAndAfterIt() {
        persons();

        JsonNode last = query("{ Person(last: 2) { pageInfo { hasPreviousPage hasNextPage } edges { node { name } } } }")
                .get("Person");
        assertThat(names(last, "name")).containsExactly("e", "d");
        assertThat(previousAndNext(last)).containsExactly(true, false);

        JsonNode before = query("{ Person(before: \"c\") { pageInfo { hasPreviousPage hasNextPage } edges { node { name } } } }")
                .get("Person");
        assertThat(names(before, "name")).containsExactly("b", "a");
        assertThat(previousAndNext(before)).containsExactly(false, true);
    }

    @Test
    void aForwardPageSaysWhatComesBeforeAndAfterIt() {
        persons();

        JsonNode first = query("{ Person(first: 2, after: \"a\") { pageInfo { hasPreviousPage hasNextPage } edges { node { name } } } }")
                .get("Person");
        assertThat(names(first, "name")).containsExactly("b", "c");
        assertThat(previousAndNext(first)).containsExactly(true, true);
    }

    @Test
    void aBackwardPageOfReverseLinksSaysWhatComesBeforeAndAfterIt() {
        persons();
        write("Car", "audi", "{'make':'Audi','owners':['/Person/b']}");
        write("Car", "saab", "{'make':'Saab','owners':['/Person/a','/Person/b']}");
        write("Car", "volvo", "{'make':'Volvo','owners':['/Person/b','/Person/c']}");

        JsonNode owned = query("{ PersonById(id: \"b\") { owned(last: 2) { pageInfo { hasPreviousPage hasNextPage } "
                + "edges { node { make } } } } }").get("PersonById").get("owned");

        assertThat(names(owned, "make")).containsExactly("Volvo", "Saab");
        assertThat(previousAndNext(owned)).containsExactly(true, false);
    }

    /**
     * The links to each member of the union are read in the order of their ids together, and the page is taken
     * from them: here the cat comes first, though the union names the dogs first.
     */
    @Test
    void aPageOfLinksToAUnionIsTakenInTheOrderOfTheIds() {
        write("Dog", "rex", "{'name':'Rex'}");
        write("Dog", "spot", "{'name':'Spot'}");
        write("Cat", "alf", "{'name':'Alf'}");
        write("Person", "p", "{'name':'p','pets':['/Dog/rex','/Dog/spot','/Cat/alf']}");

        JsonNode pets = query("{ PersonById(id: \"p\") { pets(first: 2) { pageInfo { hasNextPage } "
                + "edges { cursor } } } }").get("PersonById").get("pets");

        List<String> cursors = new ArrayList<>();
        pets.get("edges").forEach(edge -> cursors.add(edge.get("cursor").stringValue()));
        assertThat(cursors).containsExactly("alf", "rex");
        assertThat(pets.get("pageInfo").get("hasNextPage").booleanValue()).isTrue();

        JsonNode last = query("{ PersonById(id: \"p\") { pets(last: 2) { edges { cursor } } } }")
                .get("PersonById").get("pets");
        List<String> lastCursors = new ArrayList<>();
        last.get("edges").forEach(edge -> lastCursors.add(edge.get("cursor").stringValue()));
        assertThat(lastCursors).containsExactly("spot", "rex");
    }

    @Test
    void aPageOfLinksInsideAnEmbeddedObjectIsRead() {
        write("Car", "saab", "{'make':'Saab'}");
        write("Car", "volvo", "{'make':'Volvo'}");
        write("Person", "p", "{'name':'p','home':{'cars':['/Car/volvo','/Car/aghost','/Car/saab']}}");

        JsonNode cars = query("{ PersonById(id: \"p\") { home { cars(first: 5) { edges { cursor node { make } } } } } }")
                .get("PersonById").get("home").get("cars");
        assertThat(names(cars, "make")).containsExactly("Saab", "Volvo");

        // a link to a document that does not exist takes no place on a page
        JsonNode first = query("{ PersonById(id: \"p\") { home { cars(first: 1) { pageInfo { hasPreviousPage "
                + "hasNextPage } edges { node { make } } } } } }").get("PersonById").get("home").get("cars");
        assertThat(names(first, "make")).containsExactly("Saab");
        assertThat(previousAndNext(first)).containsExactly(false, true);

        JsonNode last = query("{ PersonById(id: \"p\") { home { cars(last: 1, after: \"aghost\") { pageInfo { "
                + "hasPreviousPage hasNextPage } edges { node { make } } } } } }").get("PersonById").get("home").get("cars");
        assertThat(names(last, "make")).containsExactly("Volvo");
        assertThat(previousAndNext(last)).containsExactly(true, false);

        JsonNode inList = query("{ Person(first: 1) { edges { node { home { cars(first: 1) { edges { node { make } } } } } } } }")
                .get("Person").get("edges").get(0).get("node").get("home").get("cars");
        assertThat(names(inList, "make")).containsExactly("Saab");
    }
}
