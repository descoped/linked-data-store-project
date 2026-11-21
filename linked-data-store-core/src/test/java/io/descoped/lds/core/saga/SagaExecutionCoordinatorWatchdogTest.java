package io.descoped.lds.core.saga;

import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.core.persistence.PersistenceCreateOrOverwriteSagaAdapter;
import io.descoped.lds.core.utils.FileAndClasspathReaderUtils;
import io.descoped.lds.test.ConfigurationOverride;
import io.descoped.lds.test.client.TestClient;
import io.descoped.lds.test.server.TestServer;
import io.descoped.lds.test.server.TestServerListener;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.adapter.Adapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import javax.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.descoped.lds.api.persistence.json.JsonTools.mapper;
import static org.awaitility.Awaitility.await;
import static org.testng.Assert.assertEquals;

@Listeners(TestServerListener.class)
public class SagaExecutionCoordinatorWatchdogTest {

    private static final Logger LOG = LoggerFactory.getLogger(SagaExecutionCoordinatorWatchdogTest.class);

    @Inject
    TestServer server;

    @Inject
    TestClient client;

    /**
     * Configurations where saga.threadpool.queue.capacity >= saga.threadpool.core
     * will most likely provoke a deadlock.
     */
    @Test
    @ConfigurationOverride({
            "persistence.provider", "mem",
            "sagalog.provider", "no.cantara.sagalog.memory.MemorySagaLogInitializer",
            "saga.number-of-logs", "50",
            "specification.schema", "spec/schemas/contact.json,spec/schemas/provisionagreement.json",
            "saga.threadpool.core", "15",
            "saga.threadpool.max", "50",
            "saga.threadpool.queue.capacity", "100",
    })
    public void thatWatchdogIsTriggeredWhenSagaThreadpoolIsDeadlocked() throws InterruptedException {
        SagaExecutionCoordinator sec = server.getApplication().getSec();
        /*
         * The slow node holds its pool thread until the test releases it, after the watchdog has acted. A sleep of a
         * fixed time does not do: whether the pool deadlocks then depends on how fast the requests arrive, and on a
         * slow machine the sagas drain before the watchdog looks.
         */
        CountDownLatch releaseSlowNodes = new CountDownLatch(1);

        {
            /*
             * Configure a dummy slow node before saga fan-out in order to provoke deadlock.
             */
            final ObjectNode empty = mapper.createObjectNode();
            sec.sagaRepository.getAdapterLoader().register(new Adapter<>(JsonNode.class, "SlowNodeAdapter", (i, d) -> {
                try {
                    releaseSlowNodes.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return empty;
            }));
            sec.sagaRepository.getAdapterLoader().register(new Adapter<>(JsonNode.class, "search", (i, d) -> empty));
            sec.sagaRepository.register(Saga
                    .start(SagaRepository.SAGA_CREATE_OR_UPDATE_MANAGED_RESOURCE).linkTo("slownode", "search-index-update")
                    .id("slownode").adapter("SlowNodeAdapter").linkTo("persistence")
                    .id("persistence").adapter(PersistenceCreateOrOverwriteSagaAdapter.ADAPTER_NAME).linkToEnd()
                    .id("search-index-update").adapter("search").linkToEnd()
                    .end());
        }

        int sagaThreadPoolCoreSize = sec.threadPool.getCorePoolSize();

        final ExecutorService executor = Executors.newFixedThreadPool(sagaThreadPoolCoreSize);

        try {
            JsonNode provisionAgreementSirius = resource("provisionagreement_sirius.json");

            CountDownLatch requestsHandled = new CountDownLatch(sagaThreadPoolCoreSize);
            AtomicInteger requestsFailed = new AtomicInteger();
            for (int i = 0; i < sagaThreadPoolCoreSize; i++) {
                CompletableFuture.runAsync(() -> {
                    try {
                        client.put("/data/provisionagreement/100", provisionAgreementSirius.toString()).expect201Created();
                    } catch (Throwable t) {
                        requestsFailed.incrementAndGet();
                        t.printStackTrace();
                    }
                    requestsHandled.countDown();
                }, executor).exceptionally(t -> {
                    requestsFailed.incrementAndGet();
                    requestsHandled.countDown();
                    t.printStackTrace();
                    return null;
                });
            }

            LOG.debug("Waiting for deadlock to settle");
            await().pollDelay(Duration.ofMillis(300)).until(() -> true);

            {
                LOG.debug("Confirm that there is a deadlock");
                assertEquals(sec.threadPoolWatchDog.deadlockResolutionAttemptCounter.get(), 0);
                /*
                 * The state of the pool is compared with its state a second before, 30 times at most; the half second
                 * over 30 lets the 30th look be made before the wait gives up.
                 */
                AtomicInteger previousActiveCount = new AtomicInteger(sec.threadPool.getActiveCount());
                AtomicLong previousCompletedTaskCount = new AtomicLong(sec.threadPool.getCompletedTaskCount());
                LOG.debug("Threadpool-state: {}", sec.threadPool);
                await("Unable to provoke deadlock within 30 seconds").atMost(Duration.ofMillis(30_500))
                        .pollDelay(Duration.ofSeconds(1)).pollInterval(Duration.ofSeconds(1))
                        .until(() -> {
                            boolean deadlock = sec.threadPoolWatchDog.possibleDeadlock(previousActiveCount.get(), previousCompletedTaskCount.get());
                            previousActiveCount.set(sec.threadPool.getActiveCount());
                            previousCompletedTaskCount.set(sec.threadPool.getCompletedTaskCount());
                            LOG.debug("Threadpool-state: {}", sec.threadPool);
                            return deadlock;
                        });
                LOG.debug("DEADLOCKED!");
            }

            LOG.debug("Starting saga watch-dog");
            sec.startThreadpoolWatchdog();

            LOG.debug("Waiting for deadlock resolution attempt");
            await("Unable to detect deadlock resolution attempt within 30 seconds").atMost(Duration.ofSeconds(30))
                    .pollDelay(Duration.ofSeconds(1)).pollInterval(Duration.ofSeconds(1))
                    .until(() -> {
                        LOG.debug("Threadpool-state: {}", sec.threadPool);
                        return sec.threadPoolWatchDog.deadlockResolutionAttemptCounter.get() != 0;
                    });

            LOG.debug("Waiting for sagas to complete after deadlock resolution attempt");
            releaseSlowNodes.countDown();

            boolean allCompleted = requestsHandled.await(30, TimeUnit.SECONDS);
            if (allCompleted) {
                LOG.debug("All sagas completed after deadlock resolution");
            } else {
                Assert.fail("Not all sagas completed");
            }
            assertEquals(requestsFailed.get(), 0, "requests that failed");
            shutdownAndAwaitTermination(executor);
        } finally {
            releaseSlowNodes.countDown();
            if (!executor.isShutdown()) {
                shutdownAndAwaitTermination(executor);
            }
        }
    }

    static final JsonNode resource(String resourceName) {
        return JsonTools.toJsonNode(FileAndClasspathReaderUtils.getResourceAsString("spec/schemas.examples/" + resourceName, StandardCharsets.UTF_8));
    }

    static void shutdownAndAwaitTermination(ExecutorService pool) {
        shutdownAndAwaitTermination(pool, 5, TimeUnit.SECONDS);
    }

    static void shutdownAndAwaitTermination(ExecutorService pool, int timeout, TimeUnit unit) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(timeout, unit)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                    System.err.println("Pool did not terminate");
                }
            }
        } catch (InterruptedException _) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

