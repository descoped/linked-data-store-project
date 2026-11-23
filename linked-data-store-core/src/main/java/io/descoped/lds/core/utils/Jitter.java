package io.descoped.lds.core.utils;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A wait between two tries that is not the same each time. Several LDS that start together and find a store not ready,
 * or without room for all of them, would otherwise try again at the same moments and keep meeting; a random wait
 * spreads their tries.
 */
public final class Jitter {

    private Jitter() {
    }

    /**
     * A wait of the given length, give or take half of it, drawn anew at each call.
     *
     * @param millis the length of the wait, in milliseconds, not negative
     * @return a wait from half the length to one and a half times it
     */
    public static long around(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("a wait is not negative: " + millis);
        }
        if (millis == 0) {
            return 0;
        }
        return ThreadLocalRandom.current().nextLong(millis / 2, millis + millis / 2 + 1);
    }
}
