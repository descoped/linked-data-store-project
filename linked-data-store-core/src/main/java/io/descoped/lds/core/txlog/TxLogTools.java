package io.descoped.lds.core.txlog;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.core.saga.SagaInput;
import io.descoped.rawdata.api.RawdataMessage;
import org.komamitsu.jackson.dataformat.msgpack.MessagePackMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static java.util.Optional.ofNullable;

public class TxLogTools {

    static final ObjectMapper mapper = new MessagePackMapper();

    private TxLogTools() {
    }

    public static RawdataMessage sagaInputToTxEntry(SagaInput sagaInput) {
        ObjectNode meta = mapper.createObjectNode();
        meta.put("method", sagaInput.method());
        meta.put("schema", sagaInput.schema());
        meta.put("namespace", sagaInput.namespace());
        meta.put("entity", sagaInput.entity());
        meta.put("id", sagaInput.resourceId());
        meta.put("version", sagaInput.versionAsString());
        if (sagaInput.source() != null) {
            meta.put("source", sagaInput.source());
        }
        if (sagaInput.sourceId() != null) {
            meta.put("sourceId", sagaInput.sourceId());
        }

        String uri = String.format("%s/%s/%s", sagaInput.entity(), sagaInput.resourceId(), sagaInput.version().toInstant().toEpochMilli());

        RawdataMessage.Builder builder = RawdataMessage.builder();
        builder.id(UUID.fromString(sagaInput.txId()))
                .position(uri);
        if (sagaInput.data() != null) {
            builder.put("data", toBytes(sagaInput.data()));
        }
        return builder.put("meta", toBytes(meta)).build();
    }

    public static SagaInput txEntryToSagaInput(RawdataMessage message) {
        JsonNode meta = toJson(message.get("meta"));
        JsonNode data = message.keys().contains("data") ? toJson(message.get("data")) : null;
        return new SagaInput(message.id(),
                JsonTools.text(meta.get("method")),
                JsonTools.text(meta.get("schema")),
                new DocumentKey(JsonTools.text(meta.get("namespace")),
                        JsonTools.text(meta.get("entity")),
                        JsonTools.text(meta.get("id")),
                        ZonedDateTime.parse(JsonTools.text(meta.get("version")), DateTimeFormatter.ISO_ZONED_DATE_TIME)),
                ofNullable(meta.get("source")).map(JsonTools::text).orElse(null),
                ofNullable(meta.get("sourceId")).map(JsonTools::text).orElse(null),
                data);
    }

    public static byte[] toBytes(JsonNode node) {
        try {
            return mapper.writeValueAsBytes(node);
        } catch (JacksonException e) {
            throw new RuntimeException(e);
        }
    }

    public static JsonNode toJson(byte[] buf) {
        try {
            return mapper.readTree(buf);
        } catch (JacksonException e) {
            throw new RuntimeException(e);
        }
    }

}
