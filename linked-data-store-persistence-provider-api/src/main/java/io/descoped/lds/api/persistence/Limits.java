package io.descoped.lds.api.persistence;

import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * The longest names, ids and paths LDS stores, counted in bytes of UTF-8 as the stores count them. Every provider
 * keeps a document that is within them, and LDS refuses one that is not before it reaches a store.
 * <p>
 * They are set by the store with the least room, PostgreSQL 18: one entry of its primary key holds the namespace, the
 * entity type, the id, the version, the path of a value and the positions of the arrays on that path, at most 2,704
 * bytes in all. These limits use about 2,420 of them: 64 + 128 + 1,024 + 1,024, 4 bytes for each of at most 32 array
 * positions, and about 50 for the version and the headers. Neo4j 2026 holds about 8,150 bytes in each of its keys
 * (namespace and id; namespace and the order key of the id, at most twice the id's bytes, {@link IdOrder#key}; namespace,
 * path and value), the memory provider has no limit.
 */
public final class Limits {

    /**
     * The longest namespace, in bytes of UTF-8.
     */
    public static final int NAMESPACE_BYTES = 64;

    /**
     * The longest name of an entity type, in bytes of UTF-8.
     */
    public static final int ENTITY_BYTES = 128;

    /**
     * The longest id of a document, in bytes of UTF-8.
     */
    public static final int ID_BYTES = 1024;

    /**
     * The longest path of a value as it is stored ({@code $.address.street}, {@code $.tags[]}), in bytes of UTF-8.
     */
    public static final int PATH_BYTES = 1024;

    /**
     * The most arrays on the path of a value, the arrays that hold arrays included: each is a position in the key.
     */
    public static final int ARRAY_LEVELS = 32;

    private static final int SHOWN = 80;

    private Limits() {
    }

    /**
     * The length of a text in UTF-8, in bytes, counted without encoding it.
     *
     * @param text the text
     * @return the number of bytes
     */
    public static int utf8Length(CharSequence text) {
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /**
     * What in a document goes past {@link #PATH_BYTES} or {@link #ARRAY_LEVELS}, as a text that names the path, or null
     * when nothing does. The path is the one the providers store: {@code $}, then {@code .} and the name of each field,
     * and {@code []} for each array.
     *
     * @param document the document
     * @return the text, or null
     */
    public static String overLimit(JsonNode document) {
        return overLimit(document, new StringBuilder("$"), 1, 0);
    }

    private static String overLimit(JsonNode node, StringBuilder path, int bytes, int arrays) {
        if (bytes > PATH_BYTES) {
            return "The path of a value is at most " + PATH_BYTES + " bytes in UTF-8: " + shown(path);
        }
        if (arrays > ARRAY_LEVELS) {
            return "A value is in at most " + ARRAY_LEVELS + " arrays: " + shown(path);
        }
        int length = path.length();
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                path.append('.').append(field.getKey());
                String over = overLimit(field.getValue(), path, bytes + 1 + utf8Length(field.getKey()), arrays);
                path.setLength(length);
                if (over != null) {
                    return over;
                }
            }
        } else if (node.isArray() && !node.isEmpty()) {
            path.append("[]");
            for (JsonNode element : node) {
                String over = overLimit(element, path, bytes + 2, arrays + 1);
                if (over != null) {
                    return over;
                }
            }
            path.setLength(length);
        }
        return null;
    }

    private static String shown(CharSequence path) {
        return path.length() <= SHOWN ? path.toString() : path.subSequence(0, SHOWN) + "...";
    }
}
