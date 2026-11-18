package io.descoped.lds.persistence.memory;

import com.fasterxml.jackson.databind.JsonNode;
import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.persistence.api.DocumentKey;
import io.descoped.tuple.Range;
import io.descoped.tuple.Tuple;

import java.time.Instant;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The key layout, described in the package documentation.
 */
final class Keys {

    private static final String DOCUMENTS = "d";
    private static final String VALUES = "v";

    private Keys() {
    }

    /** The key of one version. */
    static byte[] version(DocumentKey key) {
        return versionsFrom(key.namespace(), key.entity(), key.id(), key.version());
    }

    /** Where the versions of a document that are not after an instant start: the newest of them comes first. */
    static byte[] versionsFrom(String namespace, String entity, String id, Instant notAfter) {
        Instant version = DocumentKey.truncate(notAfter);
        return Tuple.of(DOCUMENTS, namespace, entity, id, -version.getEpochSecond(), -(version.getNano() / 1000L))
                .pack();
    }

    /** Every version of one document, newest first. */
    static Range document(String namespace, String entity, String id) {
        return Tuple.of(DOCUMENTS, namespace, entity, id).range();
    }

    /** Every version of every document of an entity, by id, and newest first within an id. */
    static Range entity(String namespace, String entity) {
        return Tuple.of(DOCUMENTS, namespace, entity).range();
    }

    /** Whether a key is the key of a version, not of a recorded value. */
    static boolean isVersion(byte[] key) {
        return DOCUMENTS.equals(Tuple.fromBytes(key).getString(0));
    }

    /** Reads the document key back from a version key. */
    static DocumentKey documentKey(byte[] versionKey) {
        Tuple tuple = Tuple.fromBytes(versionKey);
        return new DocumentKey(tuple.getString(1), tuple.getString(2), tuple.getString(3),
                Instant.ofEpochSecond(-tuple.getLong(4), -tuple.getLong(5) * 1000L));
    }

    /** The key that records that a version has a string value at a path. */
    static byte[] value(DocumentKey key, String path, String value) {
        return Tuple.of(VALUES, key.namespace(), key.entity(), path, (long) value.hashCode(), key.id(),
                -key.version().getEpochSecond(), -(key.version().getNano() / 1000L)).pack();
    }

    /** The versions that may have a string value at a path: the hash can be shared by other values. */
    static Range values(String namespace, String entity, String path, String value) {
        return Tuple.of(VALUES, namespace, entity, path, (long) value.hashCode()).range();
    }

    /** Every recorded value of an entity. */
    static Range values(String namespace, String entity) {
        return Tuple.of(VALUES, namespace, entity).range();
    }

    /** Reads the document id back from a value key. */
    static String valueId(byte[] valueKey) {
        return Tuple.fromBytes(valueKey).getString(5);
    }

    /** A path as it is recorded: without array indices, so that {@code $.a[3].b} is found as {@code $.a[].b}. */
    static String path(JsonNavigationPath path) {
        StringBuilder text = new StringBuilder();
        for (String element : path.getPath()) {
            if (element.startsWith("[")) {
                text.append("[]");
            } else {
                text.append(text.isEmpty() ? "" : ".").append(element);
            }
        }
        return text.toString();
    }

    /** Calls the consumer with the recorded path and the value of every string in a document. */
    static void strings(JsonNode node, String path, BiConsumer<String, String> consumer) {
        if (node.isTextual()) {
            consumer.accept(path, node.textValue());
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                strings(element, path + "[]", consumer);
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                strings(field.getValue(), path + "." + field.getKey(), consumer);
            }
        }
    }
}
