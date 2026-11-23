package io.descoped.lds.api.search;

import java.util.Map;
import java.util.Set;

/**
 * Makes a durable {@link IndexQueue}, found at run time with {@link java.util.ServiceLoader}.
 */
public interface IndexQueueProvider {

    /**
     * The name that selects this provider: the name of the transaction log provider whose store it shares.
     *
     * @return the name, {@code postgres}
     */
    String providerId();

    /**
     * The configuration keys the provider reads.
     *
     * @return the keys
     */
    Set<String> configurationKeys();

    /**
     * Makes the queue, creating what it is kept in when it is missing.
     *
     * @param configuration the configuration of LDS
     * @return the queue, to be closed when it is no longer used
     */
    IndexQueue indexQueue(Map<String, String> configuration);
}
