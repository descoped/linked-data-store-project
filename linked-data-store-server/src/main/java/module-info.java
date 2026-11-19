module io.descoped.lds.server {
    requires io.descoped.lds.core;
    requires io.descoped.dynamic.config;
    requires org.slf4j;
    requires org.apache.commons.logging; // needed to use the solr search provider
}
