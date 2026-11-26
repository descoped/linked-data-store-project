package io.descoped.lds.core.persistence.memory;

import io.descoped.lds.api.persistence.TransactionStatistics;
import io.descoped.tuple.Range;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

public class MemoryTransaction implements OrderedKeyValueTransaction {

    static class TransactionLogElement {
        final String index;
        final Map<byte[], byte[]> map;

        TransactionLogElement(String index, Map<byte[], byte[]> map) {
            this.index = index;
            this.map = new LinkedHashMap<>(map);
        }
    }

    final Executor executor;
    final ConcurrentMap<String, ConcurrentNavigableMap<byte[], byte[]>> mapByIndex;
    final Lock lock;
    final boolean readOnly;
    final AtomicBoolean locked = new AtomicBoolean(true);
    final TransactionStatistics statistics = new TransactionStatistics();
    final List<TransactionLogElement> transactionLog = new CopyOnWriteArrayList<>();
    /* The thread that took the lock, which alone can let it go. */
    final Thread owner;

    public MemoryTransaction(Executor executor, ConcurrentMap<String, ConcurrentNavigableMap<byte[], byte[]>> mapByIndex, Lock lock, boolean readOnly) throws InterruptedException {
        this.executor = executor;
        this.mapByIndex = mapByIndex;
        this.lock = lock;
        this.readOnly = readOnly;
        lock.lockInterruptibly();
        this.owner = Thread.currentThread();
    }

    ConcurrentNavigableMap<byte[], byte[]> getMapByIndex(String index) {
        if (!locked.get()) {
            throw new IllegalStateException("Attempting to access database from within a closed transaction.");
        }
        return mapByIndex.computeIfAbsent(index, _ -> new ConcurrentSkipListMap<>(Arrays::compareUnsigned));
    }

    /*
     * The key a selector points at, or null when there is none. A selector is: the last key less than its key
     * (or less than or equal to it, when orEqual), and from there offset keys forward. So with offset 1, orEqual
     * gives the first key greater than the key, and not orEqual the first key greater than or equal to it.
     */
    Function<KeySelector, byte[]> keySelectorFunction(String index) {
        return ks -> {
            NavigableMap<byte[], byte[]> map = getMapByIndex(index);
            if (ks.offset() == 0) {
                if (ks.orEqual()) {
                    // last less than or equal
                    return map.floorKey(ks.key());
                }
                // last less than
                return map.lowerKey(ks.key());
            }
            if (ks.orEqual()) {
                // first greater than
                return map.higherKey(ks.key());
            }
            // first greater than or equal
            return map.ceilingKey(ks.key());
        };
    }

    @Override
    public CompletableFuture<TransactionStatistics> commit() {
        return end(false);
    }

    @Override
    public CompletableFuture<TransactionStatistics> cancel() {
        return end(true);
    }

