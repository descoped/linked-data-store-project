package io.descoped.lds.core.health;

/**
 * The state of LDS, or of one part of it, from the best to the worst.
 */
public enum Health {

    /**
     * It answers.
     */
    UP,

    /**
     * LDS takes writes and answers reads, but not all it should: search fails, or the index is behind.
     */
    DEGRADED,

    /**
     * Writes fail: the store, the saga log, the transaction log or the index queue does not answer.
     */
    DOWN;

    /**
     * The worse of the two.
     *
     * @param other the other state
     * @return the worse state
     */
    Health worse(Health other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
