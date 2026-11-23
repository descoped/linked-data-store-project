package io.descoped.lds.api.search;

import io.descoped.lds.api.persistence.DocumentKey;

import java.util.Objects;

/**
 * One hit of a search: the version of a document that matched.
 *
 * @param key the namespace, entity type, id and version of the document that matched
 */
public record SearchHit(DocumentKey key) {

    /**
     * Checks that the key is given.
     *
     * @param key the key
     */
    public SearchHit {
        Objects.requireNonNull(key, "key");
    }
}
