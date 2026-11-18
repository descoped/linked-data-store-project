/**
 * The in-memory persistence provider, {@code mem}.
 */
module io.descoped.lds.persistence.memory {
    requires transitive io.descoped.lds.persistence.api;
    requires io.descoped.tuple;
    requires com.fasterxml.jackson.databind;

    exports io.descoped.lds.persistence.memory;

    provides io.descoped.lds.persistence.api.PersistenceProvider
            with io.descoped.lds.persistence.memory.MemoryPersistenceProvider;
}
