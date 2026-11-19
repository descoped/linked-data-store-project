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
}
