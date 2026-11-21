package io.descoped.lds.core.txlog;

import io.descoped.lds.core.saga.SagaInput;
import io.descoped.lds.test.ConfigurationOverride;
import io.descoped.lds.test.client.TestClient;
import io.descoped.lds.test.server.TestServer;
import io.descoped.lds.test.server.TestServerListener;
import io.descoped.rawdata.api.RawdataClient;
import io.descoped.rawdata.api.RawdataConsumer;
import io.descoped.rawdata.api.RawdataMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import javax.inject.Inject;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

import static io.descoped.lds.core.utils.FileAndClasspathReaderUtils.readFileOrClasspathResource;
import static org.testng.Assert.assertEquals;

@Listeners(TestServerListener.class)
public class ReplayTxLogTest {

    Logger log = LoggerFactory.getLogger(ReplayTxLogTest.class);

    @Inject
    private TestClient client;

    @Inject
    private TestServer server;

    @Test
    @ConfigurationOverride({
            "txlog.rawdata.provider", "memory",
            "specification.schema", "spec/demo/contact.json,spec/demo/provisionagreement.json"
    })
    public void replayTxLogAndVerify() throws Exception {

        /*
         * Produce 7 entries in tx-log through http api
         */
        client.put("/data/provisionagreement/2a41c?sync=true&source=test&sourceId=abc123", readFileOrClasspathResource("demo/1-money-bin.json")).expect201Created();
        client.put("/data/provisionagreement/2a41c/address?sync=true", readFileOrClasspathResource("demo/2-money-bin-address.json")).expect200Ok();
        client.put("/data/contact/4b2ef?sync=true", readFileOrClasspathResource("demo/3-scrooge.json")).expect201Created();
        client.put("/data/contact/821aa?sync=true", readFileOrClasspathResource("demo/4-donald.json")).expect201Created();
        client.put("/data/provisionagreement/2a41c/contacts/contact/4b2ef?sync=true").expect200Ok();
        client.put("/data/provisionagreement/2a41c/contacts/contact/821aa?sync=true").expect200Ok();
        client.delete("/data/provisionagreement/2a41c/contacts/contact/4b2ef?sync=true").expect200Ok();

        dumpCurrentTxLog(); // for debugging purposes

        /*
         * Read and check that the tx-log contains exactly the 7 elements in the correct order
         */
        TxlogRawdataPool txlogRawdataPool = server.getApplication().getTxlogRawdataPool();
        RawdataClient rawdataClient = txlogRawdataPool.getClient();
        String txLogTopic = txlogRawdataPool.topicOf(null);

        try (RawdataConsumer consumer = rawdataClient.consumer(txLogTopic)) {

            RawdataMessage m1 = consumer.receive(0, TimeUnit.MILLISECONDS);
            assertEquals(entityAndId(m1), "provisionagreement/2a41c");
            assertEquals(TxLogTools.txEntryToSagaInput(m1).method(), "PUT");

            RawdataMessage m2 = consumer.receive(0, TimeUnit.MILLISECONDS);
            assertEquals(entityAndId(m2), "provisionagreement/2a41c");
            assertEquals(TxLogTools.txEntryToSagaInput(m2).method(), "PUT");

            RawdataMessage m3 = consumer.receive(0, TimeUnit.MILLISECONDS);
            assertEquals(entityAndId(m3), "contact/4b2ef");
            assertEquals(TxLogTools.txEntryToSagaInput(m3).method(), "PUT");

            RawdataMessage m4 = consumer.receive(0, TimeUnit.MILLISECONDS);
            assertEquals(entityAndId(m4), "contact/821aa");
            assertEquals(TxLogTools.txEntryToSagaInput(m4).method(), "PUT");

            RawdataMessage m5 = consumer.receive(0, TimeUnit.MILLISECONDS);
            assertEquals(entityAndId(m5), "provisionagreement/2a41c");
            assertEquals(TxLogTools.txEntryToSagaInput(m5).method(), "PUT");

            RawdataMessage m6 = consumer.receive(0, TimeUnit.MILLISECONDS);
            assertEquals(entityAndId(m6), "provisionagreement/2a41c");
            assertEquals(TxLogTools.txEntryToSagaInput(m6).method(), "PUT");

            RawdataMessage m7 = consumer.receive(0, TimeUnit.MILLISECONDS);
            assertEquals(entityAndId(m7), "provisionagreement/2a41c");
            assertEquals(TxLogTools.txEntryToSagaInput(m7).method(), "PUT");
        }
    }

    private String entityAndId(RawdataMessage m1) {
        String position = m1.position();
        return position.substring(0, position.length() - 14); // remove the last 14 characters in order to strip away the version/timestamp component
    }

    /**
     * The version of a write is kept as a UTC time, whatever offset the client gave it in: the same instant must not
     * be written to the transaction log in two forms.
     */
    @Test
    @ConfigurationOverride({
            "txlog.rawdata.provider", "memory",
            "specification.schema", "spec/demo/contact.json"
    })
    public void thatTheVersionOfAnEntryIsInUtc() throws Exception {
        client.put("/data/contact/utc1?sync=true&timestamp=2020-01-01T00:00:00%2B01:00", readFileOrClasspathResource("demo/3-scrooge.json")).expect201Created();
        client.get("/data/contact/utc1?timestamp=2019-12-31T23:00:00Z").expect200Ok();
        client.get("/data/contact/utc1?timestamp=2019-12-31T22:59:59Z").expect404NotFound();

        TxlogRawdataPool txlogRawdataPool = server.getApplication().getTxlogRawdataPool();
        try (RawdataConsumer consumer = txlogRawdataPool.getClient().consumer(txlogRawdataPool.topicOf(null))) {
            RawdataMessage message = consumer.receive(0, TimeUnit.MILLISECONDS);
            assertEquals(entityAndId(message), "contact/utc1");
            assertEquals(TxLogTools.txEntryToSagaInput(message).version(), ZonedDateTime.of(2019, 12, 31, 23, 0, 0, 0, ZoneId.of("Etc/UTC")));
            assertEquals(message.position(), "contact/utc1/" + ZonedDateTime.parse("2019-12-31T23:00:00Z").toInstant().toEpochMilli());
        }
    }

    private void dumpCurrentTxLog() throws Exception {
        TxlogRawdataPool txlogRawdataPool = server.getApplication().getTxlogRawdataPool();
        RawdataClient rawdataClient = txlogRawdataPool.getClient();
        String txLogTopic = txlogRawdataPool.topicOf(null);
        try (RawdataConsumer consumer = rawdataClient.consumer(txLogTopic)) {
            RawdataMessage message;
            for (int i = 1; (message = consumer.receive(0, TimeUnit.MILLISECONDS)) != null; i++) {
                SagaInput sagaInput = TxLogTools.txEntryToSagaInput(message);
                log.debug("TxLog Entry # {}:\n{}", i, sagaInput.toPrettyString());
            }
        }
    }
}
