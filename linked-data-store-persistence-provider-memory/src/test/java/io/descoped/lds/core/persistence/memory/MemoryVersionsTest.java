package io.descoped.lds.core.persistence.memory;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.Range;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.descoped.lds.api.persistence.json.JsonTools.mapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The versions of a document, read as a timeline.
 */
class MemoryVersionsTest {

    private static final String NS = "ns";

    private final RxJsonPersistence persistence = new MemoryInitializer().initialize(NS,
            Map.of("persistence.fragment.capacity", "8192"), Set.of("Person"));

    @AfterEach
    void close() {
        persistence.close();
    }

    @Test
    void everyVersionIsInTheTimelineHoweverEarlyOrLate() {
        List<ZonedDateTime> versions = List.of(
                ZonedDateTime.parse("0000-03-01T00:00:00Z"),
                ZonedDateTime.parse("0000-06-01T00:00:00Z"),
                ZonedDateTime.parse("2020-01-01T00:00:00Z"),
                ZonedDateTime.parse("9999-01-01T00:00:00Z"),
                ZonedDateTime.parse("9999-06-01T00:00:00Z"));
        for (ZonedDateTime version : versions) {
            try (Transaction tx = persistence.createTransaction(false)) {
                persistence.createOrOverwrite(tx, Flowable.just(new JsonDocument(new DocumentKey(NS, "Person", "ola", version),
                        mapper.createObjectNode().put("at", version.toString()))), null).blockingAwait();
            }
        }

        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(persistence.readDocumentVersions(tx, NS, "Person", "ola", Range.unbounded())
                    .map(document -> document.key().timestamp().toInstant()).toList().blockingGet())
                    .containsExactlyElementsOf(versions.stream().map(ZonedDateTime::toInstant).toList());
            assertThat(persistence.readDocumentVersions(tx, NS, "Person", "ola", Range.last(5))
                    .map(document -> document.key().timestamp().toInstant()).toList().blockingGet())
                    .containsExactlyElementsOf(versions.reversed().stream().map(ZonedDateTime::toInstant).toList());
        }
    }
}
