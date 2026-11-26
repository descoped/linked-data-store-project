package io.descoped.lds.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The saga log provider is found by the name of its class in {@code sagalog.provider}; a name that is missing or
 * that no provider has stops the start with a message that names the key and the providers there are.
 */
class SagaLogProviderAtStartTest {

    @Test
    void theProviderIsFoundByTheNameOfItsClass() {
        assertThat(UndertowApplication.sagaLogInitializer("no.cantara.sagalog.memory.MemorySagaLogInitializer")
                .getClass().getName()).isEqualTo("no.cantara.sagalog.memory.MemorySagaLogInitializer");
        assertThat(UndertowApplication.sagaLogInitializer(" no.cantara.sagalog.memory.MemorySagaLogInitializer ")
                .getClass().getName()).isEqualTo("no.cantara.sagalog.memory.MemorySagaLogInitializer");
    }

    @Test
    void anUnknownProviderNamesTheKeyAndTheProvidersThereAre() {
        assertThatThrownBy(() -> UndertowApplication.sagaLogInitializer("no.such.SagaLogInitializer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("No saga log provider no.such.SagaLogInitializer (sagalog.provider); there are: [")
                .hasMessageContaining("no.cantara.sagalog.memory.MemorySagaLogInitializer")
                .hasMessageContaining("no.cantara.sagalog.postgres.PostgresSagaLogInitializer");
    }

    @Test
    void aMissingProviderNamesTheKey() {
        for (String missing : new String[]{null, "", " "}) {
            assertThatThrownBy(() -> UndertowApplication.sagaLogInitializer(missing))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("sagalog.provider is not set: name the class of the saga log provider, "
                            + "one of [");
        }
    }
}
