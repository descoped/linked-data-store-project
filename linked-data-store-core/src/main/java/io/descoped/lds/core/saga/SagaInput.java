package io.descoped.lds.core.saga;

import io.descoped.lds.api.persistence.json.JsonTools;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static java.util.Optional.ofNullable;

public class SagaInput {

    private static final String VERSION = "version";

    final JsonNode node;

    public SagaInput(JsonNode node) {
        this.node = node;
    }

    public SagaInput(UUID txId, String method, String schema, String namespace, String entity,
                     String id, ZonedDateTime version, String source, String sourceId, JsonNode data) {
        ObjectNode json = JsonTools.mapper.createObjectNode();
        json.put("txid", txId.toString());
        json.put("method", method);
        json.put("schema", schema);
        json.put("namespace", namespace);
        json.put("entity", entity);
        json.put("id", id);
        json.put(VERSION, DateTimeFormatter.ISO_ZONED_DATE_TIME.format(version));
        if (source != null) {
            json.put("source", source);
        }
        if (sourceId != null) {
            json.put("sourceId", sourceId);
        }
        if (data != null) {
            json.set("data", data);
        }
        this.node = json;
    }

    JsonNode asJsonNode() {
        return node;
    }

    public String txId() {
        return JsonTools.text(node.get("txid"));
    }

    public String method() {
        return JsonTools.text(node.get("method"));
    }

    public String schema() {
        return JsonTools.text(node.get("schema"));
    }

    public String namespace() {
        return JsonTools.text(node.get("namespace"));
    }

    public String entity() {
        return JsonTools.text(node.get("entity"));
    }

    public String resourceId() {
        return JsonTools.text(node.get("id"));
    }

    public ZonedDateTime version() {
        String versionStr = JsonTools.text(node.get(VERSION));
        return ZonedDateTime.parse(versionStr, DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    public String versionAsString() {
        return JsonTools.text(node.get(VERSION));
    }

    public String source() {
        return ofNullable(node.get("source")).map(JsonTools::text).orElse(null);
    }

    public String sourceId() {
        return ofNullable(node.get("sourceId")).map(JsonTools::text).orElse(null);
    }

    public JsonNode data() {
        return node.get("data");
    }

    @Override
    public String toString() {
        return "SagaInput{" +
                "txId='" + txId() + '\'' +
                ", method='" + method() + '\'' +
                ", schema='" + schema() + '\'' +
                ", namespace='" + namespace() + '\'' +
                ", entity='" + entity() + '\'' +
                ", id='" + resourceId() + '\'' +
                ", version='" + versionAsString() + '\'' +
                ", source='" + source() + '\'' +
                ", sourceId='" + sourceId() + '\'' +
                ", data=" + data() +
                '}';
    }

    public String toPrettyString() {
        return JsonTools.toPrettyJson(node);
    }
}
