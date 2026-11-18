package io.descoped.lds.persistence.memory;

import io.descoped.lds.persistence.api.Transaction;

import java.util.NavigableMap;

/**
 * A transaction's own writes, kept apart from the committed data until {@link #commit()}.
 */
final class MemoryTransaction implements Transaction {

    private enum State { OPEN, COMMITTED, CLOSED }

    final MemoryStore store;
    final boolean readOnly;
    final NavigableMap<byte[], Object> overlay = MemoryStore.newMap();
    private State state = State.OPEN;

    MemoryTransaction(MemoryStore store, boolean readOnly) {
        this.store = store;
        this.readOnly = readOnly;
    }

    /** Fails unless the transaction can still be used, and for writing when that is asked. */
    synchronized void require(boolean writing) {
        if (state != State.OPEN) {
            throw new IllegalStateException("The transaction is " + (state == State.COMMITTED ? "committed" : "closed"));
        }
        if (store.closed) {
            overlay.clear();
            state = State.CLOSED;
            throw new IllegalStateException("The persistence is closed");
        }
        if (writing && readOnly) {
            throw new IllegalStateException("The transaction is read-only");
        }
    }

    @Override
    public synchronized void commit() {
        require(false);
        store.lock.writeLock().lock();
        try {
            store.apply(overlay);
        } finally {
            store.lock.writeLock().unlock();
        }
        overlay.clear();
        state = State.COMMITTED;
    }

    @Override
    public synchronized void close() {
        if (state == State.OPEN) {
            overlay.clear();
            state = State.CLOSED;
        }
    }
}
