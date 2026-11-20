package io.descoped.lds.core.search;

import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.search.SearchIndexProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

public class SearchIndexConfigurator {

    private static final Logger LOG = LoggerFactory.getLogger(SearchIndexConfigurator.class);

    public static SearchIndex configureSearchIndex(DynamicConfiguration configuration) {
        boolean graphqlSearchEnabled = configuration.getBoolean("graphql.search.enabled", false);
        if (graphqlSearchEnabled) {
            final String providerId = configuration.find("search.index.provider").orElse(null);

            ServiceLoader<SearchIndexProvider> loader = ServiceLoader.load(SearchIndexProvider.class);
            for (SearchIndexProvider provider : loader) {
                if (providerId.equals(provider.getProviderId())) {
                    LOG.info("Search index provider configured: {}", providerId);
                    return provider.getSearchIndex(configuration.asMap());
                }
            }
            throw new RuntimeException("No search index provider found for providerId: " + providerId);
        } else {
            LOG.info("GraphQL Search is disabled. Skipping search index initialisation");
            return null;
        }
    }
}
