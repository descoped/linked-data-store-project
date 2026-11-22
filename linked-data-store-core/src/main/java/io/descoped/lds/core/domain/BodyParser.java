package io.descoped.lds.core.domain;

import org.apache.tika.mime.MediaType;
import org.komamitsu.jackson.dataformat.msgpack.MessagePackMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class BodyParser {

    static final String SEND_JSON = "Send the body as JSON, with Content-Type: application/json";

    final ObjectMapper jsonMapper = new ObjectMapper();
    final ObjectMapper msgPackMapper = new MessagePackMapper();

    /**
     * Where a text could not be read as JSON, and why: {@code " at line 1, column 12: Unexpected end-of-input: ..."}.
     *
     * @param e what Jackson threw
     * @return the place, when Jackson knows it, a colon and the reason
     */
    public static String describe(JacksonException e) {
        return place(e) + ": " + reason(e);
    }

    private static String place(JacksonException e) {
        TokenStreamLocation location = e.getLocation();
        if (location == null || location.getLineNr() < 1) {
            return "";
        }
        return " at line " + location.getLineNr() + ", column " + location.getColumnNr();
    }

    // Jackson's own text, without the place of the start of an object it adds, whose source is redacted
    private static String reason(JacksonException e) {
        String reason = e.getOriginalMessage();
        int marker = reason.indexOf(" (start marker at ");
        return marker < 0 ? reason : reason.substring(0, marker);
    }

    /**
     * The document a body holds.
     *
     * @throws BodyException 400 when the body is empty or cannot be read, 415 when the content type is not one LDS
     *                       reads
     */
    public JsonNode deserializeBody(String contentType, String requestBody) {
        MediaType type = contentType == null ? null : MediaType.parse(contentType);
        ObjectMapper reader;
        if (type != null && "application".equals(type.getType()) && "json".equals(type.getSubtype())) {
            reader = jsonMapper;
        } else if (type != null && "application".equals(type.getType())
                && ("msgpack".equals(type.getSubtype()) || "x-msgpack".equals(type.getSubtype()))) {
            reader = msgPackMapper;
        } else {
            throw new BodyException(415, "Unsupported Content-Type: " + contentType + ". " + SEND_JSON);
        }
        if (requestBody == null || requestBody.isBlank()) {
            throw new BodyException(400, "Payload was empty!");
        }
        try {
            return reader.readTree(requestBody);
        } catch (JacksonException e) {
            throw new BodyException(400, "The body cannot be read" + describe(e) + ". " + SEND_JSON);
        } catch (UnsupportedOperationException e) {
            // MessagePack is bytes, and the body reaches the parser as text
            throw new BodyException(400, "The body cannot be read: " + e.getMessage() + ". " + SEND_JSON);
        }
    }
}
