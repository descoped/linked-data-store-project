package io.descoped.lds.core.persistence.memory;

import java.util.Arrays;

/**
 * One entry of the store. Two entries are equal when their keys and their values hold the same bytes.
 *
 * @param key   the key
 * @param value the value
 */
public record KeyValue(byte[] key, byte[] value) {

    @Override
    public boolean equals(Object other) {
        return other instanceof KeyValue(byte[] otherKey, byte[] otherValue)
                && Arrays.equals(key, otherKey) && Arrays.equals(value, otherValue);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(key) + Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "KeyValue[key=" + Arrays.toString(key) + ", value=" + Arrays.toString(value) + "]";
    }
}
