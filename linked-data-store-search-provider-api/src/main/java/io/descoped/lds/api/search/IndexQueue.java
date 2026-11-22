package io.descoped.lds.api.search;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The updates the search index has not taken yet. A write adds its update and is done; a worker takes updates,
 * sends them to the index, and removes them when the index has taken them, or puts them off to try again. An update
 * stays until it is removed, so one that the index could not take is not lost.
 * <p>
 * An update that is taken is the taker's for the time it is taken for, and then can be taken again, so that one LDS
 * that stops while it sends does not hold it. Several LDS can share one queue: each takes other updates.
 */
public interface IndexQueue extends AutoCloseable {

    /**
     * Adds an update; complete when it is kept.
     *
     * @param update the update
     * @return complete when the update is in the queue
     */
    Completable add(IndexUpdate update);

    /**
     * Takes the oldest updates that are not taken and not put off, at most {@code max} of them, for {@code lease}.
     *
     * @param max   the most to take
     * @param lease how long they are the taker's
     * @return the updates taken, in the order they were added; none when there are none to take
     */
    Single<List<Entry>> take(int max, Duration lease);

    /**
     * Removes updates the index has taken.
     *
     * @param entries entries that were taken
     * @return complete when they are removed
     */
    Completable remove(Collection<Entry> entries);

    /**
     * Puts off updates the index did not take: they can be taken again after {@code delay}, and each has one attempt
     * more.
     *
     * @param entries entries that were taken
     * @param delay   how long they are put off
     * @return complete when they are put off
     */
    Completable postpone(Collection<Entry> entries, Duration delay);

    /**
     * How far the index is behind.
     *
     * @return the number of updates in the queue, and when the oldest was added
     */
    Single<Lag> lag();

    /**
     * Whether the queue is kept when LDS stops: false for a queue in memory.
     *
     * @return true when the queue survives a restart
     */
    boolean durable();

    /**
     * Releases what the queue holds open; the updates in it are kept, when it is durable.
     */
    @Override
    void close();

    /**
     * An update in the queue.
     *
     * @param sequence the place of the update in the queue
     * @param update   the update
     * @param attempts how many times it was put off
     */
    record Entry(long sequence, IndexUpdate update, int attempts) {
    }

    /**
     * How far the index is behind.
     *
     * @param updates the number of updates in the queue, counted up to a limit the queue may set, so that a queue a
     *                rebuild has filled is not read whole to answer
     * @param oldest  when the oldest of them was added, or null when there are none
     */
    record Lag(long updates, Instant oldest) {
    }
}
