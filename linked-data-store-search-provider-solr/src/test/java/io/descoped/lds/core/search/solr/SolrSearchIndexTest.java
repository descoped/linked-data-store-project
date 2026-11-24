package io.descoped.lds.core.search.solr;

import io.descoped.lds.api.search.IndexEntry;
import io.descoped.lds.api.search.Language;
import io.descoped.lds.api.search.SearchHit;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.search.SearchPage;
import io.descoped.lds.api.search.SearchIndexProvider;
import io.descoped.lds.api.search.SearchQuery;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import org.apache.solr.client.solrj.RemoteSolrException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The Solr provider on a real Solr 10, reached over HTTP as LDS reaches it. An entry is in the index within the
 * second the provider gives Solr to commit it, so the tests wait for what they search for. Each test writes in a
 * namespace of its own, which no other test searches.
 */
class SolrSearchIndexTest {

    private static final Duration COMMIT_WAIT = Duration.ofSeconds(30);
    private static final ZonedDateTime T0 = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private static SearchIndex searchIndex;

    private String namespace;

    @BeforeAll
    static void start() {
        searchIndex = new SolrProvider().getSearchIndex(Solr10.configuration());
    }

    @BeforeEach
    void aNamespaceOfItsOwn() {
        namespace = "ns" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * The versions of the design of search, at the moments it was tried at: of each document the version valid at
     * the moment is the one searched, so a word that only an older version has does not find the document, and a
     * document deleted before the moment is not found.
     */
    @Test
    void aDocumentIsFoundByTheWordsOfItsVersionValidAtTheMoment() {
        searchIndex.put(List.of(
                text("Topic", "d1", 1000, "apple banana"),
                text("Topic", "d1", 2000, "cherry"),
                deleted("Topic", "d1", 3000),
                text("Topic", "d2", 1500, "apple pie"),
                text("Topic", "d3", 500, "apples are red"),
                text("Topic", "d4", 2500, "apple"))).blockingAwait();
        awaitHits(() -> search("apple", at(3500)), 3);

        assertThat(ids(search("apple", at(2200)))).containsExactlyInAnyOrder("d2", "d3");
        assertThat(search("apple", at(2200)).total()).isEqualTo(2);
        assertThat(keys(search("apple", at(1200)))).containsExactlyInAnyOrder("d1@1000", "d3@500");
        assertThat(keys(search("cherry", at(2200)))).containsExactly("d1@2000");
        assertThat(ids(search("cherry", at(3500)))).isEmpty();
        assertThat(ids(search("*:*", at(3500)))).containsExactlyInAnyOrder("d2", "d3", "d4");
        assertThat(ids(search("-cherry", at(3500)))).containsExactlyInAnyOrder("d2", "d3", "d4");
        assertThat(ids(search("apple", at(400)))).isEmpty();
    }

    @Test
    void withHistoryEveryVersionThatHasTheWordsIsAHit() {
        searchIndex.put(List.of(
                text("Topic", "d1", 1000, "apple banana"),
                text("Topic", "d1", 2000, "cherry"),
                text("Topic", "d1", 2500, "apple again"),
                deleted("Topic", "d1", 3000),
                text("Topic", "d2", 1500, "apple pie"))).blockingAwait();
        awaitHits(() -> search("apple", at(2600)), 2);

        SearchPage history = searchIndex.search(new SearchQuery(namespace, "apple", Set.of(), at(3500), true, 0, 10))
                .blockingGet();

        assertThat(keys(history)).containsExactlyInAnyOrder("d1@1000", "d1@2500", "d2@1500");
        assertThat(history.total()).isEqualTo(3);
        assertThat(keys(searchIndex.search(new SearchQuery(namespace, "apple", Set.of(), at(1200), true, 0, 10))
                .blockingGet())).containsExactly("d1@1000");
    }

    /**
     * A text in a language is found by another form of its words; a text in no language that LDS knows is found by
     * its words as they are written.
     */
    @Test
    void aTextIsFoundByTheStemOfItsWordsInItsLanguage() {
        searchIndex.put(List.of(
                text("Topic", "nb", 1000, Language.NORWEGIAN, "Acme bygger husene i byen"),
                text("Topic", "nn", 1000, Language.NORWEGIAN, "Gutane går til skulane"),
                text("Topic", "sv", 1000, Language.SWEDISH, "Huset ligger vid sjön"),
                text("Topic", "en", 1000, Language.ENGLISH, "Acme builds the houses of the town"),
                text("Topic", "de", 1000, Language.GERMAN, "Die Häuser an der Straße"),
                text("Topic", "fr", 1000, Language.FRENCH, "Les écoles de l'île"),
                text("Topic", "none", 1000, Language.NONE, "husene i byen"))).blockingAwait();
        awaitHits(() -> search("*:*", at(2000)), 7);

        assertThat(ids(search("hus", at(2000)))).containsExactlyInAnyOrder("nb", "sv");
        assertThat(ids(search("husene", at(2000)))).containsExactlyInAnyOrder("nb", "none");
        assertThat(ids(search("gutar", at(2000)))).containsExactly("nn");
        assertThat(ids(search("skule", at(2000)))).containsExactly("nn");
        assertThat(ids(search("house", at(2000)))).containsExactly("en");
        assertThat(ids(search("Haus", at(2000)))).containsExactly("de");
        assertThat(ids(search("Strasse", at(2000)))).containsExactly("de");
        assertThat(ids(search("école", at(2000)))).containsExactly("fr");
        assertThat(ids(search("île", at(2000)))).containsExactly("fr");
        assertThat(ids(search("Acme", at(2000)))).containsExactlyInAnyOrder("nb", "en");
    }

    /**
     * A word is found also when it is typed without the letters it is written with, or with them when it is written
     * without: ø, å, æ and the ways they are written without them (oe, aa, ae), and accents.
     */
    @Test
    void aWordIsFoundTypedWithOrWithoutItsLetters() {
        searchIndex.put(List.of(
                text("Topic", "nb", 1000, Language.NORWEGIAN, "Bøkene på hylla er kjære"),
                text("Topic", "sv", 1000, Language.SWEDISH, "Böckerna på hyllan"),
                text("Topic", "name", 1000, Language.NONE, "Åse Bjørn"),
                text("Topic", "fr", 1000, Language.FRENCH, "Les écoles de l'île"),
                text("Topic", "de", 1000, Language.GERMAN, "Die Häuser"),
                text("Topic", "plain", 1000, Language.NORWEGIAN, "Boker uten bokstaver"))).blockingAwait();
        awaitHits(() -> search("*:*", at(2000)), 6);

        assertThat(ids(search("boker", at(2000)))).containsExactlyInAnyOrder("nb", "plain");
        assertThat(ids(search("bøker", at(2000)))).containsExactlyInAnyOrder("nb", "plain");
        assertThat(ids(search("kjaere", at(2000)))).containsExactly("nb");
        assertThat(ids(search("kjare", at(2000)))).containsExactly("nb");
        assertThat(ids(search("bockerna", at(2000)))).containsExactly("sv");
        assertThat(ids(search("Aase", at(2000)))).containsExactly("name");
        assertThat(ids(search("Ase", at(2000)))).containsExactly("name");
        assertThat(ids(search("Bjorn", at(2000)))).containsExactly("name");
        assertThat(ids(search("ile", at(2000)))).containsExactly("fr");
        assertThat(ids(search("ecoles", at(2000)))).containsExactly("fr");
        assertThat(ids(search("Hauser", at(2000)))).containsExactly("de");
    }

    /**
     * A word as it is written ranks before the same word with other letters: "for" finds "før" too, after "for".
     */
    @Test
    void aWordAsItIsWrittenRanksFirst() {
        searchIndex.put(List.of(
                text("Topic", "before", 1000, Language.NORWEGIAN, "før middag"),
                text("Topic", "for", 1000, Language.NORWEGIAN, "for middag"))).blockingAwait();
        awaitHits(() -> search("*:*", at(2000)), 2);

        assertThat(ids(search("for", at(2000)))).containsExactly("for", "before");
        assertThat(ids(search("før", at(2000)))).containsExactly("before", "for");
    }

    @Test
    void theWordsAreAPhraseAllOfThemOrAnyOfThem() {
        searchIndex.put(List.of(
                text("Topic", "pie", 1000, "apple pie"),
                text("Topic", "red", 1000, "apples are red"))).blockingAwait();
        awaitHits(() -> search("*:*", at(2000)), 2);

        assertThat(ids(search("pie red", at(2000)))).containsExactlyInAnyOrder("pie", "red");
        assertThat(ids(search("\"apple pie\"", at(2000)))).containsExactly("pie");
        assertThat(ids(search("\"pie apple\"", at(2000)))).isEmpty();
        assertThat(ids(search("apple AND pie", at(2000)))).containsExactly("pie");
        assertThat(ids(search("+apple -pie", at(2000)))).containsExactly("red");
    }

    /**
     * Words that a query of Solr would read as a field, another query or a parser are read as words: they find no
     * document, and do not widen the search to other entity types or namespaces.
     */
    @Test
    void theWordsCannotNameAFieldOrAQuery() {
        searchIndex.put(List.of(
                text("Topic", "topic", 1000, "brewery"),
                text("Record", "record", 1000, "a record"),
                deleted("Topic", "gone", 1000))).blockingAwait();
        awaitHits(() -> search("*:*", at(2000)), 2);

        for (String words : List.of("lds_entity:Record", "{!lucene}lds_entity:Record", "_query_:\"{!lucene}*:*\"",
                "lds_deleted:true", "lds_namespace:" + namespace, "id:*")) {
            assertThat(ids(search(words, at(2000)))).as(words).isEmpty();
        }
        assertThat(ids(searchIndex.search(new SearchQuery(namespace, "brewery OR lds_entity:Record", Set.of("Topic"),
                at(2000), false, 0, 10)).blockingGet())).containsExactly("topic");
    }

    @Test
    void aSearchIsNarrowedToItsNamespaceTheEntityTypesItNamesAndItsPage() {
        String other = namespace + "other";
        searchIndex.put(List.of(
                text("Topic", "a", 1000, "fjord"),
                text("Topic", "b", 1000, "fjord"),
                text("Record", "c", 1000, "fjord"),
                IndexEntry.written(other, "Topic", "x", T0.plusNanos(1000_000L),
                        Map.of(Language.NONE, List.of("fjord"))))).blockingAwait();
        awaitHits(() -> search("fjord", at(2000)), 3);

        assertThat(ids(searchIn(Set.of("Topic"), 0, 10))).containsExactlyInAnyOrder("a", "b");
        assertThat(ids(searchIn(Set.of("Topic", "Record"), 0, 10))).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(ids(searchIn(Set.of(), 0, 10))).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(ids(searchIn(Set.of("*"), 0, 10))).isEmpty();
        assertThat(ids(searchIn(Set.of("Topi?"), 0, 10))).isEmpty();

        SearchPage second = searchIn(Set.of(), 2, 2);
        assertThat(second.total()).isEqualTo(3);
        assertThat(second.hits()).hasSize(1);
        assertThat(ids(searchIn(Set.of(), 0, 1))).doesNotContainAnyElementsOf(ids(searchIn(Set.of(), 1, 2)));
    }

    /**
     * The key of a hit is the key of the version that matched, with the version to the nanosecond, and any id,
     * also one with a slash or of four bytes in UTF-8, comes back as it was written.
     */
    @Test
    void aHitHasTheKeyOfTheVersionThatMatched() {
        ZonedDateTime version = ZonedDateTime.parse("2026-11-23T10:00:00.123456789Z");
        String id = "a/b c/😀";
        searchIndex.put(List.of(IndexEntry.written(namespace, "Topic", id, version,
                Map.of(Language.ENGLISH, List.of("found"))))).blockingAwait();

        SearchHit hit = awaitHits(() -> search("found", version), 1).hits().getFirst();

        assertThat(hit.key().namespace()).isEqualTo(namespace);
        assertThat(hit.key().entity()).isEqualTo("Topic");
        assertThat(hit.key().id()).isEqualTo(id);
        assertThat(hit.key().timestamp().toInstant()).isEqualTo(version.toInstant());
        assertThat(ids(search("found", version.minusNanos(1000)))).isEmpty();
    }

    /**
     * A version is kept to the nanosecond, before 1970 too: of two versions in the same microsecond each is its own
     * entry, the later is the one valid after it, and a moment a nanosecond before a version does not see it.
     */
    @Test
    void aVersionIsKeptToTheNanosecond() {
        ZonedDateTime first = ZonedDateTime.parse("1850-06-01T12:00:00.000000100Z");
        ZonedDateTime second = first.plusNanos(200);
        searchIndex.put(List.of(
                IndexEntry.written(namespace, "Topic", "old", first, Map.of(Language.ENGLISH, List.of("whaling"))),
                IndexEntry.written(namespace, "Topic", "old", second, Map.of(Language.ENGLISH, List.of("sailing")))))
                .blockingAwait();
        awaitHits(() -> searchIndex.search(new SearchQuery(namespace, "*:*", Set.of(), second, true, 0, 10))
                .blockingGet(), 2);

        assertThat(ids(search("whaling", first))).containsExactly("old");
        assertThat(ids(search("whaling", second.minusNanos(1)))).containsExactly("old");
        assertThat(ids(search("whaling", second))).isEmpty();
        assertThat(search("sailing", second).hits().getFirst().key().timestamp().toInstant())
                .isEqualTo(second.toInstant());
        assertThat(ids(search("whaling", first.minusNanos(1)))).isEmpty();
    }

    /**
     * Two documents whose names, joined with a slash, would read the same are two documents.
     */
    @Test
    void documentsWhoseNamesJoinTheSameAreTwo() {
        searchIndex.put(List.of(
                IndexEntry.written(namespace, "Topic", "x/y", at(1000), Map.of(Language.NONE, List.of("twin"))),
                IndexEntry.written(namespace + "/Topic", "x", "y", at(1000), Map.of(Language.NONE, List.of("twin")))))
                .blockingAwait();

        awaitHits(() -> search("twin", at(2000)), 1);
        assertThat(searchIndex.search(new SearchQuery(namespace + "/Topic", "twin", Set.of(), at(2000), false, 0, 10))
                .blockingGet().total()).isEqualTo(1);
    }

    /**
     * Words a user types that are no well-formed query (an open quote or bracket, an operator alone, the escape
     * character, a range, local parameters) are answered as words: a search never fails, and the word in them is
     * found.
     */
    @Test
    void wordsThatAreNoWellFormedQueryAreAnsweredAndNeverFail() {
        searchIndex.put(List.of(text("Topic", "a", 1000, "the fjord at night"))).blockingAwait();
        awaitHits(() -> search("fjord", at(2000)), 1);

        for (String words : List.of("\"fjord", "fjord\"", "(fjord", "fjord)", "[fjord", "fjord]", "{fjord", "fjord}",
                "fjord AND", "AND fjord", "fjord OR", "fjord &&", "|| fjord", "fjord\\", "\\fjord", "fjord:", ":fjord",
                "fjord^", "fjord^2", "fjord~", "+fjord", "fjord!", "fjord/", "/fjord/", "fjord [a TO", "{!fjord}",
                "fjord {!", "fjord $ldsText", "fjord ${x}")) {
            assertThat(ids(search(words, at(2000)))).as(words).containsExactly("a");
        }
        for (String words : List.of("\"", "(", ")", "[", "]", "{", "}", "AND", "OR", "NOT", "&&", "||", "!", "\\", ":",
                "^", "~", "+", "-", "/", "*", "?", "[* TO *]", "{!}", "\"\"", "()")) {
            assertThat(search(words, at(2000))).as(words).isNotNull();
        }
    }

    /**
     * No words, or only spaces, find nothing.
     */
    @Test
    void noWordsFindNothing() {
        searchIndex.put(List.of(text("Topic", "a", 1000, "fjord"))).blockingAwait();
        awaitHits(() -> search("fjord", at(2000)), 1);

        assertThat(search("", at(2000)).total()).isZero();
        assertThat(search("   ", at(2000)).total()).isZero();
        assertThat(search("\t\n", at(2000)).total()).isZero();
    }

    /**
     * A letter with its accent as a mark of its own (as macOS and many keyboards write it) is the same letter as the
     * one written whole: a text written either way is found by words typed either way, and without the accent.
     */
    @Test
    void aLetterWithItsAccentAsAMarkOfItsOwnIsTheSameLetter() {
        String apart = "cafe\u0301 au lait"; // e and a combining acute accent
        String whole = "na\u00efve art"; // ï as one letter
        searchIndex.put(List.of(text("Topic", "apart", 1000, Language.NONE, apart),
                text("Topic", "whole", 1000, Language.NONE, whole))).blockingAwait();
        awaitHits(() -> search("lait", at(2000)), 1);

        assertThat(ids(search("caf\u00e9", at(2000)))).as("café typed whole").containsExactly("apart");
        assertThat(ids(search("cafe\u0301", at(2000)))).as("café typed apart").containsExactly("apart");
        assertThat(ids(search("cafe", at(2000)))).as("cafe").containsExactly("apart");
        assertThat(ids(search("nai\u0308ve", at(2000)))).as("naïve typed apart").containsExactly("whole");
        assertThat(ids(search("naive", at(2000)))).as("naive").containsExactly("whole");
    }

    /**
     * Texts of every script are found by their words: Chinese and Japanese, which have no spaces, Greek, Cyrillic,
     * Arabic, Hebrew and a text with emoji.
     */
    @Test
    void textsOfEveryScriptAreFound() {
        searchIndex.put(List.of(
                text("Topic", "tokyo", 1000, Language.NONE, "東京タワーに行きました"),
                text("Topic", "athens", 1000, Language.NONE, "Η Αθήνα είναι όμορφη"),
                text("Topic", "moscow", 1000, Language.NONE, "Москва зимой"),
                text("Topic", "cairo", 1000, Language.NONE, "القاهرة مدينة كبيرة"),
                text("Topic", "jerusalem", 1000, Language.NONE, "ירושלים של זהב"),
                text("Topic", "party", 1000, Language.NONE, "a party 🎉 tonight"))).blockingAwait();
        awaitHits(() -> search("*:*", at(2000)), 6);

        assertThat(ids(search("東京", at(2000)))).containsExactly("tokyo");
        assertThat(ids(search("αθήνα", at(2000)))).containsExactly("athens");
        assertThat(ids(search("МОСКВА", at(2000)))).containsExactly("moscow");
        assertThat(ids(search("القاهرة", at(2000)))).containsExactly("cairo");
        assertThat(ids(search("ירושלים", at(2000)))).containsExactly("jerusalem");
        assertThat(ids(search("party", at(2000)))).containsExactly("party");
        assertThat(search("🎉", at(2000))).isNotNull();
    }

    /**
     * A text of a megabyte, and a word longer than any word, are taken, and a word in them is found.
     */
    @Test
    void aVeryLongTextAndAVeryLongWordAreTaken() {
        String megabyte = "ocean ".repeat(100_000) + "needle " + "ocean ".repeat(75_000);
        String longWord = "x".repeat(100_000);
        searchIndex.put(List.of(text("Topic", "long", 1000, megabyte),
                text("Topic", "word", 1000, Language.NONE, longWord + " beside"))).blockingAwait();

        assertThat(ids(awaitHits(() -> search("needle", at(2000)), 1))).containsExactly("long");
        assertThat(ids(awaitHits(() -> search("beside", at(2000)), 1))).containsExactly("word");
    }

    /**
     * A hundred entries, the most the worker sends at once, are taken in one request.
     */
    @Test
    void aHundredEntriesAreTakenInOneRequest() {
        List<IndexEntry> entries = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            entries.add(text("Topic", "t" + i, 1000, "batch"));
        }
        searchIndex.put(entries).blockingAwait();

        assertThat(awaitHits(() -> search("batch", at(2000)), 100).total()).isEqualTo(100);
    }

