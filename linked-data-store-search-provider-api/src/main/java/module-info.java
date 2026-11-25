/**
 * The search index of LDS: what a provider implements ({@link io.descoped.lds.api.search.SearchIndexProvider},
 * {@link io.descoped.lds.api.search.SearchIndex}), and the queue of the updates the index has not taken
 * ({@link io.descoped.lds.api.search.IndexQueue}).
 */
module io.descoped.lds.search.api {
    requires transitive io.reactivex.rxjava3;
    requires transitive io.descoped.lds.persistence.api;

    exports io.descoped.lds.api.search;
}
