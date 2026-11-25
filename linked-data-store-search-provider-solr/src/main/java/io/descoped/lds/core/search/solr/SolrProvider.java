package io.descoped.lds.core.search.solr;

import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.search.SearchIndexProvider;

import java.util.Map;
import java.util.Set;

/**
 * The search index in a Solr 10 core: provider {@code solr}, at the address of the core in
 * {@code search.index.url}.
 */
public class SolrProvider implements SearchIndexProvider {

    private static final String URL = "search.index.url";
    private static final String COMMIT_WITHIN = "search.index.commit-within";

    /**
     * The provider, as the service loader makes it.
     */
    public SolrProvider() {
        // nothing to set up: the index is made by getSearchIndex, with the configuration
    }

    @Override
    public String getProviderId() {
        return "solr";
    }

    @Override
    public Set<String> configurationKeys() {
        return Set.of(URL, COMMIT_WITHIN);
    }

    /**
     * The index of the core at {@code search.index.url}, which makes what it takes searchable within
     * {@code search.index.commit-within} milliseconds (1000 when it is not given).
     *
     * @param configuration the configuration of LDS, every key of it
     * @return the index
     */
    @Override
    public SearchIndex getSearchIndex(Map<String, String> configuration) {
        return new SolrSearchIndex(configuration.get(URL), commitWithin(configuration.get(COMMIT_WITHIN)));
    }

    private static int commitWithin(String value) {
        if (value == null || value.isBlank()) {
            return SolrSearchIndex.COMMIT_WITHIN_MILLIS;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(COMMIT_WITHIN + " must be a number of milliseconds: " + value, e);
        }
    }
}
