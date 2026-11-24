package io.descoped.lds.api.search;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;

import java.time.Duration;
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
     * Removes the entries of a namespace that the index took longer ago than the age given, measured by the index's
     * own clock, so that the clocks of several LDS do not matter; and, in every namespace, the entries the index
     * holds in a form it no longer writes (left by the search before 3.0.0), which no search finds. The clean-up after a rebuild: what the rebuild did not send again is what
     * storage no longer holds.
     *
     * @param namespace the namespace
     * @param age       how long ago the index took the entries that stay, at the most; none or more
     * @return complete when the index has removed them; an error ({@link IllegalArgumentException}) when the age is
     * below none, which would remove what the index takes from now on
     */
    Completable removeOlderThan(String namespace, Duration age);

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

    /**
     * Lets go of what the index holds to reach the search engine (its client, connections and threads). Called once,
     * when LDS stops, after the last update and search; the index is not used after.
     */
    void close();
}
