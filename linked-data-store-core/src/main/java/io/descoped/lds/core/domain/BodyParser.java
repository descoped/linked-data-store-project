package io.descoped.lds.core.domain;

import org.apache.tika.mime.MediaType;
import org.komamitsu.jackson.dataformat.msgpack.MessagePackMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class BodyParser {

    final ObjectMapper jsonMapper = new ObjectMapper();
    final ObjectMapper msgPackMapper = new MessagePackMapper();

    public JsonNode deserializeBody(String contentType, String requestBody) {
        try {
            // deserialize request data
            MediaType type = MediaType.parse(contentType);
            JsonNode requestData;
            if ("application".equals(type.getType()) && "json".equals(type.getSubtype())) {
                requestData = jsonMapper.readTree(requestBody);
            } else if ("application".equals(type.getType())
                    && ("msgpack".equals(type.getSubtype()) || "x-msgpack".equals(type.getSubtype()))) {
                requestData = msgPackMapper.readTree(requestBody);
            } else {
                throw new IllegalArgumentException("Unsupported Content-Type: " + contentType);
            }
            return requestData;
        } catch (JacksonException e) {
            throw new RuntimeException(e);
        }
    }
}
