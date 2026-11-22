package io.descoped.lds.core.health;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.PersistenceException;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.core.persistence.memory.MemoryInitializer;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The readiness: the worst state of its checks, each answered side by side within its time limit, and what each
 * failure means for LDS.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ReadinessTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final RxJsonPersistence persistence = new MemoryInitializer().initialize("ns", Map.of(), Set.of("Person"));
    private Readiness readiness = new Readiness(Duration.ofMillis(300));

    @AfterEach
    void close() {
        readiness.close();
    }

    private static ReadinessCheck answering(String name, ReadinessCheck.Result result, Health whenItFails) {
        return new ReadinessCheck(name, Single.just(result), whenItFails);
    }

    private static ReadinessCheck failing(String name, Health whenItFails) {
        return new ReadinessCheck(name, Single.error(new IllegalStateException(name + " does not answer")), whenItFails);
    }

    private static ReadinessCheck silent(String name, Health whenItFails) {
        return new ReadinessCheck(name, Single.never(), whenItFails);
    }

    /* A queue whose lag is the one given, or fails when it is null. */
    private static IndexQueue queue(IndexQueue.Lag lag) {
        return (IndexQueue) Proxy.newProxyInstance(IndexQueue.class.getClassLoader(), new Class<?>[]{IndexQueue.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "lag" -> lag == null
                            ? Single.error(new IllegalStateException("the queue's database does not answer"))
                            : Single.just(lag);
                    case "durable" -> true;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Readiness.Report report() {
        return readiness.report().blockingGet();
    }

    @Test
    void everyCheckUpIsUpAndTheEntriesKeepTheOrderOfTheChecks() {
        readiness.check(answering("persistence", ReadinessCheck.Result.up(), Health.DOWN))
                .check(answering("sagaLog", new ReadinessCheck.Result(Health.UP, Map.of("logs", 1)), Health.DOWN))
                .check(answering("search", ReadinessCheck.Result.up(), Health.DEGRADED));

        Readiness.Report report = report();

        assertThat(report.health()).isEqualTo(Health.UP);
        assertThat(report.entries()).extracting(Readiness.Entry::name).containsExactly("persistence", "sagaLog", "search");
        assertThat(report.entries().get(1).result().details()).containsEntry("logs", 1);
    }

    @Test
    void noCheckIsUp() {
        assertThat(report().health()).isEqualTo(Health.UP);
    }

    @Test
    void aPartThatWritesNeedFailingIsDownWithItsError() {
        readiness.check(failing("persistence", Health.DOWN))
                .check(failing("search", Health.DEGRADED));

        Readiness.Report report = report();

        assertThat(report.health()).isEqualTo(Health.DOWN);
        assertThat(report.entries().getFirst().result().health()).isEqualTo(Health.DOWN);
        assertThat(report.entries().getFirst().result().details()).containsEntry("error", "persistence does not answer");
    }

    @Test
    void searchFailingIsDegraded() {
        readiness.check(answering("persistence", ReadinessCheck.Result.up(), Health.DOWN))
                .check(failing("search", Health.DEGRADED));

        assertThat(report().health()).isEqualTo(Health.DEGRADED);
    }

    /**
     * Two checks that never answer are both given up after one time limit, not one after the other.
     */
    @Test
    void checksThatDoNotAnswerAreGivenUpSideBySideAfterTheTimeLimit() {
        readiness.check(silent("persistence", Health.DOWN))
                .check(silent("sagaLog", Health.DOWN))
                .check(answering("search", ReadinessCheck.Result.up(), Health.DEGRADED));

        long started = System.nanoTime();
        Readiness.Report report = report();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(Duration.ofMillis(550));
        assertThat(report.health()).isEqualTo(Health.DOWN);
        assertThat(report.entries().getFirst().result().details()).containsEntry("error", "no answer within 300 ms");
        assertThat(report.entries().getFirst().millis()).isGreaterThanOrEqualTo(300);
    }

    @Test
    void theStoreIsUpWhenItAnswersAReadAndTakesWritesAfterwards() {
        readiness.persistence(persistence, "ns", "Person");

        assertThat(report().health()).isEqualTo(Health.UP);

        // the read transaction was closed on the thread that opened it: the memory store takes a write
        try (Transaction tx = persistence.createTransaction(false)) {
            persistence.createOrOverwrite(tx, new JsonDocument(new DocumentKey("ns", "Person", "ola",
                    ZonedDateTime.parse("2026-01-01T00:00:00Z")), JsonTools.toJsonNode("{\"name\":\"Ola\"}")), null)
                    .blockingAwait();
        }
        assertThat(report().health()).isEqualTo(Health.UP);
    }

    @Test
    void aStoreThatGivesNoTransactionIsDown() {
        RxJsonPersistence broken = (RxJsonPersistence) Proxy.newProxyInstance(RxJsonPersistence.class.getClassLoader(),
                new Class<?>[]{RxJsonPersistence.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("createTransaction")) {
                        throw new PersistenceException("connection refused");
                    }
                    try {
                        return method.invoke(persistence, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        readiness.persistence(broken, "ns", "Person");

        Readiness.Report report = report();

        assertThat(report.health()).isEqualTo(Health.DOWN);
        assertThat(report.entries().getFirst().result().details()).containsEntry("error", "connection refused");
    }

    @Test
    void anEmptyIndexQueueIsUp() {
        readiness.indexQueue(queue(new IndexQueue.Lag(0, null)), CLOCK);

        Readiness.Report report = report();

        assertThat(report.health()).isEqualTo(Health.UP);
        assertThat(report.entries().getFirst().result().details())
                .containsExactly(Map.entry("durable", true), Map.entry("updates", 0L));
    }

    @Test
    void anIndexQueueWhoseOldestUpdateIsRecentIsUp() {
        readiness.indexQueue(queue(new IndexQueue.Lag(3, NOW.minusSeconds(59))), CLOCK);

        Readiness.Report report = report();

        assertThat(report.health()).isEqualTo(Health.UP);
        assertThat(report.entries().getFirst().result().details())
                .containsEntry("updates", 3L).containsEntry("oldest", NOW.minusSeconds(59).toString());
    }

    @Test
    void anIndexQueueWhoseOldestUpdateHasWaitedOverAMinuteIsDegraded() {
        readiness.indexQueue(queue(new IndexQueue.Lag(3, NOW.minusSeconds(61))), CLOCK);

        assertThat(report().health()).isEqualTo(Health.DEGRADED);
    }

    @Test
    void anIndexQueueThatDoesNotAnswerIsDownAsEveryWriteAddsToIt() {
        readiness.indexQueue(queue(null), CLOCK);

        assertThat(report().health()).isEqualTo(Health.DOWN);
    }

    @Test
    void theWorseOfTwoStates() {
        assertThat(Health.UP.worse(Health.DEGRADED)).isEqualTo(Health.DEGRADED);
        assertThat(Health.DOWN.worse(Health.DEGRADED)).isEqualTo(Health.DOWN);
        assertThat(Health.UP.worse(Health.UP)).isEqualTo(Health.UP);
    }
}
