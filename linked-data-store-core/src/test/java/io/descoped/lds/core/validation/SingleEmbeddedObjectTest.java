package io.descoped.lds.core.validation;

import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.test.client.TestClient;
import io.descoped.lds.test.server.TestServerListener;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import javax.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A single field of an embedded type of a GraphQL model ({@code address: address} in {@code provisionagreement} of
 * the test model, where {@code address} has {@code street: String!} and {@code city: String!}) is checked as its type
 * says, and a field of it is a part of the document that can be written and removed.
 */
@Listeners(TestServerListener.class)
public class SingleEmbeddedObjectTest {

    @Inject
    private TestClient client;

    @Test
    public void theEmbeddedObjectIsCheckedAsItsTypeSays() {
        client.put("/data/provisionagreement/single-checked?sync=true",
                "{\"name\":\"pa\",\"address\":{\"street\":\"Storgata 1\",\"city\":\"Oslo\"}}").expect201Created();

        assertThat(client.put("/data/provisionagreement/single-checked?sync=true",
                "{\"name\":\"pa\",\"address\":{\"street\":\"Storgata 1\",\"city\":7}}").expect400BadRequest().body())
                .isEqualTo("Schema validation error: [$.address.city: integer found, string expected]");
        assertThat(client.put("/data/provisionagreement/single-checked?sync=true",
                "{\"name\":\"pa\",\"address\":{\"street\":\"Storgata 1\"}}").expect400BadRequest().body())
                .isEqualTo("Schema validation error: [$.address: required property 'city' not found]");
        assertThat(client.put("/data/provisionagreement/single-checked?sync=true",
                "{\"name\":\"pa\",\"address\":\"just text\"}").expect400BadRequest().body())
                .isEqualTo("Schema validation error: [$.address: string found, object expected]");
    }

    @Test
    public void aFieldOfTheEmbeddedObjectIsAPartOfTheDocument() {
        client.put("/data/provisionagreement/single-part?sync=true",
                "{\"name\":\"pa\",\"address\":{\"street\":\"Storgata 1\",\"city\":\"Oslo\"}}").expect201Created();

        client.put("/data/provisionagreement/single-part/address/street?sync=true", "\"Akersveien 26\"").expect200Ok();
        assertThat(JsonTools.toJsonNode(client.get("/data/provisionagreement/single-part").expect200Ok().body())
                .get("address")).isEqualTo(JsonTools.toJsonNode("{\"street\":\"Akersveien 26\",\"city\":\"Oslo\"}"));

        client.delete("/data/provisionagreement/single-part/address/street?sync=true").expect200Ok();
        assertThat(JsonTools.toJsonNode(client.get("/data/provisionagreement/single-part").expect200Ok().body())
                .get("address")).isEqualTo(JsonTools.toJsonNode("{\"city\":\"Oslo\"}"));
    }
}
