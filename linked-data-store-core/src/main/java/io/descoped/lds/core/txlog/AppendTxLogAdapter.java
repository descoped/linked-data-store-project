package io.descoped.lds.core.txlog;

import io.descoped.lds.core.saga.SagaInput;
import io.descoped.rawdata.api.RawdataProducer;
import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.Adapter;
import tools.jackson.databind.JsonNode;

import java.util.Map;

public class AppendTxLogAdapter extends Adapter<JsonNode> {

    public static final String ADAPTER_NAME = "TxLog-put-entry";

    final TxlogRawdataPool pool;

    public AppendTxLogAdapter(TxlogRawdataPool pool) {
        super(JsonNode.class, ADAPTER_NAME);
        this.pool = pool;
    }

    @Override
    public JsonNode executeAction(SagaNode sagaNode, Object input, Map<SagaNode, Object> dependeesOutput) {
        SagaInput sagaInput = new SagaInput((JsonNode) input);
        RawdataProducer producer = pool.producer(sagaInput.source());
        producer.publish(TxLogTools.sagaInputToTxEntry(sagaInput));
        return null;
    }

}
