package io.descoped.lds.core.search;

import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.IndexUpdate;
import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.Adapter;
import tools.jackson.databind.JsonNode;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * The step of the saga that tells the search index that a document was written: it adds the update to the index
 * queue and is done. The index is sent the update by the {@link IndexQueueWorker}, after the saga.
 */
public class UpdateIndexSagaAdapter extends Adapter<JsonNode> {

    public static final String ADAPTER_NAME = "Persistence-Index-Create-or-Overwrite";

    private final IndexQueue queue;

    public UpdateIndexSagaAdapter(IndexQueue queue) {
        super(JsonNode.class, ADAPTER_NAME);
        this.queue = queue;
    }

    @Override
    public JsonNode executeAction(SagaNode sagaNode, Object sagaInput, Map<SagaNode, Object> dependeesOutput) {
        JsonNode input = (JsonNode) sagaInput;
        String versionStr = JsonTools.text(input.get("version"));
        ZonedDateTime version = ZonedDateTime.parse(versionStr, DateTimeFormatter.ISO_ZONED_DATE_TIME);
        queue.add(new IndexUpdate(JsonTools.text(input.get("namespace")), JsonTools.text(input.get("entity")),
                JsonTools.text(input.get("id")), version, IndexUpdate.Operation.CREATE_OR_OVERWRITE)).blockingAwait();
        return null;
    }
}
