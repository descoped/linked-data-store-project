package io.descoped.lds.api.search;

import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Set;

/**
 * A search: the documents of a namespace whose texts match the words, as they were at a moment.
 * <p>
 * Without history, a hit is a document whose version valid at the moment (the newest up to it) matches the words and
 * is not a deletion: a word that only an older version had does not find the document, and a document deleted before
 * the moment is not found. With history, a hit is every version up to the moment that matches the words and is not a
 * deletion.
 *
 * @param namespace the namespace
 * @param text      the words, as the user wrote them: a phrase in quotes, {@code AND}, {@code OR} and {@code -word}
 *                  are understood, and several words are any of them; nothing in it names a field
 * @param entities  the entity types to search; empty for all of them
 * @param snapshot  the moment
 * @param history   whether every matching version up to the moment is a hit, and not only the valid one
 * @param from      the number of hits to skip
 * @param size      the largest number of hits to give
 */
public record SearchQuery(String namespace, String text, Set<String> entities, ZonedDateTime snapshot,
                          boolean history, long from, long size) {

    /**
     * Checks that every part is given and that the page is not negative, and keeps a copy of the entity types.
     *
     * @param namespace the namespace
     * @param text      the words
     * @param entities  the entity types to search; empty for all of them
     * @param snapshot  the moment
     * @param history   whether every matching version up to the moment is a hit
     * @param from      the number of hits to skip
     * @param size      the largest number of hits to give
     */
    public SearchQuery {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(text, "text");
        entities = Set.copyOf(Objects.requireNonNull(entities, "entities"));
        Objects.requireNonNull(snapshot, "snapshot");
        if (from < 0 || size < 0) {
            throw new IllegalArgumentException("from and size must not be negative: " + from + ", " + size);
        }
    }
}
