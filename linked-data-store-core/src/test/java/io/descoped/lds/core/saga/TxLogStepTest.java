package io.descoped.lds.core.saga;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.core.txlog.AppendTxLogAdapter;
import io.descoped.lds.core.txlog.TxlogRawdataPool;
import io.descoped.rawdata.api.RawdataClient;
import io.descoped.rawdata.api.RawdataIds;
import io.descoped.rawdata.api.RawdataMessage;
import io.descoped.rawdata.api.RawdataProducer;
import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.SagaExecution;
import no.cantara.saga.execution.SagaHandoffResult;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The step that writes to the transaction log publishes asynchronously: while the transaction log has not answered,
 * the step holds no thread of the saga pool.
 */
class TxLogStepTest {

    /* The publishes the transaction log was given, each answered when the test completes its future. */
    private final List<Published> published = new CopyOnWriteArrayList<>();

    private record Published(RawdataMessage message, CompletableFuture<Void> answer) {
    }

    /* A saga pool of one thread: a step that held it would keep every other saga from running a step. */
    private final SelectableThreadPoolExecutor pool = new SelectableThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100));

    private final SagaLogPool logs = memorySagaLogs();

    private final AdapterLoader adapters = new AdapterLoader()
            .register(new AppendTxLogAdapter(new TxlogRawdataPool(transactionLog(), false, "default", "", true)));

    private final Saga saga = Saga.start("txlog only").linkTo("txlog")
            .id("txlog").adapter(AppendTxLogAdapter.ADAPTER_NAME).linkToEnd()
            .end();

    @AfterEach
    void stop() {
        pool.shutdownNow();
        logs.shutdown();
    }

    /**
     * Two sagas on a pool of one thread both reach the transaction log before it has answered the first, and each
     * completes when its publish has.
     */
    @Test
    void aStepWaitingForTheTransactionLogHoldsNoThread() {
        CompletableFuture<SagaHandoffResult> first = start("01");
        CompletableFuture<SagaHandoffResult> second = start("02");

        await().pollInterval(Duration.ofMillis(5)).atMost(Duration.ofSeconds(5))
                .until(() -> published.size() == 2);
        assertThat(first).isNotDone();
        assertThat(second).isNotDone();

        published.get(1).answer().complete(null);
        assertThat(second).succeedsWithin(Duration.ofSeconds(5)).matches(SagaHandoffResult::isSuccess);
        assertThat(first).isNotDone();

        published.get(0).answer().complete(null);
        assertThat(first).succeedsWithin(Duration.ofSeconds(5)).matches(SagaHandoffResult::isSuccess);
    }

    /**
     * A publish that fails fails the saga, with the exception of the transaction log as its cause.
     */
    @Test
    void aPublishThatFailsFailsTheSaga() {
        CompletableFuture<SagaHandoffResult> saga = start("01");
        await().pollInterval(Duration.ofMillis(5)).atMost(Duration.ofSeconds(5))
                .until(() -> published.size() == 1);

        IllegalStateException refused = new IllegalStateException("the transaction log refused it");
        published.getFirst().answer().completeExceptionally(refused);

        SagaHandoffResult result = assertThat(saga).succeedsWithin(Duration.ofSeconds(5)).actual();
        assertThat(result.isFailure()).isTrue();
        assertThat(result.getFailureCause()).isSameAs(refused);
    }

    private CompletableFuture<SagaHandoffResult> start(String log) {
        SagaInput input = new SagaInput(RawdataIds.generate(), "PUT", "TODO",
                new DocumentKey("ns", "Person", "p" + log, ZonedDateTime.now()), null, null, null);
        CompletableFuture<SagaHandoffResult> completed = new CompletableFuture<>();
        new SagaExecution(logs.connect(logs.registerInstanceLocalIdFor(log)), pool, saga, adapters)
                .executeSaga(input.txId(), input.asJsonNode(), false, completed::complete);
        return completed;
    }

    /* The saga logs in memory, found as LDS finds a saga log provider: by the name of its class. */
    private static SagaLogPool memorySagaLogs() {
        SagaLogInitializer initializer = ServiceLoader.load(SagaLogInitializer.class).stream()
                .filter(provider -> provider.type().getName().equals(SagaLogInitializers.MEMORY))
                .findFirst().orElseThrow().get();
        return initializer.initialize(initializer.configurationOptionsAndDefaults());
    }

    /* A transaction log whose every producer records what it is given and answers when the test says so. */
    private RawdataClient transactionLog() {
        RawdataProducer producer = (RawdataProducer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RawdataProducer.class}, (_, method, args) -> switch (method.getName()) {
                    case "publishAsync" -> {
                        CompletableFuture<Void> answer = new CompletableFuture<>();
                        published.add(new Published(((RawdataMessage[]) args[0])[0], answer));
                        yield answer;
                    }
                    case "isClosed" -> false;
                    case "topic" -> "default";
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (RawdataClient) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RawdataClient.class}, (_, method, _) -> switch (method.getName()) {
                    case "producer" -> producer;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
