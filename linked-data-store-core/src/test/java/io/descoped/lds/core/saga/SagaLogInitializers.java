package io.descoped.lds.core.saga;

import no.cantara.sagalog.SagaLogInitializer;

import java.util.ServiceLoader;

/**
 * The saga logs of the saga library, found as LDS finds the one {@code sagalog.provider} names: as the service whose
 * class has that name. Their packages are not exported, so a test cannot name their classes.
 */
final class SagaLogInitializers {

    static final String MEMORY = "no.cantara.sagalog.memory.MemorySagaLogInitializer";
    static final String POSTGRES = "no.cantara.sagalog.postgres.PostgresSagaLogInitializer";

    private SagaLogInitializers() {
    }

    static SagaLogInitializer named(String type) {
        return ServiceLoader.load(SagaLogInitializer.class).stream()
                .filter(provider -> type.equals(provider.type().getName()))
                .findFirst().orElseThrow().get();
    }
}
