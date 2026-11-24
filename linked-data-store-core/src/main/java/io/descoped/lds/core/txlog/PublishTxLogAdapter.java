package io.descoped.lds.core.txlog;

import io.descoped.lds.core.saga.SagaInput;
import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.ActionOutputSerializer;
import no.cantara.saga.execution.adapter.ActionOutputSerializerToStringAndStringConstructor;
import no.cantara.saga.execution.adapter.AsyncSagaAdapter;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The step of a saga that writes the write to the transaction log, without a thread of the saga that waits for it:
 * the entry is published asynchronously, and the step is done when the transaction log has it. The id of the entry
 * is the transaction id of the write, so the order in which entries are stored does not depend on when each
 * publish completes.
 * <p>
 * A client of the transaction log that only blocks (PostgreSQL) publishes on a thread of its own; the saga goes on
 * on its own pool when the publish has completed. The step has no time limit, as the transaction log has none: a
 * publish that hangs keeps its saga open, and a time limit here would fail a saga whose entry may still be stored.
 */
abstract class PublishTxLogAdapter implements AsyncSagaAdapter<JsonNode> {

    private final String name;
    private final TxlogRawdataPool pool;

    PublishTxLogAdapter(String name, TxlogRawdataPool pool) {
        this.name = name;
        this.pool = pool;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public CompletionStage<JsonNode> executeActionAsync(SagaNode sagaNode, Object input, Map<SagaNode, Object> dependeesOutput) {
        try {
            SagaInput sagaInput = new SagaInput((JsonNode) input);
            return pool.producer(sagaInput.source())
                    .publishAsync(TxLogTools.sagaInputToTxEntry(sagaInput))
                    .thenApply(_ -> null);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e); // the saga fails as it did when the publish threw
        }
    }

    @Override
    public CompletionStage<Void> executeCompensatingActionAsync(SagaNode sagaNode, Object input, JsonNode output) {
        return CompletableFuture.completedFuture(null); // an entry of the transaction log is never taken back
    }

    @Override
    public boolean hasCompensatingAction() {
        return false;
    }

    @Override
    public ActionOutputSerializer<JsonNode> serializer() {
        return new ActionOutputSerializerToStringAndStringConstructor<>(JsonNode.class);
    }
}
