package io.descoped.lds.core.persistence.neo4j;

import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.exceptions.ClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transaction of the provider, on a real Neo4j: its session, and the streams of its statements.
 */
class Neo4jTransactionTest {

    private static Driver driver;

    @BeforeAll
    static void start() {
        driver = Neo4j2026.driver();
    }

    @AfterAll
    static void stop() {
        if (driver != null) {
            driver.close();
        }
    }

    @Test
    void theSessionIsClosedWhenTheTransactionIsCommitted() {
        Session session = driver.session();
        Neo4jTransaction transaction = new Neo4jTransaction(session, false);
        assertThat(session.isOpen()).isTrue();

        transaction.commit().join();

        assertThat(session.isOpen()).isFalse();
    }

    @Test
    void theSessionIsClosedWhenTheTransactionIsCancelled() {
        Session session = driver.session();
        Neo4jTransaction transaction = new Neo4jTransaction(session, false);

        transaction.cancel().join();

        assertThat(session.isOpen()).isFalse();
    }

    @Test
    void aStatementIsRunWhenItsStreamIsSubscribedTo() {
        Session session = driver.session();
        Neo4jTransaction transaction = new Neo4jTransaction(session, false);
        try {
            // never subscribed to: this is not Cypher, and nothing fails
            transaction.executeCypherAsync("this is not Cypher", Map.of());

            TestSubscriber<Record> records = transaction.executeCypherAsync("UNWIND range(1, 5) AS x RETURN x", Map.of()).test(2);
            records.assertValueCount(2).assertNotComplete();
            records.requestMore(10);
            records.assertValueCount(5).assertComplete();
        } finally {
            transaction.cancel().join();
        }
    }

    @Test
    void aStatementThatFailsFailsItsStreamAndNothingElse() {
        List<Throwable> undeliverable = new CopyOnWriteArrayList<>();
        RxJavaPlugins.setErrorHandler(undeliverable::add);
        Session session = driver.session();
        Neo4jTransaction transaction = new Neo4jTransaction(session, false);
        try {
            transaction.executeCypherAsync("this is not Cypher", Map.of()).test()
                    .assertError(ClientException.class);
        } finally {
            transaction.cancel().join();
        }

        session = driver.session();
        transaction = new Neo4jTransaction(session, false);
        try {
            // fails on the server while its records are read
            transaction.executeCypherAsync("UNWIND [1, 1, 0] AS x RETURN 1 / x AS y", Map.of()).test()
                    .assertError(ClientException.class);
        } finally {
            transaction.cancel().join();
            RxJavaPlugins.setErrorHandler(null);
        }
        assertThat(session.isOpen()).isFalse();
        assertThat(undeliverable).isEmpty();
    }

    @Test
    void theStatementForTheLogTakesEachParameterByItsWholeName() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("id", "oslo");
        parameters.put("idAfter", "bergen");
        parameters.put("limit", 3);

        assertThat(Neo4jTransaction.interactive("MATCH (r {id: $id}) WHERE $idAfter < r.id RETURN r LIMIT $limit", parameters))
                .isEqualTo("MATCH (r {id: 'oslo'}) WHERE 'bergen' < r.id RETURN r LIMIT 3");
    }

    @Test
    void aCommitThatFailsFailsItsFutureAndClosesTheSession() {
        Session session = driver.session();
        Neo4jTransaction transaction = new Neo4jTransaction(session, false);
        transaction.executeCypherAsync("this is not Cypher", Map.of()).test().assertError(ClientException.class);

        // the server has ended the transaction: there is nothing to commit
        assertThatThrownBy(() -> transaction.commit().join()).hasCauseInstanceOf(ClientException.class);
        assertThatThrownBy(transaction::close).hasCauseInstanceOf(ClientException.class);
        assertThat(session.isOpen()).isFalse();
    }
}
