package io.descoped.lds.core.domain;

import io.descoped.lds.core.domain.resource.ResourceContext;
import io.undertow.io.Receiver;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.SameThreadExecutor;
import io.undertow.util.StatusCodes;
import no.cantara.saga.execution.SagaHandoffResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;

/**
 * How a request is read and answered without a thread that waits: its body is read as it comes, and the request goes
 * on on a worker thread when the body has all come (an I/O thread never runs a saga or a query); a write is answered
 * when its saga, and its wait for search when it asked for one, have completed, from the thread that completed them.
 */
public final class AsyncAnswer {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncAnswer.class);

    private AsyncAnswer() {
    }

    /**
     * Reads the body of the request without blocking, and goes on with it on a worker thread.
     *
     * @param exchange the request
     * @param then     what is done with it, given the request and its body
     * @param error    called when the body could not be read, on the I/O thread: it only sends a short answer
     */
    public static void withBody(HttpServerExchange exchange, BiConsumer<HttpServerExchange, String> then,
                                Receiver.ErrorCallback error) {
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            if (ex.isInIoThread()) {
                ex.dispatch(dispatched -> then.accept(dispatched, body));
            } else {
                then.accept(ex, body);
            }
        }, error, StandardCharsets.UTF_8);
    }

    /**
     * Answers the write when its saga, or its handoff, has completed, and its wait for search when it asked for one
     * ({@link SearchWait}); until then the request is kept open and no thread waits. Called on the thread that
     * handles the request.
     *
     * @param exchange the request
     * @param context  the document of the write
     * @param handoff  the completion of the saga, or of its handoff
     * @param answer   sends the answer, given the request and the result of the handoff
     */
    public static void afterSaga(HttpServerExchange exchange, ResourceContext context,
                                 CompletableFuture<SagaHandoffResult> handoff,
                                 BiConsumer<HttpServerExchange, SagaHandoffResult> answer) {
        exchange.dispatch(SameThreadExecutor.INSTANCE, () -> handoff
                .thenCompose(result -> SearchWait.whenAsked(exchange, context).thenApply(_ -> result))
                .whenComplete((result, error) -> {
                    if (error == null) {
                        try {
                            answer.accept(exchange, result);
                            return;
                        } catch (RuntimeException e) {
                            error = e;
                        }
                    }
                    failed(exchange, error);
                }));
    }

    private static void failed(HttpServerExchange exchange, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        LOG.error("A write of {} failed", exchange.getRequestPath(), cause);
        if (!exchange.isResponseStarted()) {
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
        }
        exchange.endExchange();
    }
}
