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
