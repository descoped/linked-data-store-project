package io.descoped.lds.core.persistence.memory;

import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The start of the provider from its configuration.
 */
class MemoryInitializerTest {

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

    private static RxJsonPersistence start(String capacity) {
        return new MemoryInitializer().initialize("ns", Map.of("persistence.fragment.capacity", capacity), Set.of());
    }
}
