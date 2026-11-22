package io.descoped.lds.core.controller;

import io.descoped.lds.api.persistence.PersistenceException;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.TransactionStatistics;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.undertow.Undertow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The health check opens a read-only transaction and cancels it: 200 when the store gives one, 503 when it does
 * not, whatever the store throws.
 */
class HealthCheckHandlerTest {

    @Test
    void aStoreThatGivesATransactionIsHealthyAndTheTransactionIsCancelled() throws Exception {
        AtomicInteger cancelled = new AtomicInteger();
        Transaction transaction = transaction(cancelled);

        assertThat(statusWith(() -> transaction)).isEqualTo(200);
        assertThat(cancelled).hasValue(1);
    }

    @Test
    void aStoreThatFailsWithAPersistenceExceptionIsUnavailable() throws Exception {
        assertThat(statusWith(() -> {
            throw new PersistenceException("the store is down");
        })).isEqualTo(503);
    }

    @Test
    void aStoreThatFailsWithAnyOtherExceptionIsUnavailable() throws Exception {
        assertThat(statusWith(() -> {
            throw new IllegalStateException("unexpected");
        })).isEqualTo(503);
    }

    private static int statusWith(Supplier<Transaction> transactions) throws Exception {
        RxJsonPersistence persistence = (RxJsonPersistence) Proxy.newProxyInstance(
                HealthCheckHandlerTest.class.getClassLoader(), new Class<?>[]{RxJsonPersistence.class},
                (_, method, _) -> switch (method.getName()) {
                    case "createTransaction" -> transactions.get();
                    case "toString" -> "a store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Undertow server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(new HealthCheckHandler(persistence))
                .build();
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            int port = ((InetSocketAddress) server.getListenerInfo().getFirst().getAddress()).getPort();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health/ready")).build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } finally {
            server.stop();
        }
    }

    private static Transaction transaction(AtomicInteger cancelled) {
        return new Transaction() {
            @Override
            public CompletableFuture<TransactionStatistics> commit() {
                throw new AssertionError("a health check never commits");
            }

            @Override
            public CompletableFuture<TransactionStatistics> cancel() {
                cancelled.incrementAndGet();
                return CompletableFuture.completedFuture(new TransactionStatistics());
            }
        };
    }
}
