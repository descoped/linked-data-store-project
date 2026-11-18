package io.descoped.lds.persistence.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.descoped.lds.persistence.api.Document;
import io.descoped.lds.persistence.api.DocumentKey;
import io.descoped.lds.persistence.api.Transaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * What is particular to the memory provider; the contract itself is in {@link MemoryPersistenceTckTest}.
 */
class MemoryPersistenceTest {

    private static final DocumentKey JOHN = new DocumentKey("ns", "Person", "john", Instant.parse("2020-01-01T00:00:00Z"));

    private final MemoryPersistence persistence = new MemoryPersistence();

    private static Document john(String lastname) {
        return new Document(JOHN, new ObjectMapper().createObjectNode().put("firstname", "John").put("lastname", lastname));
    }

    @Test
    void twoTransactionsWritingTheSameVersionLeaveNothingBehind() {
        try (Transaction first = persistence.begin(false); Transaction second = persistence.begin(false)) {
            persistence.write(first, List.of(john("First")));
            persistence.write(second, List.of(john("Second")));
            first.commit();
            second.commit();
        }
        assertThat(persistence.committedKeys()).as("one document and its two string values").isEqualTo(3);

        try (Transaction tx = persistence.begin(false)) {
            assertThat(persistence.read(tx, JOHN.version(), "ns", "Person", "john").orElseThrow().json().get("lastname").textValue())
                    .isEqualTo("Second");
            persistence.deleteAllVersions(tx, "ns", "Person", "john");
            tx.commit();
        }
        assertThat(persistence.committedKeys()).isZero();
    }

    @Test
    void aVersionRemovedBehindATransactionsBackLeavesNothingBehind() {
        try (Transaction tx = persistence.begin(false)) {
            persistence.write(tx, List.of(john("Smith")));
            tx.commit();
        }
        try (Transaction marker = persistence.begin(false); Transaction rewrite = persistence.begin(false)) {
            persistence.markDeleted(marker, JOHN);
            persistence.write(rewrite, List.of(john("Jones")));
            rewrite.commit();
            marker.commit();
        }
        assertThat(persistence.committedKeys()).as("the delete marker alone").isEqualTo(1);
    }

    @Test
    void closingThePersistenceRollsBackOpenTransactions() {
        Transaction open = persistence.begin(false);
        persistence.write(open, List.of(john("Smith")));

        persistence.close();

        assertThatIllegalStateException().isThrownBy(open::commit).withMessageContaining("closed");
        assertThatIllegalStateException().isThrownBy(() -> persistence.begin(true));
        assertThat(persistence.committedKeys()).isZero();
        open.close();
        persistence.close();
    }
}
