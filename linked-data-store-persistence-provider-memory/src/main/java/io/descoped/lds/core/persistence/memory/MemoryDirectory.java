package io.descoped.lds.core.persistence.memory;

import io.descoped.tuple.Tuple;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The subspaces of the store, one for each tuple that names one. Each subspace gets the next number as its prefix,
 * packed as a tuple integer: a type code that tells the length, then the bytes. Such a prefix never begins another,
 * so no key is in two subspaces, however many there are.
 */
class MemoryDirectory {

    private final AtomicLong next = new AtomicLong();
    final ConcurrentMap<Tuple, Subspace> subspaceByTuple = new ConcurrentHashMap<>();

    CompletableFuture<Subspace> createOrOpen(Tuple key) {
        Subspace subspace = subspaceByTuple.computeIfAbsent(key, _ -> new Subspace(Tuple.of(next.getAndIncrement()).pack()));
        return CompletableFuture.completedFuture(subspace);
    }
}
