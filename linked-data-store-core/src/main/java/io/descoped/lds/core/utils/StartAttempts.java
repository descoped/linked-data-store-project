package io.descoped.lds.core.utils;

import io.descoped.service.provider.api.ProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * The start of a part of LDS that needs an engine (a store, the saga log, the transaction log): tried again until it
 * is made or the wait is over, because the engine may not be up yet. A fault of the configuration is not tried again:
 * a value that can never work (a name, a number or a provider that is wrong) ends the start at once.
 */
public final class StartAttempts {

    private static final Logger LOG = LoggerFactory.getLogger(StartAttempts.class);

    private StartAttempts() {
    }

    /**
     * Makes the part, trying again after a failure until the wait is over; a fault of the configuration is thrown at
     * once.
     *
     * @param what          the part, as the log names it
     * @param maxWait       how long the part may be tried, at most
     * @param retryInterval about how long to wait between two tries (see {@link Jitter})
     * @param attempt       one try: makes the part, or throws
     * @param <T>           the part
     * @return the part
     * @throws RuntimeException the failure of the last try, or the fault of the configuration
     */
    public static <T> T until(String what, Duration maxWait, Duration retryInterval, Supplier<T> attempt) {
        long start = System.nanoTime();
        for (int i = 1; ; i++) {
            try {
                LOG.info("{} initialization attempt # {}", what, i);
                return attempt.get();
            } catch (RuntimeException e) {
                if (isFaultOfConfiguration(e)) {
                    throw e;
                }
                long remainingMs = maxWait.minusNanos(System.nanoTime() - start).toMillis();
                if (remainingMs <= 0) {
                    throw e;
                }
                LOG.info("{} could not be made, tried again: {}", what, String.valueOf(e));
                try {
                    Thread.sleep(Math.min(Jitter.around(retryInterval.toMillis()), remainingMs));
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while attempting to sleep, interrupt status preserved.", e);
                }
            }
        }
    }

    /**
     * Whether a failure is one of the configuration, which no later try can mend: a value that is refused
     * ({@link IllegalArgumentException}, which a number that is not one is too) or a provider that is not there
     * ({@link ProviderException}). An engine that cannot be reached, or refuses the user, fails otherwise, and is
     * tried again.
     *
     * @param failure the failure of a try
     * @return true when the failure is a fault of the configuration
     */
    public static boolean isFaultOfConfiguration(RuntimeException failure) {
        return failure instanceof IllegalArgumentException || failure instanceof ProviderException;
    }
}
