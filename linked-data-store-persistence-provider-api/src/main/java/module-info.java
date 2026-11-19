module io.descoped.lds.persistence.api {
    requires transitive io.reactivex.rxjava3;
    requires org.reactivestreams;
    requires com.fasterxml.jackson.core;
    requires transitive com.fasterxml.jackson.databind;

    exports io.descoped.lds.persistence.api;
    exports io.descoped.lds.api.persistence;
    exports io.descoped.lds.api.persistence.flattened;
    exports io.descoped.lds.api.persistence.streaming;
    exports io.descoped.lds.api.persistence.json;
    exports io.descoped.lds.api.persistence.reactivex;
    exports io.descoped.lds.api.specification;
    exports io.descoped.lds.api.json;

    uses io.descoped.lds.persistence.api.PersistenceProvider;
}
