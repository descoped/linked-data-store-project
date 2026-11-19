module io.descoped.lds.search.api {
    requires transitive io.reactivex.rxjava3;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires transitive io.descoped.lds.persistence.api;

    exports io.descoped.lds.api.search;
}
