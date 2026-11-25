package io.descoped.lds.api.search;

import java.util.Map;
import java.util.Set;

/**
 * A search index that LDS finds with the service loader, by its id ({@code search.index.provider}).
 */
public interface SearchIndexProvider {

    /**
     * The id the configuration names the provider by.
     *
     * @return the id
     */
    String getProviderId();

    /**
     * The keys of the configuration the provider reads.
     *
     * @return the keys
     */
    Set<String> configurationKeys();

    /**
     * The search index, set up with the configuration.
     *
     * @param configuration the configuration of LDS, every key of it
     * @return the index
     */
    SearchIndex getSearchIndex(Map<String, String> configuration);
}
