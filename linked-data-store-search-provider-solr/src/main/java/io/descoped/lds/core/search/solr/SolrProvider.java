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

    /**
     * The provider, as the service loader makes it.
     */
    public SolrProvider() {
    }

    @Override
    public String getProviderId() {
        return "solr";
    }

    @Override
    public Set<String> configurationKeys() {
        return Set.of("search.index.url");
    }

    @Override
    public SearchIndex getSearchIndex(Map<String, String> configuration) {
        return new SolrSearchIndex(configuration.get("search.index.url"));
    }
}
