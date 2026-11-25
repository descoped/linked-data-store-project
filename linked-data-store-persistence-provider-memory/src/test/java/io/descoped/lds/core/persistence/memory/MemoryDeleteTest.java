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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a delete of one version does to the store, as it is today: the version is cleared from {@code Primary}, and
 * what it put in the index of values ({@code PathValueIndex}) stays. Not finished: {@code doDelete} builds the clean-up
 * of the index entries and never runs it; {@code deleteAllVersions} does run it. When that is corrected, this test
 * changes with it.
 */
class MemoryDeleteTest {

    private static final ZonedDateTime V1 = ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime V2 = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final MemoryTransactionFactory factory = new MemoryTransactionFactory(false);
    private final MemoryRxPersistence persistence = new MemoryRxPersistence(factory, new MemoryDirectory());

    @Test
    void aDeleteOfOneVersionClearsTheVersionAndLeavesItsIndexEntries() {
        write(V1, "Bergen");
        write(V2, "Oslo");
        assertThat(entries(MemoryRxPersistence.PATH_VALUE_INDEX)).isEqualTo(2);
        int primary = entries(MemoryRxPersistence.PRIMARY_INDEX);

        try (Transaction tx = factory.createTransaction(false)) {
            persistence.delete(tx, "ns", "Person", "ola", V1, PersistenceDeletePolicy.FAIL_IF_INCOMING_LINKS)
                    .blockingAwait();
        }

        assertThat(entries(MemoryRxPersistence.PRIMARY_INDEX)).isEqualTo(primary - 1);
        assertThat(entries(MemoryRxPersistence.PATH_VALUE_INDEX)).isEqualTo(2);
    }

    private void write(ZonedDateTime version, String city) {
        Fragment fragment = new Fragment(new DocumentKey("ns", "Person", "ola", version), "$.city", FragmentType.STRING,
                0, city.getBytes(StandardCharsets.UTF_8));
        try (Transaction tx = factory.createTransaction(false)) {
            persistence.createOrOverwrite(tx, Flowable.just(fragment)).blockingAwait();
        }
    }

    /** The number of entries in one of the maps of the store, the directory of subspaces left out */
    private int entries(String index) {
        var map = factory.indexByIndex.get(index);
        return map == null ? 0 : map.size();
    }
}
