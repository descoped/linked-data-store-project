package io.descoped.lds.api.search;

import java.util.List;

/**
 * A page of the hits of a search, best match first.
 *
 * @param total the number of hits of the search in all, not only of the page
 * @param hits  the hits of the page
 */
public record SearchPage(long total, List<SearchHit> hits) {

    /**
     * Keeps a copy of the hits.
     *
     * @param total the number of hits in all
     * @param hits  the hits of the page
     */
    public SearchPage {
        hits = List.copyOf(hits);
    }
}
