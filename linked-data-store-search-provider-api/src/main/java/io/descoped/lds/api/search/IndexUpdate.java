package io.descoped.lds.api.search;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * What the search index is to be told about one version of one document: that it is written, or deleted. The document
 * itself is not held; it is read from storage, at its version, when the update is sent.
 *
 * @param namespace the namespace
 * @param entity    the entity type
 * @param id        the id of the document
 * @param version   the version, with the zone it was written with (the index names a version by it)
 * @param operation written or deleted
 */
public record IndexUpdate(String namespace, String entity, String id, ZonedDateTime version, Operation operation) {

    /**
     * What happened to the document.
     */
    public enum Operation {
        /**
         * The version was written: the index is given the document.
         */
        CREATE_OR_OVERWRITE,
        /**
         * The document was deleted at the version: the index takes it out.
         */
        DELETE
    }

    /**
     * Checks that every part is given.
     *
     * @param namespace the namespace
     * @param entity    the entity type
     * @param id        the id of the document
     * @param version   the version
     * @param operation written or deleted
     */
    public IndexUpdate {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(operation, "operation");
    }
}
