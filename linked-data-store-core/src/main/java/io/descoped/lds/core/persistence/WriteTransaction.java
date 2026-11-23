package io.descoped.lds.core.persistence;

import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.reactivex.rxjava3.core.Completable;

import java.util.function.Function;

/**
 * A write in a transaction of its own that commits only when the write completed, and is cancelled otherwise, so a
 * write that fails leaves nothing in the store.
 */
final class WriteTransaction {

    private WriteTransaction() {
    }

    /**
     * Runs the write and waits for it, then commits; when the write fails, cancels the transaction and throws what
     * the write threw.
     *
     * @param persistence the store
     * @param write       the write, given the transaction
     */
    static void run(RxJsonPersistence persistence, Function<Transaction, Completable> write) {
        Transaction tx = persistence.createTransaction(false);
        try {
            write.apply(tx).blockingAwait();
        } catch (RuntimeException | Error e) {
            try {
                tx.cancel().join();
            } catch (RuntimeException | Error c) {
                e.addSuppressed(c);
            }
            throw e;
        }
        tx.commit().join();
    }
}
