package io.descoped.lds.graphql;

import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.test.ConfigurationOverride;
import io.descoped.lds.test.client.TestClient;
import io.descoped.lds.test.server.TestServerListener;
import org.testng.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

import static io.descoped.lds.core.utils.FileAndClasspathReaderUtils.readFileOrClasspathResource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertEquals;


@Listeners(TestServerListener.class)
public class GraphQLIntegrationTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    TestClient client;

    @Test
    @ConfigurationOverride({
            "graphql.enabled", "true",
            "graphql.search.enabled", "false",
            "specification.schema", "src/test/resources/spec/abstract/jsonschemas",
            "graphql.schema", "src/test/resources/spec/abstract/graphqlschemas/schema.graphql"
    })
    public void testAbstractRelations() {
        putResource("/data/Cat/cat1", "spec/abstract/cat1.json");
        putResource("/data/Cat/cat2", "spec/abstract/cat2.json");
        putResource("/data/Dog/dog1", "spec/abstract/dog1.json");
        putResource("/data/Dog/dog2", "spec/abstract/dog2.json");
        putResource("/data/Owner/owner1", "spec/abstract/owner1.json");
        putResource("/data/Owner/owner2", "spec/abstract/owner2.json");

        JsonNode result = executeGraphQLQuery("spec/abstract/query.json");

        JsonNode expectedResult = MAPPER.readTree(readFileOrClasspathResource("spec/abstract/query-response.json"));

        assertThat(result)
                .isEqualTo(expectedResult);

    }

    @Test
    @ConfigurationOverride({
            "graphql.enabled", "true",
            "specification.schema", "spec/demo/contact.json,spec/demo/provisionagreement.json"
    })
    public void thatGraphQLEndpointSupportsCors() {

        client.options("/graphql",
                "Access-Control-Request-Method", "POST"
        ).response().headers();
    }

    @Test
    @ConfigurationOverride({
            "graphql.enabled", "true",
            "specification.schema", "spec/demo/contact.json,spec/demo/provisionagreement.json"
    })
    public void thatGetContactOnlyWorks() {
        // setup demo data
        putResource("/data/contact/821aa", "demo/4-donald.json");
        assertEquals(client.get("/data/contact/821aa").expect200Ok().body(), "{\"email\":\"donald@webfoot.example\",\"name\":\"Donald Duck\"}");

        assertNoErrors(executeGraphQLQuery("spec/demo/graphql/contact_only.json"));
        assertNoErrors(executeGraphQLQuery("spec/demo/graphql/contact_by_id.json", "821aa"));
    }

    @Test
    @ConfigurationOverride({
            "graphql.enabled", "true",
            "specification.schema", "spec/demo/contact.json"
    })
    public void thatSeachByContactNameWorks() {
        // setup demo data
        putResource("/data/contact/821aa", "demo/4-donald.json");
        // the index is sent the update after the write, by the index queue
        JsonNode result = searchUntil("spec/demo/graphql/search_contact.json", "Duck", 1);
        assertNoErrors(result);
        Assert.assertEquals(JsonTools.text(result.get("data").get("Search").get("edges").get(0).get("node").get("name")), "Donald Duck");
        // Check that the entity is deleted from the index
        client.delete("/data/contact/821aa?sync=true");
        searchUntil("spec/demo/graphql/search_contact.json", "Duck", 0);
    }

    /**
     * A search finds a document by the words of its version valid now: a name it had before is not found, unless the
     * search asks for history, which finds the version that had it.
     */
    @Test
    @ConfigurationOverride({
            "graphql.enabled", "true",
            "specification.schema", "spec/demo/contact.json"
    })
    public void thatAWordOnlyAnOlderVersionHadIsFoundOnlyWithHistory() {
        putResource("/data/contact/821ab", "demo/4-donald.json");
        searchUntil("spec/demo/graphql/search_contact.json", "Duck", 1);
        client.put("/data/contact/821ab?sync=true", readFileOrClasspathResource("demo/4-donald.json")
                .replace("Donald Duck", "Fethry Duck").replace("donald@", "fethry@"));
        searchUntil("spec/demo/graphql/search_contact.json", "Fethry", 1);

        JsonNode now = executeGraphQLQuery("spec/demo/graphql/search_contact_history.json", "Donald", "false");
        JsonNode history = executeGraphQLQuery("spec/demo/graphql/search_contact_history.json", "Donald", "true");

        assertNoErrors(now);
        assertNoErrors(history);
        assertThat(now.get("data").get("Search").get("edges").size()).isZero();
        assertThat(history.get("data").get("Search").get("edges").size()).isEqualTo(1);
        assertThat(JsonTools.text(history.get("data").get("Search").get("edges").get(0).get("node").get("name")))
                .isEqualTo("Donald Duck");
    }

    @Test
    @ConfigurationOverride({
            "graphql.enabled", "true",
            "specification.schema", "spec/demo/contact.json,spec/demo/provisionagreement.json"
    })
    public void thatNestedSeachWorks() {
        // setup demo data
        putResource("/data/provisionagreement/2a41c", "demo/1-money-bin.json");
        putResource("/data/provisionagreement/2a41c/address", "demo/2-money-bin-address.json");

        JsonNode result = searchUntil("spec/demo/graphql/search_address.json", "Duckburg", 1);
        assertNoErrors(result);
        Assert.assertEquals(JsonTools.text(result.get("data").get("Search").get("edges").get(0).get("node").get("name")), "Money Bin");
        // Check that the entity index is updated
        client.put("/data/provisionagreement/2a41c", readFileOrClasspathResource(
                "demo/1-money-bin.json").replace("Money Bin", "Jupiter"));
        result = searchUntil("spec/demo/graphql/search_address.json", "Jupiter", 1);
        assertNoErrors(result);
    }

    @Test
    @ConfigurationOverride({
            "graphql.enabled", "true",
            "specification.schema", "spec/demo/contact.json,spec/demo/provisionagreement.json"
    })
    public void thatLinkingQueryWorks() {
        // setup demo data
        putResource("/data/provisionagreement/2a41c", "demo/1-money-bin.json");
        putResource("/data/provisionagreement/2a41c/address", "demo/2-money-bin-address.json");
        putResource("/data/contact/4b2ef", "demo/3-scrooge.json");
        putResource("/data/contact/821aa", "demo/4-donald.json");
        client.put("/data/provisionagreement/2a41c/contacts/contact/4b2ef");
        client.put("/data/provisionagreement/2a41c/contacts/contact/821aa");

        assertNoErrors(executeGraphQLQuery("spec/demo/graphql/basic_query.json"));
    }

    @Test
    @ConfigurationOverride({
            "graphql.enabled", "true",
            "graphql.search.enabled", "false",
            "specification.schema", "src/test/resources/spec/abstract/jsonschemas",
            "graphql.schema", "src/test/resources/spec/abstract/graphqlschemas/schema.graphql"
    })
    public void thatALinkThatIsAbsentOrLeadsNowhereIsNull() {
        client.put("/data/Owner/nofavorite?sync=true", "{\"name\": \"none\", \"email\": \"none@example.com\"}");
        client.put("/data/Owner/dangling?sync=true",
                "{\"name\": \"dangling\", \"email\": \"dangling@example.com\", \"favorite\": \"/Cat/not-yet\"}");

        JsonNode byId = JsonTools.toJsonNode(client.postJson("/graphql",
                "{\"query\": \"{ none: OwnerById(id: \\\"nofavorite\\\") { name favorite { __typename } } "
                        + "dangling: OwnerById(id: \\\"dangling\\\") { name favorite { __typename } } }\"}")
                .expect200Ok().body());
        assertNoErrors(byId);
        assertThat(byId.at("/data/none/name").asString()).isEqualTo("none");
        assertThat(byId.at("/data/none/favorite").isNull()).isTrue();
        assertThat(byId.at("/data/dangling/favorite").isNull()).isTrue();

        JsonNode list = JsonTools.toJsonNode(client.postJson("/graphql",
                "{\"query\": \"{ Owner { edges { node { name favorite { __typename } } } } }\"}")
                .expect200Ok().body());
        assertNoErrors(list);
        // the server is shared with the other tests of the class, so only these two owners are looked at
        List<JsonNode> mine = new ArrayList<>();
        list.at("/data/Owner/edges").forEach(edge -> {
            if (List.of("none", "dangling").contains(edge.at("/node/name").asString())) {
                mine.add(edge.at("/node"));
            }
        });
        assertThat(mine).hasSize(2).allSatisfy(owner -> assertThat(owner.get("favorite").isNull()).isTrue());
    }

    @Test
    @ConfigurationOverride({
            "graphql.enabled", "true",
            "graphql.search.enabled", "false",
            "specification.schema", "src/test/resources/spec/abstract/jsonschemas",
            "graphql.schema", "src/test/resources/spec/abstract/graphqlschemas/schema.graphql"
    })
    public void thatAnIdNoDocumentHasIsNullAndTheRestOfTheAnswerIsKept() {
        client.put("/data/Owner/known?sync=true", "{\"name\": \"known\", \"email\": \"known@example.com\"}").expect201Created();

        JsonNode result = JsonTools.toJsonNode(client.postJson("/graphql",
                "{\"query\": \"{ known: OwnerById(id: \\\"known\\\") { name } nobody: OwnerById(id: \\\"nobody\\\") { name } }\"}")
                .expect200Ok().body());

        assertNoErrors(result);
        assertThat(result.at("/data/known/name").asString()).isEqualTo("known");
        assertThat(result.at("/data/nobody").isNull()).isTrue();
    }

    @Test
    @ConfigurationOverride({
            "graphql.enabled", "true",
            "graphql.search.enabled", "false",
            "specification.schema", "src/test/resources/spec/abstract/jsonschemas",
            "graphql.schema", "src/test/resources/spec/abstract/graphqlschemas/schema.graphql"
    })
    public void thatAFieldThatIsNullIsWrittenAsAFieldThatIsNotThere() {
        client.put("/data/Owner/noage?sync=true", "{\"name\": \"noage\", \"email\": \"noage@example.com\", \"age\": null}")
                .expect201Created();

        assertThat(JsonTools.toJsonNode(client.get("/data/Owner/noage").expect200Ok().body()))
                .isEqualTo(JsonTools.toJsonNode("{\"name\": \"noage\", \"email\": \"noage@example.com\"}"));

        // through GraphQL, a field that is not there is null: no age, and not an age of 0
        JsonNode byId = JsonTools.toJsonNode(client.postJson("/graphql",
                "{\"query\": \"{ OwnerById(id: \\\"noage\\\") { name age } }\"}").expect200Ok().body());
        assertNoErrors(byId);
        assertThat(byId.at("/data/OwnerById/name").asString()).isEqualTo("noage");
        assertThat(byId.at("/data/OwnerById/age").isNull()).isTrue();
    }

    @Test
    @ConfigurationOverride({
            "graphql.enabled", "true",
            "graphql.search.enabled", "false",
            "graphql.schema", "src/test/resources/spec/scalars/schema.graphql"
    })
    public void thatAValueGraphQLCannotGiveBackIsRefusedWhenItIsWritten() {
        client.put("/data/Measure/m1?sync=true", """
                {"name": "m1", "count": 2147483647, "big": 9007199254740993, "ratio": 1.5, "day": "2026-11-23",
                 "at": "10:15:30Z", "when": "2026-11-23T10:15:30.123Z", "counts": [1, -2147483648], "part": {"size": 7}}""")
                .expect201Created();

        JsonNode result = JsonTools.toJsonNode(client.postJson("/graphql",
                "{\"query\": \"{ MeasureById(id: \\\"m1\\\") { count big ratio day at counts part { size } } }\"}")
                .expect200Ok().body());
        assertNoErrors(result);
        assertThat(result.at("/data/MeasureById")).isEqualTo(JsonTools.toJsonNode("""
                {"count": 2147483647, "big": 9007199254740993, "ratio": 1.5, "day": "2026-11-23", "at": "10:15:30Z",
                 "counts": [1, -2147483648], "part": {"size": 7}}"""));

        for (String refused : List.of(
                "{\"name\": \"r\", \"count\": 2147483648}",
                "{\"name\": \"r\", \"count\": 1.5}",
                "{\"name\": \"r\", \"big\": 9223372036854775808}",
                "{\"name\": \"r\", \"ratio\": \"1.5\"}",
                "{\"name\": \"r\", \"day\": \"23.11.2026\"}",
                "{\"name\": \"r\", \"at\": \"25:00:00Z\"}",
                "{\"name\": \"r\", \"counts\": [1, 2147483648]}")) {
            var answer = client.put("/data/Measure/refused?sync=true", refused);
            assertThat(answer.response().statusCode()).as(refused).isEqualTo(400);
            assertThat(answer.body()).as(refused).startsWith("Schema validation error: ");
        }
        client.get("/data/Measure/refused").expect404NotFound();
    }

    @Test
    @ConfigurationOverride({
            "graphql.enabled", "true",
            "graphql.search.enabled", "false",
            "graphql.schema", "src/test/resources/spec/plain-links/schema.graphql"
    })
    public void thatAPlainListOfLinksToAUnionHasTheDocumentsTheLinksName() {
        client.put("/data/Cat/tom?sync=true", "{\"name\": \"Tom\"}");
        client.put("/data/Dog/rex?sync=true", "{\"name\": \"Rex\"}");
        client.put("/data/Owner/eva?sync=true",
                "{\"name\": \"Eva\", \"pets\": [\"/Dog/rex\", \"/Cat/gone\", \"/Cat/tom\"]}");

        JsonNode result = JsonTools.toJsonNode(client.postJson("/graphql",
                "{\"query\": \"{ OwnerById(id: \\\"eva\\\") { pets { __typename ... on Cat { name } ... on Dog { name } } } }\"}")
                .expect200Ok().body());

        assertNoErrors(result);
        List<String> pets = new ArrayList<>();
        result.at("/data/OwnerById/pets").forEach(pet -> pets.add(pet.get("__typename").asString() + " " + pet.get("name").asString()));
        assertThat(pets).containsExactly("Dog Rex", "Cat Tom");
    }

    @Test
    @ConfigurationOverride({
            "graphql.enabled", "true",
            "graphql.search.enabled", "false",
            "graphql.schema", "src/test/resources/spec/plain-links/schema.graphql"
    })
    public void thatADocumentOfAPlainListOfLinksCanBeAskedForItsConnections() {
        client.put("/data/Car/ab123?sync=true", "{\"plate\": \"AB 123\"}");
        client.put("/data/Owner/ola?sync=true", "{\"name\": \"Ola\", \"cars\": [\"/Car/ab123\"]}");

        JsonNode result = JsonTools.toJsonNode(client.postJson("/graphql",
                "{\"query\": \"{ OwnerById(id: \\\"ola\\\") { cars { plate reverseOwnerCars { edges { node { name } } } } } }\"}")
                .expect200Ok().body());

        assertNoErrors(result);
        assertThat(result.at("/data/OwnerById/cars/0/reverseOwnerCars/edges/0/node/name").asString()).isEqualTo("Ola");
    }

    private void putResource(String path, String resourceFilePath) {
        client.put(path + "?sync=true", readFileOrClasspathResource(resourceFilePath));
    }

    private void assertNoErrors(JsonNode responseRootNode) {
        System.out.println(responseRootNode);
        if (responseRootNode.has("errors")) {
            // there should not be any errors!
            JsonNode errors = responseRootNode.get("errors");
            String errorMessages = JsonTools.toJson(errors);
            Assert.fail(errorMessages);
        }
    }

    private JsonNode executeGraphQLQuery(String path, Object... params) {
        String query = String.format(readFileOrClasspathResource(path), params);
        return JsonTools.toJsonNode(client.postJson("/graphql", query)
                .expect200Ok()
                .body());
    }

    /*
     * Searches until the search has the given number of results, a few seconds at most: the index queue sends a write
     * to the index after the write.
     */
    private JsonNode searchUntil(String query, String text, int results) {
        JsonNode[] last = new JsonNode[1];
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() -> {
            last[0] = executeGraphQLQuery(query, text);
            JsonNode edges = last[0].path("data").path("Search").path("edges");
            return edges.isArray() && edges.size() == results;
        });
        return last[0];
    }
}
