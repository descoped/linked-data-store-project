package io.descoped.lds.persistence.memory;

import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.persistence.api.Persistence;
import io.descoped.lds.persistence.tck.PersistenceTck;

import java.util.Map;

class MemoryPersistenceTckTest extends PersistenceTck {

    @Override
    protected Persistence open(Specification specification) {
        return new MemoryPersistenceProvider().open(Map.of(), specification);
    }
}
