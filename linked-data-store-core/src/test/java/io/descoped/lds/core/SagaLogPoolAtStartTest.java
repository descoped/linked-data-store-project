package io.descoped.lds.core;

import no.cantara.sagalog.SagaLogInitializer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A saga log whose pool cannot hold a connection for each of its logs and one more is refused at start, before a
 * connection is made; a saga log without a pool is not checked.
 */
class SagaLogPoolAtStartTest {

    private static final SagaLogInitializer POSTGRES = initializer("no.cantara.sagalog.postgres.PostgresSagaLogInitializer");

    /* The provider as LDS finds it, by the name of its class. */
    private static SagaLogInitializer initializer(String className) {
        return ServiceLoader.load(SagaLogInitializer.class).stream()
                .filter(provider -> provider.type().getName().equals(className))
                .findFirst().orElseThrow().get();
    }

    @Test
    void aPoolWithAConnectionForEachLogAndOneMoreIsTaken() {
        assertThatCode(() -> UndertowApplication.checkSagaLogPool(POSTGRES,
                Map.of("connection-pool.max-size", "50"), 30)).doesNotThrowAnyException();
        assertThatCode(() -> UndertowApplication.checkSagaLogPool(POSTGRES,
                Map.of("connection-pool.max-size", " 31 "), 30)).doesNotThrowAnyException();
    }

    @Test
    void aPoolWithNoMoreConnectionsThanLogsIsRefusedWithTheKeysToChange() {
        assertThatThrownBy(() -> UndertowApplication.checkSagaLogPool(POSTGRES,
                Map.of("connection-pool.max-size", "30"), 30))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The saga log has 30 connections in its pool (sagalog.config.connection-pool.max-size) for"
                        + " 30 saga logs (saga.number-of-logs): each open saga log holds a connection, and one more is"
                        + " needed to list and recover them. Give the pool at least 31 connections, or use fewer saga"
                        + " logs.");
    }

    /**
     * Without the key the pool has the provider's default, which is checked as well.
     */
    @Test
    void theDefaultOfTheProviderIsCheckedWhenThePoolIsNotConfigured() {
        int providerDefault = Integer.parseInt(POSTGRES.configurationOptionsAndDefaults().get("connection-pool.max-size"));

        assertThatCode(() -> UndertowApplication.checkSagaLogPool(POSTGRES, Map.of(), providerDefault - 1))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> UndertowApplication.checkSagaLogPool(POSTGRES, Map.of(), providerDefault))
                .hasMessageContaining("has " + providerDefault + " connections");
    }

    @Test
    void aSagaLogWithoutAPoolIsNotChecked() {
        assertThatCode(() -> UndertowApplication.checkSagaLogPool(
                initializer("no.cantara.sagalog.memory.MemorySagaLogInitializer"), Map.of(), 1000))
                .doesNotThrowAnyException();
    }
}
