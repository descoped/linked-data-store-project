package io.descoped.lds.core.saga;

import io.descoped.lds.api.persistence.DocumentKey;
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

    /**
     * The input of a saga that writes or deletes one version of one document.
     *
     * @param txId     the id of the transaction
     * @param method   the method of the request ({@code PUT} or {@code DELETE})
     * @param schema   the schema
     * @param key      the document and its version: namespace, entity type, id and version
     * @param source   the source of the write, or null
     * @param sourceId the id of the write at its source, or null
     * @param data     the document, or null
     */
    public SagaInput(UUID txId, String method, String schema, DocumentKey key, String source, String sourceId,
                     JsonNode data) {
        ObjectNode json = JsonTools.mapper.createObjectNode();
        json.put("txid", txId.toString());
        json.put("method", method);
        json.put("schema", schema);
        json.put("namespace", key.namespace());
        json.put("entity", key.entity());
        json.put("id", key.id());
        json.put(VERSION, DateTimeFormatter.ISO_ZONED_DATE_TIME.format(key.timestamp()));
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
