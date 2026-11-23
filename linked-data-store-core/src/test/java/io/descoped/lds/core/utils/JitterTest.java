package io.descoped.lds.core.utils;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wait between two tries at start: the length given, give or take half, and not the same each time.
 */
class JitterTest {

    @Test
    void aWaitIsFromHalfToOneAndAHalfTimesItsLength() {
        LongStream.range(0, 10_000).map(_ -> Jitter.around(2000))
                .forEach(wait -> assertThat(wait).isBetween(1000L, 3000L));
    }

    @Test
    void waitsDifferSoThatTriesThatStartedTogetherSpreadOut() {
        Set<Long> waits = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            waits.add(Jitter.around(2000));
        }
        assertThat(waits).hasSizeGreaterThan(50);
    }

    @Test
    void bothEndsOfTheRangeAreReached() {
        Set<Long> waits = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            waits.add(Jitter.around(2));
        }
        assertThat(waits).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void noWaitStaysNone() {
        assertThat(Jitter.around(0)).isZero();
    }

    @Test
    void aNegativeWaitIsRefused() {
        assertThatThrownBy(() -> Jitter.around(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
