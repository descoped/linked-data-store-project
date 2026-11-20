module io.descoped.lds.search.api {
    requires transitive io.reactivex.rxjava3;
    requires tools.jackson.core;
    requires tools.jackson.databind;
    requires transitive io.descoped.lds.persistence.api;

    exports io.descoped.lds.api.search;
}
