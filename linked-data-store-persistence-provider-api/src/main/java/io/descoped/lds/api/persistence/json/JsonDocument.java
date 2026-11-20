package io.descoped.lds.api.persistence.json;

import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.persistence.DocumentKey;
import tools.jackson.databind.JsonNode;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class JsonDocument {

    private final DocumentKey key;

    private JsonNode jackson;

    public JsonDocument(DocumentKey key, Map<String, Object> document) {
        this.key = key;
        this.jackson = JsonTools.toJsonNode(document);
    }

    public JsonDocument(DocumentKey key, JsonNode document) {
        this.key = key;
        this.jackson = document;
    }

    public DocumentKey key() {
        return key;
    }

    public JsonNode jackson() {
        return jackson;
    }

    public Map<String, Object> toMap() {
        return JsonTools.toMap(jackson);
    }

    public boolean deleted() {
        return jackson == null;
    }

    /**
     * Visits what the document holds at a path. {@code []} in the path is every element of an array, so a path
     * may be visited many times. Where the document does not have the path, nothing is visited: a field that is
     * not there, an array where the path says object, or anything else where it says array.
     *
     * @param jsonNavigationPath the path, from the root of the document
     * @param visitCallback      is given what is at the path, and the path to it with the place of each array
     *                           element: {@code $.name[1].first}
     */
    public void traverseField(JsonNavigationPath jsonNavigationPath, BiConsumer<JsonNode, String> visitCallback) {
        LinkedList<String> path = new LinkedList<>();
        path.add(jsonNavigationPath.getPath()[0]);
        doTraverseField(jackson, jsonNavigationPath.getPath(), 1, path, visitCallback);
    }

    private void doTraverseField(JsonNode node, String[] schemaPath, int index, Deque<String> path, BiConsumer<JsonNode, String> visitCallback) {
        if (node == null) {
            return;
        }
        if (index >= schemaPath.length) {
            visitCallback.accept(node, path.stream().collect(Collectors.joining(".")).replaceAll("\\.\\[", "["));
            return;
        }
        String childName = schemaPath[index];

        if ("[]".equals(childName)) {
            if (!node.isArray()) {
                return;
            }
            for (int i = 0; i < node.size(); i++) {
                JsonNode child = node.get(i);
                path.addLast("[" + i + "]");
                doTraverseField(child, schemaPath, index + 1, path, visitCallback);
                path.removeLast();
            }
        } else {
            // Object node; anything else has no field
            JsonNode childNode = node.get(childName);
            path.addLast(childName);
            doTraverseField(childNode, schemaPath, index + 1, path, visitCallback);
            path.removeLast();
        }
    }
}
