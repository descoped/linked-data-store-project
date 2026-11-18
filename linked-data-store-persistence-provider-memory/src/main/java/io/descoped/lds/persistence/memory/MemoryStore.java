package io.descoped.lds.persistence.memory;

import com.fasterxml.jackson.databind.JsonNode;
import io.descoped.lds.persistence.api.DocumentKey;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The committed data: an ordered map from packed keys to values. The lock is held for the length of one call, never
 * across calls, so a transaction can move between threads.
 */
final class MemoryStore {

    /** The value an overlay uses for a key its transaction removed. */
    static final Object REMOVED = new Object();

    final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** Set when the persistence is closed: nothing reads or commits after that. */
    volatile boolean closed;
    private final NavigableMap<byte[], Object> committed = new TreeMap<>(Arrays::compareUnsigned);

    /** A new, empty map with the store's key order. */
    static NavigableMap<byte[], Object> newMap() {
        return new TreeMap<>(Arrays::compareUnsigned);
    }

    /**
     * Applies a transaction's writes. The caller holds the write lock.
     *
     * <p>A version this transaction replaces or removes may have been committed by another transaction after this
     * one looked at it. The values recorded for that content are removed here, where it is known what is being
     * replaced, so that they do not stay behind for good.
     */
    void apply(NavigableMap<byte[], Object> overlay) {
        overlay.forEach((key, value) -> {
            if (committed.get(key) instanceof JsonNode replaced && Keys.isVersion(key)) {
                DocumentKey documentKey = Keys.documentKey(key);
                Keys.strings(replaced, "$", (path, text) -> {
                    byte[] recorded = Keys.value(documentKey, path, text);
                    if (!overlay.containsKey(recorded)) {
                        committed.remove(recorded);
                    }
                });
            }
        });
        overlay.forEach((key, value) -> {
            if (value == REMOVED) {
                committed.remove(key);
            } else {
                committed.put(key, value);
            }
        });
    }

    /** How many keys are committed. The caller holds the read lock. */
    int size() {
        return committed.size();
    }

    /** The committed value of a key, or the overlay's. The caller holds the read lock. */
    Object get(NavigableMap<byte[], Object> overlay, byte[] key) {
        Object value = overlay.containsKey(key) ? overlay.get(key) : committed.get(key);
        return value == REMOVED ? null : value;
    }

    /**
     * The entries with {@code begin <= key < end} as a transaction sees them: committed data with the overlay on
     * top. The caller holds the read lock until it is done with the iterator.
     */
    Iterator<Map.Entry<byte[], Object>> scan(NavigableMap<byte[], Object> overlay, byte[] begin, byte[] end,
                                             boolean reverse) {
        Iterator<Map.Entry<byte[], Object>> base = directed(committed, begin, end, reverse);
        Iterator<Map.Entry<byte[], Object>> top = directed(overlay, begin, end, reverse);
        return new Iterator<>() {
            private Map.Entry<byte[], Object> nextBase = advance(base);
            private Map.Entry<byte[], Object> nextTop = advance(top);
            private Map.Entry<byte[], Object> next = merge();

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public Map.Entry<byte[], Object> next() {
                if (next == null) {
                    throw new NoSuchElementException();
                }
                Map.Entry<byte[], Object> current = next;
                next = merge();
                return current;
            }

            private Map.Entry<byte[], Object> merge() {
                while (nextBase != null || nextTop != null) {
                    int order = nextBase == null ? 1 : nextTop == null ? -1
                            : Arrays.compareUnsigned(nextBase.getKey(), nextTop.getKey()) * (reverse ? -1 : 1);
                    Map.Entry<byte[], Object> chosen;
                    if (order < 0) {
                        chosen = nextBase;
                        nextBase = advance(base);
                    } else {
                        chosen = nextTop;
                        nextTop = advance(top);
                        if (order == 0) {
                            nextBase = advance(base);
                        }
                    }
                    if (chosen.getValue() != REMOVED) {
                        return chosen;
                    }
                }
                return null;
            }
        };
    }

    private static Iterator<Map.Entry<byte[], Object>> directed(NavigableMap<byte[], Object> map, byte[] begin,
                                                                byte[] end, boolean reverse) {
        if (Arrays.compareUnsigned(begin, end) >= 0) {
            return Map.<byte[], Object>of().entrySet().iterator();
        }
        NavigableMap<byte[], Object> part = map.subMap(begin, true, end, false);
        return (reverse ? part.descendingMap() : part).entrySet().iterator();
    }

    private static Map.Entry<byte[], Object> advance(Iterator<Map.Entry<byte[], Object>> iterator) {
        return iterator.hasNext() ? iterator.next() : null;
    }
}
