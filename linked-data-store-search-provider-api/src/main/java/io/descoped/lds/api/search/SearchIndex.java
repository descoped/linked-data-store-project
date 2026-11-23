package io.descoped.lds.api.search;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;

import java.util.List;

/**
 * The search index: one entry for each version of each document, written and deleted, from which a search finds the
 * documents whose texts match words, as they were at a moment (see {@link SearchQuery}).
 */
public interface SearchIndex {

    /**
     * Adds entries, in one request. An entry that the index already has (the same document and version, sent again
     * by a retry or a rebuild) is replaced by the one given, which holds the same.
     *
     * @param entries the entries
     * @return complete when the index has taken every entry; fails when it refuses them or cannot be reached
     */
    Completable put(List<IndexEntry> entries);

    /**
     * Makes every entry the index has taken searchable now, instead of within the time it takes otherwise.
     *
     * @return complete when a search finds every entry the index had taken when it was asked
     */
    Completable refresh();

    /**
     * Asks the index whether it answers, for the readiness of LDS.
     *
     * @return completes when the index answers, fails when it cannot be reached or says it is not ready
     */
    Completable health();

    /**
     * Searches.
     *
     * @param query the search
     * @return the page of hits the query asks for, and the number of hits in all
     */
    Single<SearchPage> search(SearchQuery query);
}
