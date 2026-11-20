/**
 * The LDS server: the main class and the defaults of its configuration. The persistence providers, the search
 * provider, the saga log and the transaction log it can be configured with are found at run time.
 */
module io.descoped.lds.server {
    requires io.descoped.lds.core;
    requires io.descoped.dynamic.config;
    requires org.slf4j;
    requires org.apache.commons.logging; // needed to use the solr search provider
}
