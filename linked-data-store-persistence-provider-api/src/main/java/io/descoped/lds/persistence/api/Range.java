package io.descoped.lds.persistence.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Which part of an ordered result to return: the keys strictly between two bounds, and of those at most a number
 * counted from the start or from the end. This is cursor paging: the bounds are the keys of results the caller
 * already has.
 *
 * @param after    only keys after this one, or {@code null} for no lower bound
 * @param before   only keys before this one, or {@code null} for no upper bound
 * @param limit    the most results to return, {@link #UNLIMITED} for all
 * @param backward {@code false} to count from the start and return ascending order, {@code true} to count from the
 *                 end and return descending order
 * @param <T>      the key type: a document id or a version
 */
public record Range<T>(T after, T before, int limit, boolean backward) {

    /**
     * The limit that means every result.
     */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    /**
     * Checks the limit.
     *
     * @throws IllegalArgumentException if the limit is negative
     */
    public Range {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative: " + limit);
        }
    }

    /**
     * Every result, ascending.
     *
     * @param <T> the key type
     * @return the range
     */
    public static <T> Range<T> unbounded() {
        return new Range<>(null, null, UNLIMITED, false);
    }

    /**
     * Every result strictly between two keys, ascending.
     *
     * @param after  the lower bound, or {@code null}
     * @param before the upper bound, or {@code null}
     * @param <T>    the key type
     * @return the range
     */
    public static <T> Range<T> between(T after, T before) {
        return new Range<>(after, before, UNLIMITED, false);
    }

    /**
     * The first results, ascending.
     *
     * @param limit how many
     * @param <T>   the key type
     * @return the range
     */
    public static <T> Range<T> first(int limit) {
        return new Range<>(null, null, limit, false);
    }

    /**
     * The first results after a key, ascending.
     *
     * @param limit how many
     * @param after the lower bound
     * @param <T>   the key type
     * @return the range
     */
    public static <T> Range<T> firstAfter(int limit, T after) {
        return new Range<>(after, null, limit, false);
    }

    /**
     * The first results between two keys, ascending.
     *
     * @param limit  how many
     * @param after  the lower bound
     * @param before the upper bound
     * @param <T>    the key type
     * @return the range
     */
    public static <T> Range<T> firstBetween(int limit, T after, T before) {
        return new Range<>(after, before, limit, false);
    }

    /**
     * The last results, descending.
     *
     * @param limit how many
     * @param <T>   the key type
     * @return the range
     */
    public static <T> Range<T> last(int limit) {
        return new Range<>(null, null, limit, true);
    }

    /**
     * The last results before a key, descending.
     *
     * @param limit  how many
     * @param before the upper bound
     * @param <T>    the key type
     * @return the range
     */
    public static <T> Range<T> lastBefore(int limit, T before) {
        return new Range<>(null, before, limit, true);
    }

    /**
     * The last results between two keys, descending.
     *
     * @param limit  how many
     * @param after  the lower bound
     * @param before the upper bound
     * @param <T>    the key type
     * @return the range
     */
    public static <T> Range<T> lastBetween(int limit, T after, T before) {
        return new Range<>(after, before, limit, true);
    }

    /**
     * Whether there is a limit.
     *
     * @return {@code false} when the limit is {@link #UNLIMITED}
     */
    public boolean limited() {
        return limit != UNLIMITED;
    }

    /**
     * The same range with its bounds converted to another key type.
     *
     * @param mapper converts a bound; not called for a missing bound
     * @param <R>    the new key type
     * @return the converted range
     */
    public <R> Range<R> map(Function<? super T, ? extends R> mapper) {
        return new Range<>(after == null ? null : mapper.apply(after), before == null ? null : mapper.apply(before),
                limit, backward);
    }

    /**
     * Applies this range to a complete result. A provider that cannot leave the selection to its engine uses this,
     * so that every provider selects the same way.
     *
     * @param ascending every candidate, ordered ascending by key
     * @param key       the key of a candidate
     * @param order     the order of the keys
     * @param <E>       the candidate type
     * @return the selected candidates: ascending, or descending when this range is backward
     */
    public <E> List<E> select(List<E> ascending, Function<? super E, ? extends T> key, Comparator<? super T> order) {
        List<E> selected = new ArrayList<>();
        int size = ascending.size();
        for (int i = 0; i < size && selected.size() < limit; i++) {
            E candidate = ascending.get(backward ? size - 1 - i : i);
            T candidateKey = key.apply(candidate);
            boolean afterLower = after == null || order.compare(candidateKey, after) > 0;
            boolean beforeUpper = before == null || order.compare(candidateKey, before) < 0;
            if (afterLower && beforeUpper) {
                selected.add(candidate);
            } else if (backward ? !afterLower : !beforeUpper) {
                break;
            }
        }
        return selected;
    }
}
