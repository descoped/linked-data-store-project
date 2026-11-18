package io.descoped.lds.persistence.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Identifies one version of one document.
 *
 * @param namespace the namespace, see {@link Identifiers#requireNamespace(String)}
 * @param entity    the entity name, see {@link Identifiers#requireEntity(String)}
 * @param id        the document id, see {@link Identifiers#requireId(String)}
 * @param version   the instant this version is valid from, cut to microseconds
 */
public record DocumentKey(String namespace, String entity, String id, Instant version) {

    /**
     * Checks the names and cuts the version to microseconds, the precision every provider keeps.
     *
     * @throws IllegalArgumentException if a name is not valid
     */
    public DocumentKey {
        Identifiers.requireNamespace(namespace);
        Identifiers.requireEntity(entity);
        Identifiers.requireId(id);
        version = truncate(Objects.requireNonNull(version, "version"));
    }

    /**
     * Cuts an instant to the precision versions are kept with.
     *
     * @param instant any instant
     * @return the instant without what is below a microsecond
     */
    public static Instant truncate(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The link other documents use to refer to this document.
     *
     * @return the link, {@code /entity/id}
     */
    public Link link() {
        return new Link(entity, id);
    }
}
