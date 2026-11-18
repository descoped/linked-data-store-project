package io.descoped.lds.persistence.api;

import io.descoped.lds.api.specification.Specification;

import java.util.Map;
import java.util.Set;

/**
 * The service a persistence module registers ({@code provides} in {@code module-info.java} and a file in
 * {@code META-INF/services}). The application picks the provider whose {@link #name()} is configured.
 */
public interface PersistenceProvider {

    /**
     * The name this provider is selected with, for example {@code mem} or {@code postgres}.
     *
     * @return the name
     */
    String name();

    /**
     * The configuration keys this provider reads. The application passes the ones that are set; the provider
     * decides which are required and what the others default to.
     *
     * @return the keys, possibly none
     */
    Set<String> configurationKeys();

    /**
     * Connects to the engine and prepares it for the given model.
     *
     * @param configuration the values of {@link #configurationKeys()} that are set
     * @param specification the model: the managed entities and the shape of their documents
     * @return a persistence the caller closes when done
     * @throws IllegalArgumentException if a required key is missing or a value is not usable
     * @throws PersistenceException     if the engine cannot be reached or prepared
     */
    Persistence open(Map<String, String> configuration, Specification specification);
}
