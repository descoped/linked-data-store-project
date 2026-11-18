package io.descoped.lds.persistence.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.descoped.lds.api.json.JsonNavigationPath;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One version of a document: its key and its content, or a delete marker.
 *
 * <p>The content is not copied when a {@code Document} is created. A provider copies it when it stores a document
 * and returns content that the caller owns.
 *
 * @param key  the key
 * @param json the content, or {@code null} when this version is a delete marker
 */
public record Document(DocumentKey key, JsonNode json) {

    /**
     * Checks that there is a key.
     */
    public Document {
        Objects.requireNonNull(key, "key");
    }

    /**
     * Creates the delete marker for a key.
     *
     * @param key the key
     * @return a document without content
     */
    public static Document deleted(DocumentKey key) {
        return new Document(key, null);
    }

    /**
     * Whether this version is a delete marker.
     *
     * @return {@code true} when there is no content
     */
    public boolean deleted() {
        return json == null;
    }

    /**
     * The values a path leads to. An array element in the path without an index ({@code []}) stands for every
     * element, with an index ({@code [2]}) for that element. Parts of the document that do not have the shape the
     * path expects are skipped.
     *
     * @param path a path that starts at the document root ({@code $})
     * @return the values in document order, empty for a delete marker
     */
    public List<JsonNode> values(JsonNavigationPath path) {
        List<JsonNode> found = new ArrayList<>();
        if (json != null) {
            collect(json, path.getPath(), 1, found);
        }
        return found;
    }

    private static void collect(JsonNode node, String[] path, int index, List<JsonNode> found) {
        if (node == null) {
            return;
        }
        if (index == path.length) {
            found.add(node);
            return;
        }
        String element = path[index];
        if (element.startsWith("[") && element.endsWith("]")) {
            if (!node.isArray()) {
                return;
            }
            if (element.length() == 2) {
                for (JsonNode child : node) {
                    collect(child, path, index + 1, found);
                }
            } else {
                collect(node.get(Integer.parseInt(element, 1, element.length() - 1, 10)), path, index + 1, found);
            }
        } else if (node.isObject()) {
            collect(node.get(element), path, index + 1, found);
        }
    }
}
