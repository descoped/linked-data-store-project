package io.descoped.lds.core.controller;

import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.test.ConfigurationOverride;
import io.descoped.lds.test.client.ResponseHelper;
import io.descoped.lds.test.client.TestClient;
import io.descoped.lds.test.server.TestServerListener;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import javax.inject.Inject;

import static io.descoped.lds.core.utils.FileAndClasspathReaderUtils.readFileOrClasspathResource;
import static org.testng.Assert.assertEquals;

@Listeners(TestServerListener.class)
@Test(singleThreaded = true)
public class SourceTest {

    @Inject
    private TestClient client;

    @Test
    @ConfigurationOverride({
            "txlog.split.sources", "true",
            "txlog.default-source", "default",
            "txlog.rawdata.topic-prefix", "tx-empty-",
            "txlog.rawdata.provider", "memory"
    })
    public void thatLastSourceIdIsNullWhenNoDataHasBeenReceivedForSource() {
        ObjectNode expected = JsonTools.mapper.createObjectNode();
        expected.putNull("lastSourceId");

        String response = client.get("/source/MyEmptySource").expect200Ok().body();
        JsonNode actual = JsonTools.toJsonNode(response);
        assertEquals(actual, expected);
    }

    @Test
    @ConfigurationOverride({
            "txlog.split.sources", "true",
            "txlog.default-source", "default",
            "txlog.rawdata.topic-prefix", "tx-split-",
            "txlog.rawdata.provider", "memory"
    })
    public void thatLastSourceIdIsReturnsLastOfThreeMessages() throws InterruptedException {
        client.put("/data/provisionagreement/2a41c?sync=true&source=A&sourceId=a1", readFileOrClasspathResource("demo/1-money-bin.json")).expect201Created();
        client.put("/data/provisionagreement/2a41c/address?sync=true&source=A&sourceId=a2", readFileOrClasspathResource("demo/2-money-bin-address.json")).expect200Ok();
        client.put("/data/contact/4b2ef?sync=true&source=B&sourceId=b1", readFileOrClasspathResource("demo/3-scrooge.json")).expect201Created();
        client.put("/data/contact/821aa?sync=true&source=B&sourceId=b2", readFileOrClasspathResource("demo/4-donald.json")).expect201Created();
        client.put("/data/provisionagreement/2a41c/contacts/contact/4b2ef?sync=true&source=A&sourceId=a3").expect200Ok();
        client.put("/data/provisionagreement/2a41c/contacts/contact/821aa?sync=true&source=B&sourceId=b3").expect200Ok();
        client.delete("/data/provisionagreement/2a41c/contacts/contact/4b2ef?sync=true&source=B&sourceId=b4").expect200Ok();

        {
            ObjectNode expected = JsonTools.mapper.createObjectNode();
            expected.put("lastSourceId", "a3");
            String response = client.get("/source/A").expect200Ok().body();
            JsonNode actual = JsonTools.toJsonNode(response);
            assertEquals(actual, expected);
        }
        {
            ObjectNode expected = JsonTools.mapper.createObjectNode();
            expected.put("lastSourceId", "b4");
            String response = client.get("/source/B").expect200Ok().body();
            JsonNode actual = JsonTools.toJsonNode(response);
            assertEquals(actual, expected);
        }
    }

    @Test
    @ConfigurationOverride({
            "http.prefix", "/lds",
            "txlog.split.sources", "true",
            "txlog.default-source", "default",
            "txlog.rawdata.topic-prefix", "tx-prefixed-",
            "txlog.rawdata.provider", "memory"
    })
    public void thatTheLastSourceIdIsTheSameUnderAPrefix() {
        client.put("/lds/data/contact/4b2ef?sync=true&source=A&sourceId=a1", readFileOrClasspathResource("demo/3-scrooge.json")).expect201Created();

        ObjectNode expected = JsonTools.mapper.createObjectNode();
        expected.put("lastSourceId", "a1");
        String response = client.get("/lds/source/A").expect200Ok().body();
        assertEquals(JsonTools.toJsonNode(response), expected);
    }

    @Test
    @ConfigurationOverride({
            "txlog.split.sources", "false",
            "txlog.default-source", "default",
            "txlog.rawdata.topic-prefix", "tx-merged-",
            "txlog.rawdata.provider", "memory"
    })
    public void thatLastSourceIdIsOnlyReturnedForDefaultSourceWhenTxLogIsNotSplitUp() throws Exception {
        client.put("/data/provisionagreement/2a41c?sync=true&source=A&sourceId=a1", readFileOrClasspathResource("demo/1-money-bin.json")).expect201Created();
        client.put("/data/provisionagreement/2a41c/address?sync=true&source=A&sourceId=a2", readFileOrClasspathResource("demo/2-money-bin-address.json")).expect200Ok();
        client.put("/data/contact/4b2ef?sync=true&source=B&sourceId=b1", readFileOrClasspathResource("demo/3-scrooge.json")).expect201Created();
        client.put("/data/contact/821aa?sync=true&source=B&sourceId=b2", readFileOrClasspathResource("demo/4-donald.json")).expect201Created();
        client.put("/data/provisionagreement/2a41c/contacts/contact/4b2ef?sync=true&source=A&sourceId=a3").expect200Ok();
        client.put("/data/provisionagreement/2a41c/contacts/contact/821aa?sync=true&source=B&sourceId=b3").expect200Ok();
        client.delete("/data/provisionagreement/2a41c/contacts/contact/4b2ef?sync=true&source=B&sourceId=b4").expect200Ok();

        {
            ObjectNode expected = JsonTools.mapper.createObjectNode();
            expected.putNull("lastSourceId");
            String response = client.get("/source/A").expect200Ok().body();
            JsonNode actual = JsonTools.toJsonNode(response);
            assertEquals(actual, expected);
        }
        {
            ObjectNode expected = JsonTools.mapper.createObjectNode();
            expected.putNull("lastSourceId");
            String response = client.get("/source/B").expect200Ok().body();
            JsonNode actual = JsonTools.toJsonNode(response);
            assertEquals(actual, expected);
        }
        {
            ObjectNode expected = JsonTools.mapper.createObjectNode();
            expected.put("lastSourceId", "b4");
            String response = client.get("/source/default").expect200Ok().body();
            JsonNode actual = JsonTools.toJsonNode(response);
            assertEquals(actual, expected);
        }
    }

    @Test
    @ConfigurationOverride({
            "txlog.split.sources", "true",
            "txlog.default-source", "default",
            "txlog.rawdata.topic-prefix", "tx-discard-",
            "txlog.rawdata.provider", "discard"
    })
    public void thatTheLastSourceIdIsAConflictWhenNoTransactionLogIsKept() {
        client.put("/data/contact/4b2ef?sync=true&source=A&sourceId=a1", readFileOrClasspathResource("demo/3-scrooge.json")).expect201Created();

        ResponseHelper<String> response = client.get("/source/A");

        assertEquals(response.response().statusCode(), 409);
        assertEquals(response.response().headers().firstValue("Content-Type").orElse(null), "text/plain");
        assertEquals(response.body(), "No transaction log is kept (txlog.rawdata.provider=discard), so the last source id "
                + "of a source is not known");
    }
}
