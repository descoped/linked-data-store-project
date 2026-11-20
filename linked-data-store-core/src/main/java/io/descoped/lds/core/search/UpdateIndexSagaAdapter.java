package io.descoped.lds.core.search;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.specification.Specification;
import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.Adapter;
import tools.jackson.databind.JsonNode;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class UpdateIndexSagaAdapter extends Adapter<JsonNode> {

    public static final String ADAPTER_NAME = "Persistence-Index-Create-or-Overwrite";

    private final SearchIndex indexer;
    private final Specification specification;

    public UpdateIndexSagaAdapter(SearchIndex indexer, Specification specification) {
        super(JsonNode.class, ADAPTER_NAME);
        this.indexer = indexer;
        this.specification = specification;
    }

    @Override
    public JsonNode executeAction(SagaNode sagaNode, Object sagaInput, Map<SagaNode, Object> dependeesOutput) {
        JsonNode input = (JsonNode) sagaInput;
        String versionStr = JsonTools.text(input.get("version"));
        ZonedDateTime version = ZonedDateTime.parse(versionStr, DateTimeFormatter.ISO_ZONED_DATE_TIME);
        indexer.createOrOverwrite(new JsonDocument(new DocumentKey(JsonTools.text(input.get("namespace")),
                        JsonTools.text(input.get("entity")), JsonTools.text(input.get("id")), version), input.get("data")))
                .blockingAwait();
        return null;
    }
}
