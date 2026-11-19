package io.descoped.lds.core.persistence.memory;

import java.util.Arrays;

/**
 * Points at a key of the store relative to a given key, which need not be in the store. The key pointed at is found
 * in two steps: the last key less than the given key (or less than or equal to it, when {@code orEqual}), and from
 * there {@code offset} keys forward. Offsets 0 and 1 are what this provider resolves.
 * <p>
 * Two selectors are equal when their keys hold the same bytes and the rest is the same.
 *
 * @param key     the key the selector is relative to
 * @param orEqual whether the first step stops at the given key itself when it is in the store
 * @param offset  the number of keys to go forward from there
 */
public record KeySelector(byte[] key, boolean orEqual, int offset) {

    /**
     * @param key the key the selector is relative to
     * @return a selector of the first key greater than or equal to the given key
     */
    public static KeySelector firstGreaterOrEqual(byte[] key) {
        return new KeySelector(key, false, 1);
    }

    /**
     * @param key the key the selector is relative to
     * @return a selector of the last key less than or equal to the given key
     */
    public static KeySelector lastLessOrEqual(byte[] key) {
        return new KeySelector(key, true, 0);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KeySelector(byte[] otherKey, boolean otherOrEqual, int otherOffset)
                && Arrays.equals(key, otherKey) && orEqual == otherOrEqual && offset == otherOffset;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(key) + Boolean.hashCode(orEqual)) + offset;
    }

    @Override
    public String toString() {
        return "KeySelector[key=" + Arrays.toString(key) + ", orEqual=" + orEqual + ", offset=" + offset + "]";
    }
}
