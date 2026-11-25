package io.descoped.lds.core.domain.embedded;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.test.client.TestClient;
import io.descoped.lds.test.server.TestServer;
import io.descoped.lds.test.server.TestServerListener;
import org.json.JSONException;
import org.skyscreamer.jsonassert.JSONAssert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

import javax.inject.Inject;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.testng.Assert.assertEquals;

@Listeners(TestServerListener.class)
public class EmbeddedResourceHandlerTest {

    @Inject
    private TestClient client;

    @Inject
    private TestServer server;

    private void createTestResource(String entity, String id, String json) {
        ZonedDateTime timestamp = ZonedDateTime.now(ZoneId.of("Etc/UTC"));
        JsonNode jsonObject = JsonTools.toJsonNode(json);
        RxJsonPersistence persistence = server.getApplication().getPersistence();
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.createOrOverwrite(tx, new JsonDocument(new DocumentKey("data", entity, id, timestamp), jsonObject), server.getApplication().getSpecification()).blockingAwait();
        }
    }

    @Test
    public void thatAnEmptyBodyIsRefusedAndNothingIsWritten() {
        createTestResource("provisionagreement", "e-empty", "{\"id\":\"e-empty\",\"name\":\"pa-test-name\",\"address\":{\"street\":\"Akersveien 26\"}}");

        assertEquals(client.put("/data/provisionagreement/e-empty/address?sync=true", "").expect400BadRequest().body(), "Payload was empty!");
        assertEquals(client.put("/data/provisionagreement/e-empty/name?sync=true", "").expect400BadRequest().body(), "Payload was empty!");
        assertEquals(client.put("/data/provisionagreement/e-empty/name?sync=true", "\"x").expect400BadRequest().body().startsWith("The body cannot be read at line 1, column "), true);

        assertEquals(client.get("/data/provisionagreement/e-empty/address").expect200Ok().body(), "{\"street\":\"Akersveien 26\"}");
    }

    @Test
    public void thatGetEmbeddedResourceNavigatesDocumentToArray() {
        createTestResource("provisionagreement", "e1", "{\"id\":\"e1\",\"name\":\"pa-test-name\",\"contacts\":[\"/contact/c1\",\"/contact/c2\"]}");
        String response = client.get("/data/provisionagreement/e1/contacts").expect200Ok().body();
        assertEquals(response, "[\"/contact/c1\",\"/contact/c2\"]");
    }

    @Test
    public void thatGETEmbeddedResourceReturnsEmptyArray() {
        createTestResource("provisionagreement", "e2", "{\"id\":\"e2\"}");
        String response = client.get("/data/provisionagreement/e2/contacts").expect200Ok().body();
        assertEquals(response, "[null]");
    }

    @Test
    public void thatPutEmbeddedObjectResourceUpdatesSubDocument() throws JSONException {
        createTestResource("provisionagreement", "e3", "{\"id\":\"e3\",\"name\":\"pa-test-name\",\"contacts\":[\"/contact/c1\",\"/contact/c2\"],\"address\":{\"street\":\"Der julenissen bor\",\"country\":\"Nordpolen\"}}");
        client.put("/data/provisionagreement/e3/address?sync=true", "{\"street\":\"Akersveien 26\",\"city\":\"Oslo\",\"country\":\"Norway\"}").expect200Ok();
        String response = client.get("/data/provisionagreement/e3").expect200Ok().body();
        JSONAssert.assertEquals("{\"id\":\"e3\",\"name\":\"pa-test-name\",\"contacts\":[\"/contact/c1\",\"/contact/c2\"],\"address\":{\"street\":\"Akersveien 26\",\"city\":\"Oslo\",\"country\":\"Norway\"}}", response, true);
    }

    @Test
    public void thatAPartThatTakesTheDocumentOverTheLimitsIsRefusedAndNothingIsWritten() throws JSONException {
        String document = "{\"id\":\"e-limits\",\"name\":\"pa-test-name\",\"contacts\":[],\"address\":{\"street\":\"Storgata 1\"}}";
        createTestResource("provisionagreement", "e-limits", document);
        // the path is counted in the whole document: $.address. and the name of the field
        String part = "{\"" + "s".repeat(1100) + "\":\"x\"}";
        assertEquals(client.put("/data/provisionagreement/e-limits/address?sync=true", part).expect400BadRequest().body(),
                "The path of a value is at most 1024 bytes in UTF-8: $.address." + "s".repeat(70) + "...");
        JSONAssert.assertEquals(document, client.get("/data/provisionagreement/e-limits").expect200Ok().body(), true);
    }

    @Test
    public void thatAPartIsWrittenBeforeItIsAnsweredWhenSyncIsNotGivenAndANullFieldInItIsLeftOut() throws JSONException {
        createTestResource("provisionagreement", "e-wait", "{\"id\":\"e-wait\",\"name\":\"pa-test-name\",\"contacts\":[]}");
        for (int n = 0; n < 20; n++) {
            client.put("/data/provisionagreement/e-wait/address", "{\"street\":\"Storgata " + n + "\",\"city\":\"Oslo\",\"country\":null}").expect200Ok();
            JSONAssert.assertEquals("{\"id\":\"e-wait\",\"name\":\"pa-test-name\",\"contacts\":[],\"address\":{\"street\":\"Storgata " + n + "\",\"city\":\"Oslo\"}}",
                    client.get("/data/provisionagreement/e-wait").expect200Ok().body(), true);
        }
    }

    @Test
    public void thatAPartWrittenAsNullIsRemovedAsNullIsNoValue() throws JSONException {
        createTestResource("provisionagreement", "e-null", "{\"id\":\"e-null\",\"name\":\"pa-test-name\",\"address\":{\"street\":\"Storgata 1\"}}");
        client.put("/data/provisionagreement/e-null/address", "null").expect200Ok();
        JSONAssert.assertEquals("{\"id\":\"e-null\",\"name\":\"pa-test-name\"}",
                client.get("/data/provisionagreement/e-null").expect200Ok().body(), true);
    }

    @Test
    public void thatPutEmbeddedArrayResourceUpdatesSubDocument() throws JSONException {
        createTestResource("provisionagreement", "e4", "{\"id\":\"e4\",\"name\":\"pa-test-name\",\"contacts\":[\"/contact/c1\",\"/contact/c2\"]}");
        client.put("/data/provisionagreement/e4/contacts?sync=true", "[\"/contact/truls\",\"/contact/hans\"]").expect200Ok();
        String response = client.get("/data/provisionagreement/e4").expect200Ok().body();
        JSONAssert.assertEquals("{\"id\":\"e4\",\"name\":\"pa-test-name\",\"contacts\":[\"/contact/truls\",\"/contact/hans\"]}", response, true);
    }

    @Test
    public void thatDeleteEmbeddedObjectResourceUpdatesSubDocument() throws JSONException {
        createTestResource("provisionagreement", "e5", "{\"id\":\"e5\",\"name\":\"pa-test-name\",\"contacts\":[\"/contact/c1\",\"/contact/c2\"],\"address\":{\"street\":\"Der julenissen bor\",\"country\":\"Nordpolen\"}}");
        client.delete("/data/provisionagreement/e5/address?sync=true").expect200Ok();
        String response = client.get("/data/provisionagreement/e5").expect200Ok().body();
        JSONAssert.assertEquals("{\"id\":\"e5\",\"name\":\"pa-test-name\",\"contacts\":[\"/contact/c1\",\"/contact/c2\"]}", response, true);
    }

    @Test
    public void thatDeleteEmbeddedArrayResourceUpdatesSubDocument() throws JSONException {
        createTestResource("provisionagreement", "e6", "{\"id\":\"e6\",\"name\":\"pa-test-name\",\"contacts\":[\"/contact/c1\",\"/contact/c2\"]}");
        client.delete("/data/provisionagreement/e6/contacts?sync=true").expect200Ok();
        String response = client.get("/data/provisionagreement/e6").expect200Ok().body();
        JSONAssert.assertEquals("{\"id\":\"e6\",\"name\":\"pa-test-name\"}", response, true);
    }

    @Test
    public void thatDeleteOfAPartUnderAnObjectTheDocumentDoesNotHaveAddsNothing() throws JSONException {
        String document = "{\"id\":\"e-missing\",\"name\":\"pa-test-name\",\"contacts\":[\"/contact/c1\"]}";
        createTestResource("provisionagreement", "e-missing", document);
        client.delete("/data/provisionagreement/e-missing/support/technicalSupport?sync=true").expect200Ok();
        JSONAssert.assertEquals(document, client.get("/data/provisionagreement/e-missing").expect200Ok().body(), true);
    }

    @Test
    public void thatDELETELongResourcePathWorks() throws JSONException {
        createTestResource("provisionagreement", "e7", "{\"id\":\"e7\",\"name\":\"pa-test-name\",\"support\":{\"technicalSupport\":[\"/contact/s1\",\"/contact/s2\"],\"businessSupport\":[\"/contact/b1\"]}}");
        client.delete("/data/provisionagreement/e7/support/technicalSupport?sync=true").expect200Ok();
        String response = client.get("/data/provisionagreement/e7/support").expect200Ok().body();
        JSONAssert.assertEquals("{\"businessSupport\":[\"/contact/b1\"]}", response, true);
    }

    @Test
    public void thatPUTLongResourcePathWorks() throws JSONException {
        createTestResource("provisionagreement", "e8", "{\"id\":\"e8\",\"name\":\"pa-test-name\",\"support\":{\"technicalSupport\":[\"/contact/s1\",\"/contact/s2\"],\"businessSupport\":[\"/contact/b1\"]}}");
        client.put("/data/provisionagreement/e8/support/technicalSupport?sync=true", "[]").expect200Ok();
        String response = client.get("/data/provisionagreement/e8/support").expect200Ok().body();
        JSONAssert.assertEquals("{\"technicalSupport\":[],\"businessSupport\":[\"/contact/b1\"]}", response, true);
    }
}
