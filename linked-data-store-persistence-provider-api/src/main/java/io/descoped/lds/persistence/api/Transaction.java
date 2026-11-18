package io.descoped.lds.persistence.api;

/**
 * A unit of work against one {@link Persistence}. Use it with try-with-resources and call {@link #commit()} as the
 * last statement of the block: when the block is left any other way, {@link #close()} rolls the work back.
 *
 * <p>A transaction is used by one thread at a time.
 */
public interface Transaction extends AutoCloseable {

    /**
     * Makes the work of this transaction durable and visible to others. Nothing more can be done with the
     * transaction afterwards.
     *
     * @throws IllegalStateException if the transaction is already committed or closed
     * @throws PersistenceException  if the engine could not commit; the work is then rolled back
     */
    void commit();

    /**
     * Rolls the work back unless it was committed, and releases what the transaction holds. Closing twice does
     * nothing.
     */
    @Override
    void close();
}
