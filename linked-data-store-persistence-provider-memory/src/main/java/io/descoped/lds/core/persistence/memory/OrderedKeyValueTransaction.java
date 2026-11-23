package io.descoped.lds.core.persistence.memory;

import io.descoped.lds.api.persistence.Transaction;
import io.descoped.tuple.Range;

/**
 * A transaction on a store of ordered keys, which is what the logic of this provider is written against. The keys
 * of each index are ordered as unsigned bytes.
 */
public interface OrderedKeyValueTransaction extends Transaction {

    /**
     * The limit that means every entry of a range.
     */
    int ROW_LIMIT_UNLIMITED = 0;

    void clearRange(Range range, String index);

    void set(byte[] key, byte[] value, String index);

    void clear(byte[] key, String index);

    AsyncIterable<KeyValue> getRange(Range range, String index, int limit);

    AsyncIterable<KeyValue> getRange(KeySelector begin, KeySelector end, String index, int limit);

    /**
     * The same entries as {@link #getRange(KeySelector, KeySelector, String, int)}, from the last key to the first.
     *
     * @param begin the first key of the range
     * @param end   the key after the last of the range
     * @param index the index
     * @param limit the most entries to give, or {@link #ROW_LIMIT_UNLIMITED}
     * @return the entries, last key first
     */
    AsyncIterable<KeyValue> getRangeReversed(KeySelector begin, KeySelector end, String index, int limit);
}
