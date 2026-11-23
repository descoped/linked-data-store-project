import io.descoped.lds.api.search.SearchIndexProvider;
import io.descoped.lds.core.search.solr.SolrProvider;

/**
 * The search index of LDS in Solr 10: provider {@code solr}.
 */
module io.descoped.lds.search.provider.solr {
    requires io.descoped.lds.search.api;
    requires io.descoped.lds.persistence.api;
    requires solr.solrj;
    requires tools.jackson.databind;
    requires org.slf4j;
    requires io.reactivex.rxjava3;

    provides SearchIndexProvider with SolrProvider;
}
