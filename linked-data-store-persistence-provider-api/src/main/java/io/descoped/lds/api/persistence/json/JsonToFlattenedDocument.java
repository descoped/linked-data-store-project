package io.descoped.lds.api.persistence.json;

import io.descoped.lds.api.json.FieldName;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.flattened.FlattenedDocument;
import io.descoped.lds.api.persistence.flattened.FlattenedDocumentLeafNode;
import io.descoped.lds.api.persistence.streaming.FragmentType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.ZonedDateTime;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

public class JsonToFlattenedDocument {

    private final String namespace;
    private final String entity;
    private final String id;
    private final ZonedDateTime version;
    private final DocumentKey documentKey;
    private final JsonNode root;
    private final int fragmentCapacity;

    public JsonToFlattenedDocument(String namespace, String entity, String id, ZonedDateTime version, JsonNode root, int fragmentCapacity) {
        this.namespace = namespace;
        this.entity = entity;
        this.id = id;
        this.version = version;
        this.root = root;
        this.fragmentCapacity = fragmentCapacity;
        documentKey = new DocumentKey(namespace, entity, id, version);
    }

    public FlattenedDocument toDocument() {
        Map<String, FlattenedDocumentLeafNode> leafNodesByPath = new LinkedHashMap<>();
        Deque<String> parentPath = new LinkedList<>();
        parentPath.add("$");
        populateMapFromJson(parentPath, leafNodesByPath, root);
        return new FlattenedDocument(
                new DocumentKey(
                        namespace,
                        entity,
                        id,
                        version
                ),
                leafNodesByPath,
                false
        );
    }

    void populateMapFromJson(Deque<String> parentPath, Map<String, FlattenedDocumentLeafNode> leafNodesByPath, JsonNode node) {
        if (node == null || node.isNull()) {
            putLeaf(parentPath, leafNodesByPath, FragmentType.NULL, null);
        } else if (node.isString()) {
            putLeaf(parentPath, leafNodesByPath, FragmentType.STRING, node.stringValue());
        } else if (node.isNumber()) {
            putLeaf(parentPath, leafNodesByPath, FragmentType.NUMERIC, node.asString());
        } else if (node.isBoolean()) {
            putLeaf(parentPath, leafNodesByPath, FragmentType.BOOLEAN, node.asString());
        } else if (node.isArray()) {
            populateMapFromArray(parentPath, leafNodesByPath, (ArrayNode) node);
        } else if (node.isObject()) {
            populateMapFromObject(parentPath, leafNodesByPath, (ObjectNode) node);
        } else {
            throw new UnsupportedOperationException("Type " + node.getClass().getName() + " not supported for path " + parentPath);
        }
    }

    private void populateMapFromArray(Deque<String> parentPath, Map<String, FlattenedDocumentLeafNode> leafNodesByPath, ArrayNode array) {
        if (array.size() == 0) {
            putLeaf(parentPath, leafNodesByPath, FragmentType.EMPTY_ARRAY, null);
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            String originalLast = parentPath.removeLast();
            String last = originalLast + "[" + i + "]";
            parentPath.addLast(last);
            populateMapFromJson(parentPath, leafNodesByPath, array.get(i));
            parentPath.removeLast();
            parentPath.addLast(originalLast);
        }
    }

    private void populateMapFromObject(Deque<String> parentPath, Map<String, FlattenedDocumentLeafNode> leafNodesByPath, ObjectNode object) {
        if (object.size() == 0) {
            putLeaf(parentPath, leafNodesByPath, FragmentType.EMPTY_OBJECT, null);
            return;
        }
        for (Map.Entry<String, JsonNode> field : object.properties()) {
            // a name is a step of the path with the syntax of a path escaped, so that it is read back as it is
            parentPath.addLast(FieldName.escape(field.getKey()));
            populateMapFromJson(parentPath, leafNodesByPath, field.getValue());
            parentPath.removeLast();
        }
    }

    private void putLeaf(Deque<String> parentPath, Map<String, FlattenedDocumentLeafNode> leafNodesByPath, FragmentType type, String value) {
        String path = String.join(".", parentPath);
        leafNodesByPath.put(path, new FlattenedDocumentLeafNode(documentKey, path, type, value, fragmentCapacity));
    }
}
