package io.descoped.lds.core.search;

import io.descoped.lds.api.search.IndexEntry;
import io.descoped.lds.api.search.Language;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.search.SearchQuery;
import io.reactivex.rxjava3.core.Completable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The index in memory answers as the Solr index does where a test of core leans on it.
 */
class SimpleSearchIndexTest {

    private static final ZonedDateTime VERSION = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final SearchIndex index = new SimpleSearchIndex();

    private List<String> ids(String words) {
        return index.search(new SearchQuery("data", words, Set.of(), VERSION.plusDays(1), false, 0, 10)).blockingGet()
                .hits().stream().map(hit -> hit.key().id()).toList();
    }

    private void put(String id, String text) {
        index.put(List.of(IndexEntry.written("data", "Topic", id, VERSION, Map.of(Language.NONE, List.of(text)))))
                .blockingAwait();
    }

    /**
     * No words, or only spaces, find nothing.
     */
    @Test
    void noWordsFindNothing() {
        put("a", "fjord");

        assertThat(ids("")).isEmpty();
        assertThat(ids("  \t")).isEmpty();
        assertThat(ids("fjord")).containsExactly("a");
    }

    /**
     * A letter with its accent as a mark of its own is the same letter as the one written whole.
     */
    @Test
    void aLetterWithItsAccentAsAMarkOfItsOwnIsTheSameLetter() {
        put("apart", "café au lait");
        put("whole", "naïve art");

        assertThat(ids("café")).containsExactly("apart");
        assertThat(ids("naïve")).containsExactly("whole");
    }

    /**
     * An age below none is refused.
     */
    @Test
    void aNegativeAgeIsRefused() {
        Completable removal = index.removeOlderThan("data", Duration.ofSeconds(-1));

        assertThatThrownBy(removal::blockingAwait).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("age");
    }
}
