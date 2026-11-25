package io.descoped.lds.core.persistence.memory;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;

import static io.descoped.lds.api.persistence.json.JsonTools.mapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The start of the provider from its configuration.
 */
class MemoryInitializerTest {

    private static final ZonedDateTime T = ZonedDateTime.parse("2020-01-01T00:00:00Z");

    /* 0 would cut a text for ever while the write holds the lock of the store, a negative value fail every write. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", ""})
    void aFragmentCapacityThatCannotWorkStopsTheStartAndNamesTheKey(String capacity) {
        assertThatThrownBy(() -> start(capacity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("persistence.fragment.capacity");
    }

    @Test
    void aFragmentCapacityOfOneByteStarts() {
        RxJsonPersistence persistence = start("1");
        assertThat(persistence).isNotNull();
        persistence.close();
    }

    /*
     * The provider is given only the keys it names (configurationKeys), so a key it does not name changes nothing:
     * a transaction that is closed is committed, as in LDS.
     */
    @Test
    void aKeyTheProviderDoesNotNameChangesNothing() {
        RxJsonPersistence persistence = new MemoryInitializer().initialize("ns", Map.of(
                "persistence.fragment.capacity", "8192",
                "persistence.mem.transaction.cancel-on-close", "true"), Set.of());
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.createOrOverwrite(tx, Flowable.just(new JsonDocument(new DocumentKey("ns", "Person", "ola", T),
                    mapper.createObjectNode().put("name", "Ola"))), null).blockingAwait();
        }
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(persistence.readDocument(tx, T, "ns", "Person", "ola").blockingGet()).isNotNull();
        }
        persistence.close();
    }

    private static RxJsonPersistence start(String capacity) {
        return new MemoryInitializer().initialize("ns", Map.of("persistence.fragment.capacity", capacity), Set.of());
    }
}
