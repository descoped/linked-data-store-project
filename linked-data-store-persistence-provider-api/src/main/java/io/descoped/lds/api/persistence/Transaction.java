package io.descoped.lds.api.persistence;

import java.util.concurrent.CompletableFuture;

public interface Transaction extends AutoCloseable {

    CompletableFuture<TransactionStatistics> commit();

    CompletableFuture<TransactionStatistics> cancel();

    @Override
    default void close() {
        boolean committed = false;
        try {
            // join throws only unchecked exceptions (CompletionException, CancellationException), which pass as they are
            commit().join();
            committed = true;
        } finally {
            if (!committed) {
                cancel().join();
            }
        }
    }
}