    /**
     * A page of no hits gives the total only.
     */
    @Test
    void aPageOfNoHitsGivesTheTotal() {
        searchIndex.put(List.of(text("Topic", "a", 1000, "count"), text("Topic", "b", 1000, "count")))
                .blockingAwait();
        awaitHits(() -> search("count", at(2000)), 2);

        SearchPage page = searchIndex.search(new SearchQuery(namespace, "count", Set.of(), at(2000), false, 0, 0))
                .blockingGet();

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.hits()).isEmpty();
    }

    /**
     * Nothing to put asks nothing of Solr: it completes also when Solr cannot be reached.
     */
    @Test
    void nothingToPutAsksNothingOfSolr() throws IOException {
        SearchIndex unreachable = new SolrSearchIndex("http://127.0.0.1:" + closedPort() + "/solr/lds-index");

        unreachable.put(List.of()).blockingAwait();
    }

    /**
     * The provider is found by its name and names the keys it reads; a blank time to be searchable is the default.
     */
    @Test
    void theProviderIsFoundByItsNameAndNamesItsKeys() {
        SearchIndexProvider provider = ServiceLoader.load(SearchIndexProvider.class).stream()
                .map(ServiceLoader.Provider::get).filter(p -> p.getProviderId().equals("solr"))
                .findFirst().orElseThrow();

        assertThat(provider.configurationKeys()).containsExactlyInAnyOrder("search.index.url",
                "search.index.commit-within");
        SearchIndex blank = provider.getSearchIndex(Map.of("search.index.url", Solr10.url(),
                "search.index.commit-within", " "));
        blank.put(List.of(text("Topic", "blank", 1000, "default"))).blockingAwait();
        assertThat(ids(awaitHits(() -> search("default", at(2000)), 1))).containsExactly("blank");
    }

    /**
     * An age below none is refused: it would remove what the index takes from now on.
     */
    @Test
    void aNegativeAgeIsRefused() {
        assertThatThrownBy(() -> searchIndex.removeOlderThan(namespace, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("age");
    }

    @Test
    void aPagePastEveryPlaceSolrCanGiveIsEmpty() {
        searchIndex.put(List.of(text("Topic", "a", 1000, "far"))).blockingAwait();
        awaitHits(() -> search("far", at(2000)), 1);

        SearchPage page = searchIndex.search(new SearchQuery(namespace, "far", Set.of(), at(2000), false,
                Integer.MAX_VALUE, 10)).blockingGet();

        assertThat(page.hits()).isEmpty();
        assertThat(page.total()).isEqualTo(1);
    }

    /**
     * The clean-up after a rebuild: of the namespace, an entry Solr took less long ago than the age stays and one it
     * took longer ago goes, by Solr's own clock; another namespace keeps its entries, and a namespace that reads as a
     * query removes nothing. The ages, an hour and none, are chosen so that no test waits.
     */
    @Test
    void theEntriesOfANamespaceTakenLongerAgoThanTheAgeAreRemoved() {
        String other = namespace + "other";
        searchIndex.put(List.of(text("Topic", "taken", 1000, "harbour"), IndexEntry.written(other, "Topic",
                "elsewhere", at(1000), Map.of(Language.ENGLISH, List.of("harbour"))))).blockingAwait();

        searchIndex.removeOlderThan(namespace, Duration.ofHours(1)).blockingAwait();
        searchIndex.removeOlderThan("\" OR *:* OR \"", Duration.ZERO).blockingAwait();
        searchIndex.refresh().blockingAwait();
        assertThat(ids(search("harbour", at(2000)))).containsExactly("taken");

        searchIndex.removeOlderThan(namespace, Duration.ZERO).blockingAwait();
        searchIndex.refresh().blockingAwait();
        assertThat(ids(search("harbour", at(2000)))).isEmpty();
        assertThat(ids(searchIndex.search(new SearchQuery(other, "harbour", Set.of(), at(2000), false, 0, 10))
                .blockingGet())).containsExactly("elsewhere");
    }

    /**
     * An index that makes what it takes searchable only after a minute makes it searchable at once when it is asked.
     */
    @Test
    void anEntryIsSearchableAtOnceWhenTheIndexIsAskedTo() {
        SearchIndex slow = new SolrProvider().getSearchIndex(Map.of("search.index.url", Solr10.url(),
                "search.index.commit-within", "60000"));
        slow.put(List.of(text("Topic", "now", 1000, "immediately"))).blockingAwait();

        slow.refresh().blockingAwait();

        assertThat(ids(search("immediately", at(2000)))).containsExactly("now");
    }

    @Test
    void theTimeWithinWhichAnEntryIsSearchableIsAtLeastAMillisecondAndANumber() {
        String url = Solr10.url();
        SolrProvider provider = new SolrProvider();
        Map<String, String> notANumber = Map.of("search.index.url", url, "search.index.commit-within", "1s");

        assertThatThrownBy(() -> new SolrSearchIndex(url, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("search.index.commit-within");
        assertThatThrownBy(() -> provider.getSearchIndex(notANumber)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("search.index.commit-within must be a number of milliseconds: 1s");
    }

    @Test
    void anEntrySentAgainIsKeptOnce() {
        IndexEntry entry = text("Topic", "again", 1000, "twice");
        searchIndex.put(List.of(entry)).blockingAwait();
        searchIndex.put(List.of(entry)).blockingAwait();
        awaitHits(() -> search("twice", at(2000)), 1);

        assertThat(searchIndex.search(new SearchQuery(namespace, "twice", Set.of(), at(2000), true, 0, 10))
                .blockingGet().total()).isEqualTo(1);
    }

    @Test
    void anIndexWithNothingInItIsSearched() {
        assertThat(search("anything", at(2000)).total()).isZero();
    }

    /**
     * The schema is put in by the first index that uses the core, and an index that finds it there leaves it; one
     * that finds another mark puts it in again over the fields that are there. Two indexes that put it in at the
     * same time both end with it.
     */
    @Test
    void theSchemaIsPutInWhenTheCoreDoesNotHaveIt() throws Exception {
        setMark("not-this-schema");
        SearchIndex first = new SolrSearchIndex(Solr10.url());
        SearchIndex second = new SolrSearchIndex(Solr10.url());
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread a = Thread.ofVirtual().start(() -> put(first, "first", errors));
        Thread b = Thread.ofVirtual().start(() -> put(second, "second", errors));
        a.join();
        b.join();

        assertThat(errors).isEmpty();
        assertThat(mark()).isEqualTo(new SolrSchema().markOfThisSchema());
        assertThat(ids(awaitHits(() -> search("mark", at(2000)), 2))).containsExactlyInAnyOrder("first", "second");
        // a field the schema does not have is refused: Solr does not make one
        assertThat(post("/update?commit=true", "[{\"id\": \"x\", \"unknown_field\": \"y\"}]")).isEqualTo(400);
    }

    private void put(SearchIndex index, String id, List<Throwable> errors) {
        try {
            index.put(List.of(text("Topic", id, 1000, "mark"))).blockingAwait();
        } catch (Throwable e) {
            errors.add(e);
        }
    }

    @Test
    void aWriteAfterSolrHasClosedTheIdleConnectionStillReachesTheIndex() throws InterruptedException {
        searchIndex.put(List.of(text("Topic", "first", 1000, "before the pause"))).blockingAwait();
        awaitHits(() -> search("pause", at(2000)), 1);

        Thread.sleep(Solr10.IDLE_TIMEOUT.multipliedBy(2).toMillis());

        searchIndex.put(List.of(text("Topic", "second", 1000, "after the pause"))).blockingAwait();
        assertThat(ids(awaitHits(() -> search("pause", at(2000)), 2))).containsExactlyInAnyOrder("first", "second");
    }

    @Test
    void anUpdateSolrRefusesFailsForItsCaller() {
        // Solr answers 404 for a core it does not have
        SolrSearchIndex missingCore = new SolrSearchIndex(Solr10.url().replace("/lds-index", "/no-such-core"));

        List<IndexEntry> entries = List.of(text("Topic", "a", 1000, "one"));
        SearchQuery query = new SearchQuery(namespace, "one", Set.of(), at(2000), false, 0, 10);

        // put and search only make the request; it is sent, and fails, when it is waited for
        Completable put = missingCore.put(entries);
        Single<SearchPage> search = missingCore.search(query);

        assertThatThrownBy(put::blockingAwait)
                .isInstanceOf(RemoteSolrException.class).hasMessageContaining("404");
        assertThatThrownBy(search::blockingGet)
                .isInstanceOf(RemoteSolrException.class).hasMessageContaining("404");
    }

    /**
     * The health of the index is that of its core: an empty core answers, a core Solr does not have and a Solr that
     * cannot be reached do not.
     */
    @Test
    void theIndexIsHealthyWhenItsCoreAnswers() throws IOException {
        searchIndex.health().blockingAwait();

        SolrSearchIndex missingCore = new SolrSearchIndex(Solr10.url().replace("/lds-index", "/no-such-core"));
        assertThatThrownBy(() -> missingCore.health().blockingAwait())
                .isInstanceOf(RemoteSolrException.class).hasMessageContaining("404");

        SolrSearchIndex unreachable = new SolrSearchIndex("http://127.0.0.1:" + closedPort() + "/solr/lds-index");
        assertThatThrownBy(() -> unreachable.health().blockingAwait())
                .hasRootCauseInstanceOf(java.net.ConnectException.class);
    }

    /**
     * Nothing is put in when Solr cannot be reached, and the index tries again the next time it is used.
     */
    @Test
    void anUpdateThatCannotReachSolrFailsForItsCallerAndIsTriedAgain() throws IOException {
        SolrSearchIndex unreachable = new SolrSearchIndex("http://127.0.0.1:" + closedPort() + "/solr/lds-index");
        List<IndexEntry> entries = List.of(text("Topic", "a", 1000, "one"));

        Completable first = unreachable.put(entries);
        Completable again = unreachable.put(entries);

        assertThatThrownBy(first::blockingAwait).hasRootCauseInstanceOf(java.net.ConnectException.class);
        assertThatThrownBy(again::blockingAwait).hasRootCauseInstanceOf(java.net.ConnectException.class);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private SearchPage search(String words, ZonedDateTime at) {
        return searchIndex.search(new SearchQuery(namespace, words, Set.of(), at, false, 0, 10)).blockingGet();
    }

    private SearchPage searchIn(Set<String> entities, long from, long size) {
        return searchIndex.search(new SearchQuery(namespace, "fjord", entities, at(2000), false, from, size)).blockingGet();
    }

    /**
     * Searches until the index has the number of hits the test expects, and fails when the time a commit may take is
     * over first.
     */
    private static SearchPage awaitHits(Supplier<SearchPage> search, long hits) {
        AtomicReference<SearchPage> page = new AtomicReference<>();
        await().atMost(COMMIT_WAIT).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            page.set(search.get());
            assertThat(page.get().total()).as("hits").isEqualTo(hits);
        });
        return page.get();
    }

    private static ZonedDateTime at(long millis) {
        return T0.plusNanos(millis * 1_000_000L);
    }

    private IndexEntry text(String entity, String id, long millis, String text) {
        return text(entity, id, millis, Language.ENGLISH, text);
    }

    private IndexEntry text(String entity, String id, long millis, Language language, String text) {
        return IndexEntry.written(namespace, entity, id, at(millis), Map.of(language, List.of(text)));
    }

    private IndexEntry deleted(String entity, String id, long millis) {
        return IndexEntry.deleted(namespace, entity, id, at(millis));
    }

    private static List<String> ids(SearchPage page) {
        return page.hits().stream().map(hit -> hit.key().id()).toList();
    }

    /* The id and the version as milliseconds after T0: d1@1000. */
    private static List<String> keys(SearchPage page) {
        return page.hits().stream().map(hit -> hit.key().id() + "@"
                + Duration.between(T0, hit.key().timestamp()).toMillis()).toList();
    }

    private static void setMark(String mark) throws Exception {
        assertThat(post("/config", "{\"set-user-property\": {\"" + SolrSchema.MARK + "\": \"" + mark + "\"}}"))
                .isEqualTo(200);
    }

    private static String mark() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(Solr10.url() + "/config/overlay").toURL()
                .openConnection();
        try (InputStream in = connection.getInputStream()) {
            return tools.jackson.databind.json.JsonMapper.builder().build().readTree(in)
                    .path("overlay").path("userProps").path(SolrSchema.MARK).asString();
        } finally {
            connection.disconnect();
        }
    }

    /* The status of Solr's answer to a POST of JSON. */
    private static int post(String path, String json) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(Solr10.url() + path).toURL().openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}
