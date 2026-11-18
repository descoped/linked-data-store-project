package io.descoped.lds.persistence.api;

import java.util.Optional;

/**
 * A reference from one document to another, written {@code /entity/id} in the referring document.
 *
 * @param entity the entity name of the document referred to
 * @param id     the id of the document referred to
 */
public record Link(String entity, String id) {

    /**
     * Checks the names.
     *
     * @throws IllegalArgumentException if a name is not valid
     */
    public Link {
        Identifiers.requireEntity(entity);
        Identifiers.requireId(id);
    }

    /**
     * Reads a link from a JSON string value.
     *
     * @param text the value, may be {@code null}
     * @return the link, or empty when the value is not of the form {@code /entity/id} with valid names
     */
    public static Optional<Link> parse(String text) {
        if (text == null || text.length() < 4 || text.charAt(0) != '/') {
            return Optional.empty();
        }
        int separator = text.indexOf('/', 1);
        if (separator < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Link(text.substring(1, separator), text.substring(separator + 1)));
        } catch (IllegalArgumentException notALink) {
            return Optional.empty();
        }
    }

    /**
     * The link as it is written in a document.
     *
     * @return {@code /entity/id}
     */
    @Override
    public String toString() {
        return "/" + entity + "/" + id;
    }
}
