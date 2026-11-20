package io.descoped.lds.core.search;

import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.api.search.SearchIndex;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SearchIndexConfiguratorTest {

    @Test
    public void thatServiceLoadingWorks() {
        DynamicConfiguration configuration = DynamicConfiguration.builder()
                .value("graphql.search.enabled", "true")
                .value("search.index.provider", "testSearchIndex")
                .build();
        SearchIndex searchIndex = SearchIndexConfigurator.configureSearchIndex(configuration);
        Assert.assertNotNull(searchIndex);
    }

    @Test
    public void thatServiceLoadingIsSkipped() {
        DynamicConfiguration configuration = DynamicConfiguration.builder()
                .value("graphql.search.enabled", "false")
                .value("search.index.provider", "")
                .build();
        SearchIndex searchIndex = SearchIndexConfigurator.configureSearchIndex(configuration);
        Assert.assertNull(searchIndex);
    }

    @Test(expectedExceptions = RuntimeException.class)
    public void thatServiceLoadingIsFails() {
        DynamicConfiguration configuration = DynamicConfiguration.builder()
                .value("graphql.search.enabled", "true")
                .value("search.index.provider", "")
                .build();
        SearchIndexConfigurator.configureSearchIndex(configuration);
    }

}
