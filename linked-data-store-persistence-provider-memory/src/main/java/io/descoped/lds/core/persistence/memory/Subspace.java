package io.descoped.lds.core.persistence.memory;

import io.descoped.tuple.Range;
import io.descoped.tuple.Tuple;

import java.util.Arrays;

/**
 * A part of the key space: every key that starts with one prefix. A tuple is packed into a key of the subspace by
 * putting the prefix in front of it.
 */
public final class Subspace {

    private final byte[] prefix;

    /**
     * @param prefix the bytes every key of this subspace starts with
     */
    public Subspace(byte[] prefix) {
        this.prefix = prefix.clone();
    }

    /**
     * @param tuple the tuple to pack
     * @return the key of the tuple in this subspace
     */
    public byte[] pack(Tuple tuple) {
        return tuple.pack(prefix);
    }

    /**
     * @param key a key of this subspace
     * @return the tuple the key was packed from
     * @throws IllegalArgumentException if the key is not in this subspace
     */
    public Tuple unpack(byte[] key) {
        if (!contains(key)) {
            throw new IllegalArgumentException("The key is not in this subspace");
        }
        return Tuple.fromBytes(key, prefix.length, key.length - prefix.length);
    }

    /**
     * @return the range of every key packed from a tuple in this subspace
     */
    public Range range() {
        return rangeAfter(prefix);
    }

    /**
     * @param tuple the first elements of the tuples asked for
     * @return the range of every key of this subspace packed from a longer tuple that starts with the given one
     */
    public Range range(Tuple tuple) {
        return rangeAfter(tuple.pack(prefix));
    }

    /**
     * @param key any key
     * @return whether the key starts with the prefix of this subspace
     */
    public boolean contains(byte[] key) {
        return key.length >= prefix.length && Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length);
    }

    /* From the bytes followed by 0x00 to the bytes followed by 0xff: what a packed tuple can continue with. */
    private static Range rangeAfter(byte[] bytes) {
        byte[] begin = Arrays.copyOf(bytes, bytes.length + 1);
        byte[] end = Arrays.copyOf(bytes, bytes.length + 1);
        end[bytes.length] = (byte) 0xff;
        return new Range(begin, end);
    }
}
