package io.descoped.lds.core.persistence.memory;

import java.util.concurrent.CompletableFuture;

/**
 * Walks a result one item at a time. Whether there is a next item can be asked without blocking.
 *
 * @param <T> the type of the items
 */
public interface AsyncIterator<T> {

    /**
     * @return a future of whether there is a next item
     */
    CompletableFuture<Boolean> onHasNext();

    /**
     * @return whether there is a next item
     */
    boolean hasNext();

    /**
     * @return the next item
     */
    T next();
}
