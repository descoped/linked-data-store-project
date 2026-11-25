package io.descoped.lds.core.persistence.memory;

import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.PersistenceDeletePolicy;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.LinkQuery;
import io.descoped.lds.api.persistence.reactivex.Range;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.descoped.lds.api.persistence.json.JsonTools.mapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The documents that link to a document, read backward along the link through the index of values
 * ({@link RxJsonPersistence#readSourceDocuments}).
 */
class MemoryReverseLinkTest {

    private static final String NS = "ns";
    private static final ZonedDateTime T = ZonedDateTime.parse("2020-01-01T00:00:00Z");
    private static final JsonNavigationPath CAR = JsonNavigationPath.from("$.car");

    private RxJsonPersistence persistence;

    @AfterEach
    void close() {
        if (persistence != null) {
            persistence.close();
        }
    }

    /*
     * A document deleted at the time of its version leaves its index entry, which the read meets and leaves out. It
     * must do that within its own read: more such documents than the store has threads once stopped the store.
     */
    @Test
    void aReadPastDocumentsDeletedAtTheTimeOfTheirVersionEnds() {
        persistence = store(8192);
        for (int i = 0; i < 16; i++) {
            write("Garage", "g" + i, T, garage("/Car/c9"));
            try (Transaction tx = persistence.createTransaction(false)) {
                persistence.markDocumentDeleted(tx, NS, "Garage", "g" + i, T, PersistenceDeletePolicy.FAIL_IF_INCOMING_LINKS)
                        .blockingAwait();
            }
        }

        assertThat(sources("c9")).isEmpty();
        write("Garage", "g99", T, garage("/Car/c9"));
        assertThat(sources("c9")).containsExactly("g99");
    }

    @Test
    void aLinkReplacedAtTheSameTimeIsNoLongerFollowedBackward() {
        persistence = store(8192);
        write("Garage", "gx", T, garage("/Car/a1"));
        write("Garage", "gx", T, garage("/Car/b2"));

        assertThat(sources("a1")).isEmpty();
        assertThat(sources("b2")).containsExactly("gx");
    }

    @Test
    void aLinkLongerThanTheFragmentCapacityIsFollowedBackward() {
        persistence = store(16);
        String id = "a-car-whose-id-is-longer-than-the-fragment-capacity";
        write("Garage", "long", T, garage("/Car/" + id));
        // begins as the link does, so its first piece is the same
        write("Garage", "other", T, garage("/Car/" + id + "-and-more"));

        assertThat(sources(id)).containsExactly("long");
    }

    private static RxJsonPersistence store(int capacity) {
        return new MemoryInitializer().initialize(NS,
                Map.of("persistence.fragment.capacity", String.valueOf(capacity)), Set.of("Garage", "Car"));
    }

    private static ObjectNode garage(String car) {
        ObjectNode garage = mapper.createObjectNode();
        garage.put("car", car);
        return garage;
    }

    private void write(String entity, String id, ZonedDateTime version, ObjectNode content) {
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.createOrOverwrite(tx, Flowable.just(new JsonDocument(new DocumentKey(NS, entity, id, version), content)), null)
                    .blockingAwait();
        }
    }

    /* The ids of the garages that link to the car, read within ten seconds. */
    private List<String> sources(String carId) {
        try (Transaction tx = persistence.createTransaction(true)) {
            TestSubscriber<String> read = persistence.readSourceDocuments(tx, T,
                            new LinkQuery(NS, "Car", carId, CAR, "Garage"), Range.unbounded())
                    .map(document -> document.key().id())
                    .test();
            boolean ended = read.await(10, TimeUnit.SECONDS);
            read.cancel();
            assertThat(ended).as("the read ended").isTrue();
            read.assertNoErrors();
            return read.values();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