    /*
     * Ends the transaction, once: what it changed is put back when undo, the undo log is emptied and the lock let
     * go. After that an end does nothing. The lock belongs to the thread that began the transaction, so an end on
     * another thread fails and leaves the transaction as it was, for its own thread to end.
     */
    private CompletableFuture<TransactionStatistics> end(boolean undo) {
        if (Thread.currentThread() != owner) {
            return CompletableFuture.failedFuture(new IllegalStateException("A transaction is ended on the thread "
                    + "that began it, which holds the lock of the store: it was begun on " + owner.getName()
                    + " and is still open; " + Thread.currentThread().getName() + " cannot end it"));
        }
        if (!locked.get()) {
            return CompletableFuture.completedFuture(statistics);
        }
        try {
            if (undo) {
                for (int i = transactionLog.size() - 1; i >= 0; i--) {
                    TransactionLogElement element = transactionLog.get(i);
                    ConcurrentNavigableMap<byte[], byte[]> mapByPrefix = getMapByIndex(element.index);
                    for (Map.Entry<byte[], byte[]> entry : element.map.entrySet()) {
                        if (entry.getValue() == null) {
                            mapByPrefix.remove(entry.getKey());
                        } else {
                            mapByPrefix.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
            return CompletableFuture.completedFuture(statistics);
        } finally {
            transactionLog.clear();
            locked.set(false);
            lock.unlock();
        }
    }

    /* A read-only transaction holds the lock that readers share, so it must not write. */
    private void checkWritable() {
        if (readOnly) {
            throw new IllegalStateException("A read-only transaction does not write.");
        }
    }

    @Override
    public void clearRange(Range range, String index) {
        checkWritable();
        NavigableMap<byte[], byte[]> subMap = getMapByIndex(index).subMap(range.begin(), true, range.end(), false);
        transactionLog.add(new TransactionLogElement(index, subMap));
        subMap.clear();
    }

    @Override
    public void clear(byte[] key, String index) {
        checkWritable();
        byte[] previousValue = getMapByIndex(index).remove(key);
        LinkedHashMap<byte[], byte[]> map = new LinkedHashMap<>();
        map.put(key, previousValue);
        transactionLog.add(new TransactionLogElement(index, map));
    }

    @Override
    public void set(byte[] key, byte[] value, String index) {
        checkWritable();
        byte[] previousValue = getMapByIndex(index).put(key, value);
        LinkedHashMap<byte[], byte[]> map = new LinkedHashMap<>();
        map.put(key, previousValue);
        transactionLog.add(new TransactionLogElement(index, map));
    }

    @Override
    public AsyncIterable<KeyValue> getRange(Range range, String index, int limit) {
        return new MemoryAsyncIterable(executor, copy(getMapByIndex(index).subMap(range.begin(), true, range.end(), false)), limit);
    }

    @Override
    public AsyncIterable<KeyValue> getRange(KeySelector begin, KeySelector end, String index, int limit) {
        byte[] fromInclusive = keySelectorFunction(index).apply(begin);
        byte[] toExclusive = keySelectorFunction(index).apply(end);
        if (fromInclusive != null) {
            return getRangeFrom(fromInclusive, toExclusive, end, index, limit);
        }
        return getRangeWithoutFrom(toExclusive, begin, end, index, limit);
    }

    @Override
    public AsyncIterable<KeyValue> getRangeReversed(KeySelector begin, KeySelector end, String index, int limit) {
        MemoryAsyncIterable forward = (MemoryAsyncIterable) getRange(begin, end, index, ROW_LIMIT_UNLIMITED);
        return new MemoryAsyncIterable(executor, forward.map.descendingMap(), limit);
    }

    private AsyncIterable<KeyValue> getRangeFrom(byte[] fromInclusive, byte[] toExclusive, KeySelector end, String index, int limit) {
        if (toExclusive != null) {
            int compareUnsigned = Arrays.compareUnsigned(fromInclusive, toExclusive);
            if (compareUnsigned >= 0) {
                // negative or empty range
                return new MemoryAsyncIterable(executor, Collections.emptyNavigableMap(), limit);
            }
            // positive range
            return new MemoryAsyncIterable(executor, copy(getMapByIndex(index).subMap(fromInclusive, true, toExclusive, false)), limit);
        }
        // toExclusive is null
        if (end.offset() == 0) {
            // end less
            return new MemoryAsyncIterable(executor, Collections.emptyNavigableMap(), limit);
        }
        // end greater
        return new MemoryAsyncIterable(executor, copy(getMapByIndex(index).tailMap(fromInclusive, true)), limit);
    }

    /* fromInclusive == null */
    private AsyncIterable<KeyValue> getRangeWithoutFrom(byte[] toExclusive, KeySelector begin, KeySelector end, String index, int limit) {
        if (toExclusive != null) {
            if (begin.offset() == 0) {
                // begin less
                return new MemoryAsyncIterable(executor, copy(getMapByIndex(index).headMap(toExclusive, false)), limit);
            }
            // begin greater
            return new MemoryAsyncIterable(executor, Collections.emptyNavigableMap(), limit);
        }
        // toExclusive == null
        if (begin.offset() == 0 && end.offset() == 1) {
            // begin less and end greater
            return new MemoryAsyncIterable(executor, copy(getMapByIndex(index)), limit);
        }
        // begin greater or end less
        return new MemoryAsyncIterable(executor, Collections.emptyNavigableMap(), limit);
    }

    NavigableMap<byte[], byte[]> copy(NavigableMap<byte[], byte[]> source) {
        ConcurrentSkipListMap<byte[], byte[]> dest = new ConcurrentSkipListMap<>(Arrays::compareUnsigned);
        dest.putAll(source);
        return dest;
    }
}
