package io.descoped.lds.core.persistence;

import io.descoped.lds.api.persistence.PersistenceDeletePolicy;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.Adapter;
import tools.jackson.databind.JsonNode;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class PersistenceDeleteSagaAdapter extends Adapter<JsonNode> {

    public static final String ADAPTER_NAME = "Persistence-Delete";

    private final RxJsonPersistence persistence;

    public PersistenceDeleteSagaAdapter(RxJsonPersistence persistence) {
        super(JsonNode.class, ADAPTER_NAME);
        this.persistence = persistence;
    }

    @Override
    public JsonNode executeAction(SagaNode sagaNode, Object sagaInput, Map<SagaNode, Object> dependeesOutput) {
        JsonNode input = (JsonNode) sagaInput;
        String versionStr = JsonTools.text(input.get("version"));
        ZonedDateTime version = ZonedDateTime.parse(versionStr, DateTimeFormatter.ISO_ZONED_DATE_TIME);
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.markDocumentDeleted(
                    tx,
                    JsonTools.text(input.get("namespace")),
                    JsonTools.text(input.get("entity")),
                    JsonTools.text(input.get("id")),
                    version,
                    PersistenceDeletePolicy.FAIL_IF_INCOMING_LINKS
            ).blockingAwait();
        }
        return null;
    }
}
