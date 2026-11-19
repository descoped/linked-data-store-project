package io.descoped.lds.core.persistence.memory;

import java.util.NavigableMap;
import java.util.concurrent.Executor;

public class MemoryAsyncIterable implements AsyncIterable<KeyValue> {

    private final Executor executor;
    final NavigableMap<byte[], byte[]> map;
    private final int limit;

    public MemoryAsyncIterable(Executor executor, NavigableMap<byte[], byte[]> map, int limit) {
        this.executor = executor;
        this.map = map;
        this.limit = limit;
    }

    @Override
    public AsyncIterator<KeyValue> iterator() {
        return new MemoryAsyncIterator(executor, map, limit);
    }
}
