package io.descoped.lds.core;

import no.cantara.sagalog.SagaLogInitializer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A saga log whose pool cannot hold a connection for each of its logs, for each log recovery opens at once and one
 * for its own queries is refused at start, before a connection is made; a saga log without a pool is not checked.
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
    void aPoolWithRoomForTheLogsRecoveryAndItsOwnQueriesIsTaken() {
        assertThatCode(() -> UndertowApplication.checkSagaLogPool(POSTGRES,
                Map.of("connection-pool.max-size", "50"), 30)).doesNotThrowAnyException();
        assertThatCode(() -> UndertowApplication.checkSagaLogPool(POSTGRES,
                Map.of("connection-pool.max-size", " 41 "), 30)).doesNotThrowAnyException();
    }

    @Test
    void aPoolOneConnectionShortIsRefusedWithTheKeysToChange() {
        assertThatThrownBy(() -> UndertowApplication.checkSagaLogPool(POSTGRES,
                Map.of("connection-pool.max-size", "40"), 30))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The saga log has 40 connections in its pool (sagalog.config.connection-pool.max-size) for"
                        + " 30 saga logs (saga.number-of-logs): each open saga log holds a connection, recovery opens"
                        + " up to 10 logs of other instances at once, and the pool needs one for its own queries. Give"
                        + " the pool at least 41 connections, or use fewer saga logs.");
    }

    /**
     * Without the key the pool has the provider's default, which is checked as well.
     */
    @Test
    void theDefaultOfTheProviderIsCheckedWhenThePoolIsNotConfigured() {
        int providerDefault = Integer.parseInt(POSTGRES.configurationOptionsAndDefaults().get("connection-pool.max-size"));

        assertThatThrownBy(() -> UndertowApplication.checkSagaLogPool(POSTGRES, Map.of(), 1))
                .hasMessageContaining("has " + providerDefault + " connections");
    }

    @Test
    void aSagaLogWithoutAPoolIsNotChecked() {
        assertThatCode(() -> UndertowApplication.checkSagaLogPool(
                initializer("no.cantara.sagalog.memory.MemorySagaLogInitializer"), Map.of(), 1000))
                .doesNotThrowAnyException();
    }
}
