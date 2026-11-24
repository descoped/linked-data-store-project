package io.descoped.lds.core.saga;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.core.txlog.AppendTxLogAdapter;
import io.descoped.lds.core.txlog.TxlogRawdataPool;
import io.descoped.rawdata.api.RawdataClient;
import io.descoped.rawdata.api.RawdataConsumer;
import io.descoped.rawdata.api.RawdataDuplicateIdException;
import io.descoped.rawdata.api.RawdataIds;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The step of a saga that writes to the transaction log.
 */
class TxLogStepTest {

    /**
     * A step that is run again stores its entry once. Recovery runs a step again when its End is not in the saga
     * log (the process stopped after the publish). It runs it with the input as the saga log gave it back, as text
     * (the saga stores {@code String.valueOf(input)}), so the entry must come out the same: the transaction log takes
     * the same message again as a no-op, and refuses a different message under the same id.
     */
    @Test
    void aStepRunAgainByRecoveryStoresItsEntryOnce() throws Exception {
        try (RawdataClient transactionLog = RawdataClient.create(Map.of(RawdataClient.PROVIDER_KEY, "memory"))) {
            AppendTxLogAdapter step = new AppendTxLogAdapter(new TxlogRawdataPool(transactionLog, false, "default", "", true));
            ObjectNode document = JsonTools.mapper.createObjectNode();
            document.put("name", "Ærlig Øystein 😀");
            document.put("price", new BigDecimal("1.10"));
            document.put("count", Long.MAX_VALUE);
            document.put("ratio", 0.1 + 0.2);
            document.putNull("nothing");
            document.putArray("tags").add("a").addArray().add(1).add(true);
            JsonNode input = new SagaInput(RawdataIds.generate(), "PUT", "TODO",
                    new DocumentKey("ns", "Person", "p1", ZonedDateTime.now()), "source", "s1", document).asJsonNode();
            JsonNode recovered = JsonTools.toJsonNode(input.toString()); // as recovery reads it from the saga log

            step.executeAction(null, input, Map.of());
            step.executeAction(null, recovered, Map.of());
            assertThat(entries(transactionLog)).isEqualTo(1);

            ObjectNode changed = (ObjectNode) input.deepCopy();
            changed.withObject("data").put("name", "another");
            assertThatThrownBy(() -> step.executeAction(null, changed, Map.of()))
                    .isInstanceOf(RawdataDuplicateIdException.class);
            assertThat(entries(transactionLog)).isEqualTo(1);
        }
    }

    private static int entries(RawdataClient transactionLog) throws InterruptedException {
        try (RawdataConsumer consumer = transactionLog.consumer("default")) {
            int entries = 0;
            while (consumer.receive(0, TimeUnit.SECONDS) != null) {
                entries++;
            }
            return entries;
        }
    }
}
