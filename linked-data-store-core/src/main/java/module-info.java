module io.descoped.lds.core {
    requires io.descoped.lds.persistence.api;
    requires io.descoped.lds.search.api;
    requires io.descoped.dynamic.config;
    requires no.cantara.concurrent.futureselector;
    requires no.cantara.saga.api;
    requires no.cantara.saga.execution;
    requires no.cantara.sagalog;
    requires io.descoped.rawdata.api;
    requires jdk.unsupported;
    requires java.base;
    requires java.net.http;
    requires org.slf4j;
    requires undertow.core;
    requires xnio.api;
    requires com.networknt.schema;
    requires java.xml; // belongs in test scope only; no class of core uses it
    requires tools.jackson.core;
    requires tools.jackson.databind;
    requires jackson.dataformat.msgpack;
    requires org.apache.tika.core;

    requires com.graphqljava;
    requires io.reactivex.rxjava3;
    requires com.graphqljava.extendedscalars;

    opens io.descoped.lds.graphql.graphiql;

    provides io.descoped.lds.api.search.SearchIndexProvider with io.descoped.lds.core.search.TestSearchIndex;

    uses io.descoped.lds.api.persistence.PersistenceInitializer;
    uses io.descoped.lds.api.search.IndexQueueProvider;
    uses io.descoped.lds.api.search.SearchIndexProvider;
    uses no.cantara.sagalog.SagaLogInitializer;

    exports io.descoped.lds.core;
    exports io.descoped.lds.test.server; // Needed to run tests in IntelliJ
}
