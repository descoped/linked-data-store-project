module io.descoped.lds.search.api {
    requires transitive io.reactivex.rxjava3;
    requires org.reactivestreams;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires io.descoped.lds.persistence.api;

    exports io.descoped.lds.api.search;
}
