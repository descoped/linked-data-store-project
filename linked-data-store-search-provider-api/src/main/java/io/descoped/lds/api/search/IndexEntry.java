package io.descoped.lds.api.search;

import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the search index holds of one version of one document: the texts of the document, each with the language it is
 * searched in, or, for a deletion, that the document is deleted from this version on. An entry is never changed: a
 * new version of the document is a new entry.
 *
 * @param namespace the namespace
 * @param entity    the entity type
 * @param id        the id of the document
 * @param version   the version
 * @param deleted   whether the document is deleted at this version
 * @param texts     the texts of the version by the language they are searched in; empty for a deletion
 */
public record IndexEntry(String namespace, String entity, String id, ZonedDateTime version, boolean deleted,
                         Map<Language, List<String>> texts) {

    /**
     * Checks that every part is given, and keeps a copy of the texts.
     *
     * @param namespace the namespace
     * @param entity    the entity type
     * @param id        the id of the document
     * @param version   the version
     * @param deleted   whether the document is deleted at this version
     * @param texts     the texts by language
     */
    public IndexEntry {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Map<Language, List<String>> copy = new EnumMap<>(Language.class);
        Objects.requireNonNull(texts, "texts").forEach((language, values) -> copy.put(language, List.copyOf(values)));
        texts = Map.copyOf(copy);
    }

    /**
     * The entry of a version that is written.
     *
     * @param namespace the namespace
     * @param entity    the entity type
     * @param id        the id of the document
     * @param version   the version
     * @param texts     the texts of the version by language
     * @return the entry
     */
    public static IndexEntry written(String namespace, String entity, String id, ZonedDateTime version,
                                     Map<Language, List<String>> texts) {
        return new IndexEntry(namespace, entity, id, version, false, texts);
    }

    /**
     * The entry of a deletion.
     *
     * @param namespace the namespace
     * @param entity    the entity type
     * @param id        the id of the document
     * @param version   the version the document is deleted at
     * @return the entry
     */
    public static IndexEntry deleted(String namespace, String entity, String id, ZonedDateTime version) {
        return new IndexEntry(namespace, entity, id, version, true, Map.of());
    }
}
