package io.descoped.lds.core.domain;

import io.descoped.lds.core.domain.resource.ResourceContext;
import io.descoped.lds.core.specification.JsonSchemaBasedSpecification;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;
import no.cantara.saga.execution.SagaHandoffResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A write whose saga fails, or whose answer cannot be sent, is answered 500 and lets the lock of its document go, so
 * the next write of the document gets it.
 */
class AsyncAnswerTest {

    private static final ResourceContext CONTEXT = context();

    private final DocumentLocks locks = new DocumentLocks(4, Duration.ofSeconds(30));
    private Undertow server;
    private HttpClient client;
    private String address;

    private static ResourceContext context() {
        try {
            return ResourceContext.createResourceContext(JsonSchemaBasedSpecification.create(
                            "spec/schemas/contact.json", "spec/schemas/provisionagreement.json"),
                    "/ns/provisionagreement/ola", ZonedDateTime.now(ZoneId.of("Etc/UTC")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    /*
     * Serves writes of one document: each takes its lock, is answered 409 when the lock is not free at once, and is
     * otherwise answered after the saga given.
     */
    private void serve(CompletableFuture<SagaHandoffResult> handoff, BiConsumer<HttpServerExchange, SagaHandoffResult> answer) {
        server = Undertow.builder().addHttpListener(0, "127.0.0.1", exchange -> {
            if (!locks.acquire(exchange, "ns", "provisionagreement", "ola").getNow(false)) {
                exchange.setStatusCode(StatusCodes.CONFLICT);
                exchange.endExchange();
                return;
            }
            AsyncAnswer.afterSaga(exchange, CONTEXT, handoff, answer);
        }).build();
        server.start();
        address = "http://127.0.0.1:" + ((InetSocketAddress) server.getListenerInfo().getFirst().getAddress()).getPort();
        client = HttpClient.newHttpClient();
    }

    private int write() throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(address)).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void aWriteWhoseSagaFailsIsAnswered500AndLetsTheLockGo() throws Exception {
        serve(CompletableFuture.failedFuture(new IllegalStateException("the saga fails")),
                (exchange, result) -> exchange.setStatusCode(StatusCodes.CREATED));

        assertThat(write()).isEqualTo(500);
        assertThat(write()).isEqualTo(500); // not 409: the first let the lock go
        assertThat(locks.waiting()).isZero();
    }

    @Test
    void aWriteWhoseAnswerFailsIsAnswered500AndLetsTheLockGo() throws Exception {
        serve(CompletableFuture.completedFuture(null), (exchange, result) -> {
            throw new IllegalStateException("the answer fails");
        });

        assertThat(write()).isEqualTo(500);
        assertThat(write()).isEqualTo(500);
        assertThat(locks.waiting()).isZero();
    }
}
