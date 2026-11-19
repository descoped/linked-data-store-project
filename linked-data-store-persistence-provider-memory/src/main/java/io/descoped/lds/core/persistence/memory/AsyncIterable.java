package io.descoped.lds.core.persistence.memory;

/**
 * A result that is read one item at a time, each when it is asked for.
 *
 * @param <T> the type of the items
 */
public interface AsyncIterable<T> {

    /**
     * @return an iterator over the items of the result
     */
    AsyncIterator<T> iterator();
}
