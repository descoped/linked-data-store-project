package io.descoped.lds.api.persistence.json;

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
import java.util.stream.Collectors;

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
            String path = parentPath.stream().collect(Collectors.joining("."));
            leafNodesByPath.put(path, new FlattenedDocumentLeafNode(documentKey, path, FragmentType.NULL, null, fragmentCapacity));
        } else if (node.isString()) {
            String path = parentPath.stream().collect(Collectors.joining("."));
            leafNodesByPath.put(path, new FlattenedDocumentLeafNode(documentKey, path, FragmentType.STRING, node.stringValue(), fragmentCapacity));
        } else if (node.isNumber()) {
            String path = parentPath.stream().collect(Collectors.joining("."));
            leafNodesByPath.put(path, new FlattenedDocumentLeafNode(documentKey, path, FragmentType.NUMERIC, node.asString(), fragmentCapacity));
        } else if (node.isBoolean()) {
            String path = parentPath.stream().collect(Collectors.joining("."));
            leafNodesByPath.put(path, new FlattenedDocumentLeafNode(documentKey, path, FragmentType.BOOLEAN, node.asString(), fragmentCapacity));
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            if (array.size() == 0) {
                String path = parentPath.stream().collect(Collectors.joining("."));
                leafNodesByPath.put(path, new FlattenedDocumentLeafNode(documentKey, path, FragmentType.EMPTY_ARRAY, null, fragmentCapacity));
            } else {
                for (int i = 0; i < array.size(); i++) {
                    String originalLast = parentPath.removeLast();
                    String last = originalLast + "[" + i + "]";
                    parentPath.addLast(last);
                    populateMapFromJson(parentPath, leafNodesByPath, array.get(i));
                    parentPath.removeLast();
                    parentPath.addLast(originalLast);
                }
            }
        } else if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            if (object.size() == 0) {
                String path = parentPath.stream().collect(Collectors.joining("."));
                leafNodesByPath.put(path, new FlattenedDocumentLeafNode(documentKey, path, FragmentType.EMPTY_OBJECT, null, fragmentCapacity));
            } else {
                for (Map.Entry<String, JsonNode> field : node.properties()) {
                    parentPath.addLast(field.getKey());
                    populateMapFromJson(parentPath, leafNodesByPath, field.getValue());
                    parentPath.removeLast();
                }
            }
        } else {
            throw new UnsupportedOperationException("Type " + node.getClass().getName() + " not supported for path " + parentPath);
        }
    }
}
