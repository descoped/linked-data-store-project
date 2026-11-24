package io.descoped.lds.core.health;

import io.descoped.lds.api.persistence.reactivex.Range;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.core.persistence.StoreReads;
import io.descoped.lds.core.txlog.TxlogRawdataPool;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import no.cantara.sagalog.SagaLogPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Whether LDS can take writes and answer: every part it needs is asked, side by side, each with a time limit, and
 * the answer is the worst of their states, with one entry for each. The store, the saga log, the transaction log and
 * the index queue are needed for a write, so LDS is {@link Health#DOWN} when one of them does not answer. Search is
 * not needed for a write, as the queue keeps what the index has not taken: LDS is {@link Health#DEGRADED} when the
 * index does not answer, or when the oldest update in the queue has waited longer than {@link #INDEX_LAG_DEGRADED}.
 * <p>
 * A part whose client blocks (JDBC) is asked on a virtual thread of its own, opened and closed there.
 */
public final class Readiness implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Readiness.class);

    /**
     * How long a part is given to answer.
     */
    public static final Duration CHECK_TIMEOUT = Duration.ofSeconds(2);

    /**
     * How long the oldest update in the index queue may wait before search is behind: the longest the worker puts
     * off an update the index did not take.
     */
    public static final Duration INDEX_LAG_DEGRADED = Duration.ofMinutes(1);

    private final List<ReadinessCheck> checks = new ArrayList<>();
    private final Duration timeout;
    private final ExecutorService blockingExecutor = Executors.newVirtualThreadPerTaskExecutor();
    // interrupts a check that is given up, so that the thread of a hung store or database ends
    private final Scheduler blocking = Schedulers.from(blockingExecutor, true);

    /**
     * A readiness with no check yet, each check given {@link #CHECK_TIMEOUT}.
     */
    public Readiness() {
        this(CHECK_TIMEOUT);
    }

    Readiness(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * Adds a check, asked after those added before it.
     *
     * @param check the check
     * @return this
     */
    public Readiness check(ReadinessCheck check) {
        checks.add(check);
        return this;
    }

    /**
     * The store answers a read of one document of an entity type, in a read transaction opened and closed on a
     * thread of the store's ({@link StoreReads}); no thread waits for it.
     *
     * @param persistence the store
     * @param namespace   the namespace
     * @param entity      an entity type of the model
     * @return this
     */
    public Readiness persistence(RxJsonPersistence persistence, String namespace, String entity) {
        return check(new ReadinessCheck("persistence", StoreReads.single(persistence,
                        transaction -> persistence.readDocuments(transaction, ZonedDateTime.now(Clock.systemUTC()),
                                namespace, entity, Range.first(1)).count())
                .map(_ -> ReadinessCheck.Result.up()), Health.DOWN));
    }

    /**
     * The saga log answers the list of its logs, which a database behind it is asked for.
     *
     * @param pool the pool of saga logs
     * @return this
     */
    public Readiness sagaLog(SagaLogPool pool) {
        return check(new ReadinessCheck("sagaLog", Single.fromCallable(() ->
                new ReadinessCheck.Result(Health.UP, Map.<String, Object>of("logs", pool.clusterWideLogIds().size())))
                .subscribeOn(blocking), Health.DOWN));
    }

    /**
     * The transaction log answers the last message of its topic.
     *
     * @param pool the transaction log
     * @return this
     */
    public Readiness transactionLog(TxlogRawdataPool pool) {
        return check(new ReadinessCheck("transactionLog", Single.fromCallable(() -> {
            pool.getLastMessage(pool.getDefaultSource());
            return new ReadinessCheck.Result(Health.UP, Map.<String, Object>of("keepsMessages", pool.keepsMessages()));
        }).subscribeOn(blocking), Health.DOWN));
    }

    /**
     * The search index answers.
     *
     * @param index the search index
     * @return this
     */
    public Readiness search(SearchIndex index) {
        return check(new ReadinessCheck("search", index.health().toSingle(ReadinessCheck.Result::up), Health.DEGRADED));
    }

    /**
     * The index queue answers how many updates it holds and when the oldest was added; search is behind when that
     * is longer ago than {@link #INDEX_LAG_DEGRADED}.
     *
     * @param queue the index queue
     * @param clock the clock the age is measured by
     * @return this
     */
    public Readiness indexQueue(IndexQueue queue, Clock clock) {
        return check(new ReadinessCheck("indexQueue", queue.lag().map(lag -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("durable", queue.durable());
            details.put("updates", lag.updates());
            Health health = Health.UP;
            if (lag.updates() > 0 && lag.oldest() != null) {
                details.put("oldest", lag.oldest().toString());
                if (lag.oldest().isBefore(Instant.now(clock).minus(INDEX_LAG_DEGRADED))) {
                    health = Health.DEGRADED;
                }
            }
            return new ReadinessCheck.Result(health, details);
        }), Health.DOWN));
    }

    /**
     * Asks every part, side by side.
     *
     * @return the answer, its entries in the order the checks were added
     */
    public Single<Report> report() {
        return Flowable.fromIterable(List.copyOf(checks))
                .concatMapEager(check -> ask(check).toFlowable())
                .toList()
                .map(Report::new);
    }

    private Single<Entry> ask(ReadinessCheck check) {
        return Single.defer(() -> {
            long started = System.nanoTime();
            return check.probe()
                    .timeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .onErrorReturn(error -> new ReadinessCheck.Result(check.whenItFails(),
                            Map.of("error", error(check, error))))
                    .map(result -> new Entry(check.name(), result,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        });
    }

    /*
     * What the answer says of a failure: that the part did not answer in time, or the kind of error. Anyone may ask
     * the readiness, and the message of an error can name hosts, ports and databases, so it goes to the log only.
     */
    private String error(ReadinessCheck check, Throwable error) {
        if (error instanceof TimeoutException) {
            return "no answer within " + timeout.toMillis() + " ms";
        }
        LOG.warn("The readiness check {} failed: {}", check.name(), error.toString());
        return error.getClass().getSimpleName();
    }

    /**
     * Ends the threads of checks that still wait for an answer.
     */
    @Override
    public void close() {
        blockingExecutor.shutdownNow();
    }

    /**
     * The answer of one check.
     *
     * @param name   the name of the check
     * @param result what it found
     * @param millis how long it took
     */
    public record Entry(String name, ReadinessCheck.Result result, long millis) {
    }

    /**
     * The answer of every check.
     *
     * @param entries the answers, in the order of the checks
     */
    public record Report(List<Entry> entries) {

        /**
         * @return the worst state of the checks, {@link Health#UP} when there are none
         */
        public Health health() {
            return entries.stream().map(entry -> entry.result().health()).reduce(Health.UP, Health::worse);
        }
    }
}
