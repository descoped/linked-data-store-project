package io.descoped.lds.core.persistence.memory;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.PersistenceDeletePolicy;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.streaming.Fragment;
import io.descoped.lds.api.persistence.streaming.FragmentType;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a delete does to the store: what is deleted is cleared from {@code Primary}, and what it put in the index of
 * values ({@code PathValueIndex}) with it.
 */
class MemoryDeleteTest {

    private static final ZonedDateTime V1 = ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime V2 = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final MemoryTransactionFactory factory = new MemoryTransactionFactory();
    private final MemoryRxPersistence persistence = new MemoryRxPersistence(factory, new MemoryDirectory());

    @Test
    void aDeleteOfOneVersionClearsTheVersionAndItsIndexEntries() {
        write(V1, "Bergen");
        write(V2, "Oslo");
        assertThat(entries(MemoryRxPersistence.PATH_VALUE_INDEX)).isEqualTo(2);
        int primary = entries(MemoryRxPersistence.PRIMARY_INDEX);

        try (Transaction tx = factory.createTransaction(false)) {
            persistence.delete(tx, "ns", "Person", "ola", V1, PersistenceDeletePolicy.FAIL_IF_INCOMING_LINKS)
                    .blockingAwait();
        }

        assertThat(entries(MemoryRxPersistence.PRIMARY_INDEX)).isEqualTo(primary - 1);
        assertThat(entries(MemoryRxPersistence.PATH_VALUE_INDEX)).isEqualTo(1);
    }

    /**
     * A delete of every document of an entity clears the index entry of every value, at any path: the store does not
     * ask the model which paths there are.
     */
    @Test
    void aDeleteOfEveryDocumentOfAnEntityClearsEveryIndexEntry() {
        write("ola", V1, "$.city", "Bergen");
        write("ola", V2, "$.city", "Oslo");
        write("kari", V1, "$.more.anything[3]", "x", "$.city", "Oslo");
        assertThat(entries(MemoryRxPersistence.PATH_VALUE_INDEX)).isEqualTo(4);

        try (Transaction tx = factory.createTransaction(false)) {
            persistence.deleteAllEntities(tx, "ns", "Person").blockingAwait();
        }

        assertThat(entries(MemoryRxPersistence.PRIMARY_INDEX)).isZero();
        assertThat(entries(MemoryRxPersistence.PATH_VALUE_INDEX)).isZero();
    }

    private void write(ZonedDateTime version, String city) {
        write("ola", version, "$.city", city);
    }

    /* One version of a document: a path and its text, as many as are given. */
    private void write(String id, ZonedDateTime version, String... pathsAndValues) {
        List<Fragment> fragments = new ArrayList<>();
        for (int i = 0; i < pathsAndValues.length; i += 2) {
            fragments.add(new Fragment(new DocumentKey("ns", "Person", id, version), pathsAndValues[i],
                    FragmentType.STRING, 0, pathsAndValues[i + 1].getBytes(StandardCharsets.UTF_8)));
        }
        try (Transaction tx = factory.createTransaction(false)) {
            persistence.createOrOverwrite(tx, Flowable.fromIterable(fragments)).blockingAwait();
        }
    }

    /** The number of entries in one of the maps of the store, the directory of subspaces left out */
    private int entries(String index) {
        var map = factory.indexByIndex.get(index);
        return map == null ? 0 : map.size();
    }
}
