module io.descoped.lds.search.api {
    requires io.reactivex.rxjava2;
    requires org.reactivestreams;
    requires com.github.akarnokd.rxjava2jdk9interop;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires io.descoped.lds.persistence.api;

    exports io.descoped.lds.api.search;
}
