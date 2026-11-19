package io.descoped.lds.core.persistence.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static io.descoped.lds.core.persistence.memory.OrderedKeyValueTransaction.ROW_LIMIT_UNLIMITED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key a selector points at in a store that holds the keys 1, 3 and 5.
 */
class KeySelectorTest {

    private static final String INDEX = "i";

    private MemoryTransactionFactory factory;
    private MemoryTransaction tx;
    private Function<KeySelector, byte[]> keyOf;

    @BeforeEach
    void fill() {
        factory = new MemoryTransactionFactory(false);
        tx = factory.createTransaction(false);
        for (int key : new int[]{1, 3, 5}) {
            tx.set(new byte[]{(byte) key}, new byte[0], INDEX);
        }
        keyOf = tx.keySelectorFunction(INDEX);
    }

    @AfterEach
    void close() {
        tx.cancel();
        factory.close();
    }

    @Test
    void firstGreaterOrEqual() {
        assertThat(keyOf.apply(KeySelector.firstGreaterOrEqual(key(3)))).containsExactly(3);
        assertThat(keyOf.apply(KeySelector.firstGreaterOrEqual(key(4)))).containsExactly(5);
        assertThat(keyOf.apply(KeySelector.firstGreaterOrEqual(key(6)))).isNull();
    }

    @Test
    void firstGreaterThan() {
        assertThat(keyOf.apply(new KeySelector(key(3), true, 1))).containsExactly(5);
        assertThat(keyOf.apply(new KeySelector(key(2), true, 1))).containsExactly(3);
        assertThat(keyOf.apply(new KeySelector(key(5), true, 1))).isNull();
    }

    @Test
    void lastLessOrEqual() {
        assertThat(keyOf.apply(KeySelector.lastLessOrEqual(key(3)))).containsExactly(3);
        assertThat(keyOf.apply(KeySelector.lastLessOrEqual(key(4)))).containsExactly(3);
        assertThat(keyOf.apply(KeySelector.lastLessOrEqual(key(0)))).isNull();
    }

    @Test
    void lastLessThan() {
        assertThat(keyOf.apply(new KeySelector(key(3), false, 0))).containsExactly(1);
        assertThat(keyOf.apply(new KeySelector(key(4), false, 0))).containsExactly(3);
        assertThat(keyOf.apply(new KeySelector(key(1), false, 0))).isNull();
    }

    @Test
    void aKeyThatReadsTrueIsFoundAsAnyOther() {
        byte[] key = "true".getBytes(StandardCharsets.UTF_8);
        tx.set(key, new byte[0], INDEX);

        assertThat(keyOf.apply(KeySelector.firstGreaterOrEqual(key))).isEqualTo(key);
    }

    @Test
    void aRangeRunsFromItsFirstSelectorToBeforeItsSecond() {
        assertThat(keysOf(KeySelector.firstGreaterOrEqual(key(1)), KeySelector.firstGreaterOrEqual(key(5)), ROW_LIMIT_UNLIMITED))
                .containsExactly(key(1), key(3));
        assertThat(keysOf(KeySelector.firstGreaterOrEqual(key(2)), KeySelector.firstGreaterOrEqual(key(9)), ROW_LIMIT_UNLIMITED))
                .containsExactly(key(3), key(5));
        assertThat(keysOf(KeySelector.firstGreaterOrEqual(key(5)), KeySelector.firstGreaterOrEqual(key(1)), ROW_LIMIT_UNLIMITED))
                .isEmpty();
    }

    @Test
    void aRangeHoldsNoMoreThanItsLimit() {
        assertThat(keysOf(KeySelector.firstGreaterOrEqual(key(0)), KeySelector.firstGreaterOrEqual(key(9)), 2))
                .containsExactly(key(1), key(3));
    }

    @Test
    void selectorsAreEqualWhenTheirBytesAndTheRestAre() {
        assertThat(KeySelector.firstGreaterOrEqual(key(1))).isEqualTo(new KeySelector(key(1), false, 1))
                .hasSameHashCodeAs(new KeySelector(key(1), false, 1))
                .isNotEqualTo(KeySelector.firstGreaterOrEqual(key(2)))
                .isNotEqualTo(new KeySelector(key(1), true, 1))
                .isNotEqualTo(KeySelector.lastLessOrEqual(key(1)));
    }

    @Test
    void entriesAreEqualWhenTheirBytesAre() {
        assertThat(new KeyValue(key(1), key(2))).isEqualTo(new KeyValue(key(1), key(2))).hasSameHashCodeAs(new KeyValue(key(1), key(2)));
        assertThat(new KeyValue(key(1), key(2))).isNotEqualTo(new KeyValue(key(1), key(3))).isNotEqualTo(new KeyValue(key(2), key(2)));
    }

    private List<byte[]> keysOf(KeySelector begin, KeySelector end, int limit) {
        List<byte[]> keys = new ArrayList<>();
        AsyncIterator<KeyValue> iterator = tx.getRange(begin, end, INDEX, limit).iterator();
        while (iterator.hasNext()) {
            keys.add(iterator.next().key());
        }
        return keys;
    }

    private static byte[] key(int value) {
        return new byte[]{(byte) value};
    }
}
