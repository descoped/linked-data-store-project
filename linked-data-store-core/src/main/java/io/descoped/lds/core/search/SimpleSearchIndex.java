package io.descoped.lds.core.search;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.search.IndexEntry;
import io.descoped.lds.api.search.SearchHit;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.search.SearchPage;
import io.descoped.lds.api.search.SearchQuery;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A search index in memory, for tests (provider {@code testSearchIndex}). It keeps the entries as Solr does, and finds
 * them as of a moment as Solr does; the words are matched more simply: an entry matches when one of its texts holds
 * the words as they are written, in any case, and every entry matches {@code *:*}.
 */
public class SimpleSearchIndex implements SearchIndex {

    private record Version(String namespace, String entity, String id, long version) {
    }

    private final Map<Version, IndexEntry> entries = new ConcurrentHashMap<>();

    @Override
    public Completable put(List<IndexEntry> list) {
        return Completable.fromAction(() -> list.forEach(entry -> entries.put(new Version(entry.namespace(),
                entry.entity(), entry.id(), ChronoUnit.MICROS.between(Instant.EPOCH, entry.version().toInstant())), entry)));
    }

    @Override
    public Completable health() {
        return Completable.complete();
    }

    @Override
    public Single<SearchPage> search(SearchQuery query) {
        return Single.fromCallable(() -> {
            List<IndexEntry> inScope = entries.values().stream()
                    .filter(entry -> entry.namespace().equals(query.namespace()))
                    .filter(entry -> query.entities().isEmpty() || query.entities().contains(entry.entity()))
                    .filter(entry -> !entry.version().toInstant().isAfter(query.snapshot().toInstant()))
                    .toList();
            List<IndexEntry> candidates = query.history() ? inScope : List.copyOf(inScope.stream()
                    .collect(Collectors.toMap(entry -> List.of(entry.entity(), entry.id()), Function.identity(),
                            (a, b) -> a.version().toInstant().isAfter(b.version().toInstant()) ? a : b))
                    .values());
            List<IndexEntry> hits = candidates.stream()
                    .filter(entry -> !entry.deleted() && matches(entry, query.text()))
                    .sorted(Comparator.comparing(IndexEntry::entity).thenComparing(IndexEntry::id)
                            .thenComparing(entry -> entry.version().toInstant(), Comparator.reverseOrder()))
                    .toList();
            return new SearchPage(hits.size(), hits.stream().skip(query.from()).limit(query.size())
                    .map(entry -> new SearchHit(new DocumentKey(entry.namespace(), entry.entity(), entry.id(),
                            entry.version())))
                    .toList());
        });
    }

    private static boolean matches(IndexEntry entry, String text) {
        if ("*:*".equals(text)) {
            return true;
        }
        String words = text.toLowerCase(Locale.ROOT);
        return entry.texts().values().stream().flatMap(List::stream).filter(Objects::nonNull)
                .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(words));
    }
}
