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
import java.util.Map;
import java.util.Set;

import static io.descoped.lds.api.persistence.json.JsonTools.mapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A page of a list: its bounds and its limit, as every provider applies them.
 */
class MemoryPageTest {

    private static final String NS = "ns";
    private static final ZonedDateTime T = ZonedDateTime.parse("2020-01-01T00:00:00Z");

    private final RxJsonPersistence persistence = new MemoryInitializer().initialize(NS,
            Map.of("persistence.fragment.capacity", "8192"), Set.of("Person"));

    @AfterEach
    void close() {
        persistence.close();
    }

    /* A page before an id without an id after is read backward, and its limit holds whether it is first or last. */
    @Test
    void aBackwardPageWithFirstKeepsItsLimit() {
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.createOrOverwrite(tx, Flowable.fromArray("anne", "early", "kari", "ola", "per", "zed")
                    .map(id -> new JsonDocument(new DocumentKey(NS, "Person", id, T), mapper.createObjectNode().put("name", id))), null)
                    .blockingAwait();
        }
        try (Transaction tx = persistence.createTransaction(true)) {
            assertThat(persistence.readDocuments(tx, T, NS, "Person", Range.firstBetween(2, null, "per"))
                    .map(document -> document.key().id()).toList().blockingGet())
                    .containsExactly("ola", "kari");
            assertThat(persistence.readDocuments(tx, T, NS, "Person", Range.lastBefore(2, "per"))
                    .map(document -> document.key().id()).toList().blockingGet())
                    .containsExactly("ola", "kari");
        }
    }
}
