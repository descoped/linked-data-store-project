package io.descoped.lds.persistence.memory;

import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.persistence.api.Persistence;
import io.descoped.lds.persistence.api.PersistenceProvider;

import java.util.Map;
import java.util.Set;

/**
 * Opens persistences that keep their documents in memory. Each {@link #open open} returns an empty one of its own.
 */
public final class MemoryPersistenceProvider implements PersistenceProvider {

    /**
     * The name this provider is selected with.
     */
    public static final String NAME = "mem";

    /**
     * Creates the provider.
     */
    public MemoryPersistenceProvider() {
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     *
     * @return no keys: there is nothing to configure
     */
    @Override
    public Set<String> configurationKeys() {
        return Set.of();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The model is not used: this provider stores any document of any entity.
     */
    @Override
    public Persistence open(Map<String, String> configuration, Specification specification) {
        return new MemoryPersistence();
    }
}
