package io.descoped.lds.core.persistence;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.specification.Specification;
import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.AbortSagaException;
import no.cantara.saga.execution.adapter.Adapter;
import tools.jackson.databind.JsonNode;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class PersistenceCreateOrOverwriteSagaAdapter extends Adapter<JsonNode> {

    public static final String ADAPTER_NAME = "Persistence-Create-or-Overwrite";

    private final RxJsonPersistence persistence;
    private final Specification specification;

    public PersistenceCreateOrOverwriteSagaAdapter(RxJsonPersistence persistence, Specification specification) {
        super(JsonNode.class, ADAPTER_NAME);
        this.persistence = persistence;
        this.specification = specification;
    }

    @Override
    public JsonNode executeAction(SagaNode sagaNode, Object sagaInput, Map<SagaNode, Object> dependeesOutput) {
        JsonNode input = (JsonNode) sagaInput;
        String versionStr = JsonTools.text(input.get("version"));
        ZonedDateTime version = ZonedDateTime.parse(versionStr, DateTimeFormatter.ISO_ZONED_DATE_TIME);
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.createOrOverwrite(tx, new JsonDocument(new DocumentKey(JsonTools.text(input.get("namespace")), JsonTools.text(input.get("entity")), JsonTools.text(input.get("id")), version), input.get("data")), specification).blockingAwait();
        } catch (Throwable t) {
            throw new AbortSagaException("Unable to write data using persistence.", t);
        }
        return null;
    }
}
