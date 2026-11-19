package io.descoped.lds.core.persistence.memory;

import io.descoped.tuple.Tuple;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

class MemoryDirectory {

    final int prefixLength;

    final byte[] nextPrefix;
    final ConcurrentMap<Tuple, Subspace> subspaceByTuple = new ConcurrentHashMap<>();

    MemoryDirectory(int prefixLength) {
        this.prefixLength = prefixLength;
        this.nextPrefix = new byte[prefixLength];
    }

    CompletableFuture<Subspace> createOrOpen(Tuple key) {
        Subspace subspace = subspaceByTuple.computeIfAbsent(key, k -> new Subspace(allocateUnusedShortPrefix()));
        return CompletableFuture.completedFuture(subspace);
    }

    byte[] allocateUnusedShortPrefix() {
        synchronized (nextPrefix) {
            // copy next prefix
            byte[] prefix = new byte[prefixLength];
            System.arraycopy(nextPrefix, 0, prefix, 0, prefix.length);
            // increment prefix
            for (int i = prefixLength - 1; i >= 0; i--)
                if (++nextPrefix[i] != 0) {
                    // rolled around least-significant byte, increment the more significant byte as well
                    break;
                }
            return prefix;
        }
    }
}
