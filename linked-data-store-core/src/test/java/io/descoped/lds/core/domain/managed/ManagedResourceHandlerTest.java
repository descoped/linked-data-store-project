package io.descoped.lds.core.domain.managed;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.test.client.ResponseHelper;
import io.descoped.lds.test.client.TestClient;
import io.descoped.lds.test.server.TestServer;
import io.descoped.lds.test.server.TestServerListener;
import org.json.JSONException;
import org.skyscreamer.jsonassert.JSONAssert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

import javax.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Iterator;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Listeners(TestServerListener.class)
public class ManagedResourceHandlerTest {

    @Inject
    private TestClient client;

    @Inject
    private TestServer server;

    private void createTestResource(String entity, String id, String json) {
        ZonedDateTime timestamp = ZonedDateTime.now(ZoneId.of("Etc/UTC"));
        createTestResource(entity, id, timestamp, json);
    }

    private void createTestResource(String entity, String id, ZonedDateTime timestamp, String json) {
        JsonNode jsonObject = JsonTools.toJsonNode(json);
        RxJsonPersistence persistence = server.getApplication().getPersistence();
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.createOrOverwrite(tx, new JsonDocument(new DocumentKey("data", entity, id, timestamp), jsonObject), server.getApplication().getSpecification()).blockingAwait();
        }
    }

    @Test
    public void thatPUTIntegrationWorks() throws JSONException {
        String body = "{\"name\":\"pa-test-name\",\"contacts\":[\"/contact/c1\",\"/contact/c2\"]}";
        client.put("/data/provisionagreement/m1?sync=true", body).expectAnyOf(200, 201);
        String actual = client.get("/data/provisionagreement/m1").expect200Ok().body();
        JSONAssert.assertEquals(body, actual, false);
    }

    @Test
    public void thatABodyLDSDoesNotReadIsAnsweredWithWhatToSend() {
        ResponseHelper<String> notJson = client.putAs("/data/provisionagreement/body1?sync=true", "application/json", "{\"name\":");
        assertEquals(notJson.response().statusCode(), 400);
        assertEquals(notJson.response().headers().firstValue("Content-Type").orElse(null), "text/plain");
        assertTrue(notJson.body().startsWith("The body cannot be read at line 1, column "), notJson.body());
        assertTrue(notJson.body().endsWith(". Send the body as JSON, with Content-Type: application/json"), notJson.body());

        ResponseHelper<String> form = client.putAs("/data/provisionagreement/body1?sync=true", "application/x-www-form-urlencoded", "{\"name\":\"x\"}");
        assertEquals(form.response().statusCode(), 415);
        assertEquals(form.body(), "Unsupported Content-Type: application/x-www-form-urlencoded. Send the body as JSON, with Content-Type: application/json");

        client.get("/data/provisionagreement/body1").expect404NotFound();
    }

    /**
     * The status line and the body of a GET sent as these bytes, past the URI handling of an HTTP client.
     */
    private String[] rawGet(byte[] path) throws IOException {
        try (Socket socket = new Socket("localhost", server.getTestServerServicePort())) {
            OutputStream out = socket.getOutputStream();
            out.write("GET ".getBytes(StandardCharsets.US_ASCII));
            out.write(path);
            out.write(" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String answer = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new String[]{answer.substring(0, answer.indexOf("\r\n")), answer.substring(answer.indexOf("\r\n\r\n") + 4)};
        }
    }

    @Test
    public void thatAnIdIsDecodedOnce() throws IOException {
        String body = "{\"contacts\":[],\"name\":\"plus\"}";
        client.put("/data/provisionagreement/a+b?sync=true", body).expect201Created();
        assertEquals(client.get("/data/provisionagreement/a%2Bb").expect200Ok().body(), body);
        client.get("/data/provisionagreement/a%20b").expect404NotFound();

        client.put("/data/provisionagreement/100%25?sync=true", body).expect201Created();
        assertEquals(client.get("/data/provisionagreement/100%25").expect200Ok().body(), body);

        client.put("/data/provisionagreement/%C3%B8l?sync=true", body).expect201Created();
        assertEquals(client.get("/data/provisionagreement/%C3%B8l").expect200Ok().body(), body);
        // path parameters are not part of the id, as Undertow leaves them out of the path
        assertEquals(rawGet("/data/provisionagreement/%C3%B8l;v=1".getBytes(StandardCharsets.US_ASCII))[0], "HTTP/1.1 200 OK");
    }

    @Test
    public void thatAnIdLDSDoesNotTakeIsRefusedWith400() throws IOException {
        assertEquals(client.get("/data/provisionagreement/a%2Fb").expect400BadRequest().body(), "An id cannot hold a '/': \"a/b\"");
        assertEquals(client.get("/data/provisionagreement/a%0Ab").expect400BadRequest().body(), "An id cannot hold a control character");
        assertEquals(client.get("/data/provisionagreement/" + "a".repeat(513)).expect400BadRequest().body(), "An id is at most 512 characters");
        // Undertow refuses an escape that is not one, and a byte outside ASCII, before LDS sees the request
        assertEquals(rawGet("/data/provisionagreement/100%".getBytes(StandardCharsets.US_ASCII))[0], "HTTP/1.1 400 Bad Request");
        assertEquals(rawGet("/data/provisionagreement/øl".getBytes(StandardCharsets.UTF_8))[0], "HTTP/1.1 400 Bad Request");
    }

    @Test
    public void thatDELETEWithoutAnIdFailsWith400AndRemovesNothing() {
        createTestResource("provisionagreement", "m-kept", "{\"name\":\"pa-test-name\",\"contacts\":[]}");
        assertEquals(client.delete("/data/provisionagreement?sync=true").expect400BadRequest().body(), "Id was empty!");
        assertEquals(client.delete("/data/provisionagreement/?sync=true").expect400BadRequest().body(), "Id was empty!");
        client.get("/data/provisionagreement/m-kept").expect200Ok();
    }

    @Test
    public void thatDELETEDoesRemoveResource() {
        createTestResource("provisionagreement", "m2", "{\"name\":\"pa-test-name\",\"contacts\":[\"/contact/c1\",\"/contact/c2\"]}");
        client.get("/data/provisionagreement/m2").expect200Ok();
        client.delete("/data/provisionagreement/m2?sync=true").expect204NoContent();
        client.get("/data/provisionagreement/m2").expect404NotFound();
    }

    @Test
    public void thatNonExistentResourcesReturnsWith404NotFoundAndDoesNotLeakContext() {
        String response1 = client.get("/data/provisionagreement/m3").expect404NotFound().body();
        String response2 = client.get("/data/contact/m3c1").expect404NotFound().body();
        // check that no context is leaked, i.e. response is same for different non-existent resources
        assertEquals(response1, response2);
    }

    @Test
    public void thatJsonSchemaIsFoundForNamespace() {
        ResponseHelper<String> response = client.get("/data/contact?schema");
        assertTrue(response.body().contains("\"$ref\":\"#/$defs/contact\""));
        assertTrue(response.body().contains("\"$schema\":\"https://json-schema.org/draft/2020-12/schema\""));
    }

    @Test
    public void thatADocumentThatDoesNotFollowTheSchemaIsAnsweredWithWhatIsWrong() {
        String answer = client.put("/data/contact/notvalid1?sync=true", "{\"name\":7,\"email\":\"a@b.c\"}").expect400BadRequest().body();
        assertEquals(answer, "Schema validation error: [$.name: integer found, string expected]");
        client.get("/data/contact/notvalid1").expect404NotFound();
    }

    @Test
    public void thatTimelineIsCorrectForGivenResource() {
        createTestResource("provisionagreement", "5gTH4iagnrGHJ94gkf43JdcFB2",
                ZonedDateTime.of(2018, 2, 12, 12, 1, 0, 0, ZoneOffset.UTC),
                "{\"name\":\"first\"}");
        createTestResource("provisionagreement", "5gTH4iagnrGHJ94gkf43JdcFB2",
                ZonedDateTime.of(2018, 5, 3, 13, 2, 0, 0, ZoneOffset.UTC),
                "{\"name\":\"second\"}");
        createTestResource("provisionagreement", "5gTH4iagnrGHJ94gkf43JdcFB2",
                ZonedDateTime.of(2019, 9, 28, 14, 3, 0, 0, ZoneOffset.UTC),
                "{\"name\":\"third\"}");
        createTestResource("provisionagreement", "5gTH4iagnrGHJ94gkf43JdcFB2",
                ZonedDateTime.of(2020, 4, 30, 15, 4, 0, 0, ZoneOffset.UTC),
                "{\"name\":\"fourth\"}");

        ResponseHelper<String> response = client.get("/data/provisionagreement/5gTH4iagnrGHJ94gkf43JdcFB2?timeline").expect200Ok();

        JsonNode timelineResult = JsonTools.toJsonNode(response.body());
        assertTrue(timelineResult.isArray());
        assertEquals(timelineResult.size(), 4);
        Iterator<JsonNode> it = timelineResult.iterator();
        JsonNode first = it.next();
        assertEquals(JsonTools.text(first.get("version")), "2018-02-12T12:01Z[Etc/UTC]");
        assertEquals(JsonTools.text(first.get("document").get("name")), "first");
        JsonNode second = it.next();
        assertEquals(JsonTools.text(second.get("version")), "2018-05-03T13:02Z[Etc/UTC]");
        assertEquals(JsonTools.text(second.get("document").get("name")), "second");
        JsonNode third = it.next();
        assertEquals(JsonTools.text(third.get("version")), "2019-09-28T14:03Z[Etc/UTC]");
        assertEquals(JsonTools.text(third.get("document").get("name")), "third");
        JsonNode fourth = it.next();
        assertEquals(JsonTools.text(fourth.get("version")), "2020-04-30T15:04Z[Etc/UTC]");
        assertEquals(JsonTools.text(fourth.get("document").get("name")), "fourth");
        assertFalse(it.hasNext());
    }

    @Test
    public void thatTimelineReturns404ForNonExistentResource() {
        client.get("/data/provisionagreement/non-existent-resource?timeline").expect404NotFound();
    }
}
