package io.descoped.lds.core.search;

import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.IndexQueueProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;
import java.util.Set;

/**
 * Makes the index queue where the transaction log is kept: in its store when a provider of that name is found
 * ({@code postgres}), in memory when the transaction log keeps nothing or keeps it in memory.
 */
public final class IndexQueueConfigurator {

    private static final Logger LOG = LoggerFactory.getLogger(IndexQueueConfigurator.class);

    /*
     * The transaction log providers that keep nothing durable: the queue is then in memory.
     */
    private static final Set<String> NOT_DURABLE = Set.of("discard", "memory");

    private IndexQueueConfigurator() {
    }

    /**
     * The index queue for the configuration.
     *
     * @param configuration the configuration of LDS
     * @return the queue, to be closed when it is no longer used
     * @throws IllegalStateException when the transaction log is kept durably and no queue provider of its name is found
     */
    public static IndexQueue configureIndexQueue(DynamicConfiguration configuration) {
        String txlogProvider = configuration.find("txlog.rawdata.provider").orElse("discard");
        if (NOT_DURABLE.contains(txlogProvider)) {
            LOG.warn("The index queue is in memory, as the transaction log ({}) is: updates the search index has not "
                    + "taken when LDS stops are lost, and are found again only by a rebuild of the index", txlogProvider);
            return new MemoryIndexQueue();
        }
        for (IndexQueueProvider provider : ServiceLoader.load(IndexQueueProvider.class)) {
            if (txlogProvider.equals(provider.providerId())) {
                LOG.info("The index queue is kept in {}, where the transaction log is", txlogProvider);
                return provider.indexQueue(configuration.asMap());
            }
        }
        throw new IllegalStateException("No index queue provider for the transaction log provider " + txlogProvider
                + ": the queue is kept where the transaction log is");
    }
}
