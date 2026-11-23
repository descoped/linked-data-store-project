package io.descoped.lds.core.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A field whose value is {@code null} in a document that is written is read as a field that is not there: it is
 * removed before the document is validated and stored, in the document and in every object inside it, also an
 * object in an array. A {@code null} in an array is a value of the array, not a field, and is kept.
 */
public final class NullFields {

    private NullFields() {
    }

    /**
     * Removes every field whose value is {@code null}, at any depth.
     *
     * @param node the document, or a part of it, which is changed
     * @return the same node
     */
    public static JsonNode removeFrom(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> nullFields = new ArrayList<>();
            for (var property : object.properties()) {
                if (property.getValue().isNull()) {
                    nullFields.add(property.getKey());
                } else {
                    removeFrom(property.getValue());
                }
            }
            object.remove(nullFields);
        } else if (node != null && node.isArray()) {
            for (JsonNode element : node.values()) {
                removeFrom(element);
            }
        }
        return node;
    }
}
