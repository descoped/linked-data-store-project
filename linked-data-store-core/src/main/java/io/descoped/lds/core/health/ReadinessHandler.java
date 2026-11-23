package io.descoped.lds.core.health;

import io.descoped.lds.api.persistence.json.JsonTools;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.SameThreadExecutor;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/**
 * {@code /health/ready}: 200 when LDS is {@link Health#UP} or {@link Health#DEGRADED}, 503 when it is
 * {@link Health#DOWN}, with a JSON body that has the state of LDS and an entry for each check:
 * <pre>{@code
 * {"status":"DEGRADED","checks":{"persistence":{"status":"UP","millis":3},
 *  "search":{"status":"DEGRADED","millis":2000,"error":"no answer within 2000 ms"}}}
 * }</pre>
 * The checks are asked without holding a thread of the server; the answer is sent when the last has answered.
 */
public final class ReadinessHandler implements HttpHandler {

    /**
     * The path, under {@code http.prefix}.
     */
    public static final String PATH = "/health/ready";

    /**
     * The path that answers 200 whenever the process does, for a liveness probe: a part of LDS that does not answer
     * makes LDS not ready, and a restart of LDS would not bring it back.
     */
    public static final String ALIVE_PATH = "/health/alive";

    /**
     * The path that answers 200 whenever the process does.
     */
    public static final String PING_PATH = "/ping";

    private static final Logger LOG = LoggerFactory.getLogger(ReadinessHandler.class);

    private final Readiness readiness;

    /**
     * @param readiness the checks
     */
    public ReadinessHandler(Readiness readiness) {
        this.readiness = readiness;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        exchange.dispatch(SameThreadExecutor.INSTANCE, () -> readiness.report().subscribe(
                report -> respond(exchange, report),
                error -> {
                    LOG.warn("The readiness could not be found", error);
                    exchange.setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
                    exchange.endExchange();
                }));
    }

    private static void respond(HttpServerExchange exchange, Readiness.Report report) {
        Health health = report.health();
        if (health != Health.UP) {
            LOG.warn("LDS is {}: {}", health, report.entries().stream()
                    .filter(entry -> entry.result().health() != Health.UP).toList());
        }
        exchange.setStatusCode(health == Health.DOWN ? StatusCodes.SERVICE_UNAVAILABLE : StatusCodes.OK);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send(json(report).toString(), StandardCharsets.UTF_8);
    }

    static ObjectNode json(Readiness.Report report) {
        ObjectNode body = JsonTools.mapper.createObjectNode();
        body.put("status", report.health().name());
        ObjectNode checks = body.putObject("checks");
        for (Readiness.Entry entry : report.entries()) {
            ObjectNode check = checks.putObject(entry.name());
            check.put("status", entry.result().health().name());
            check.put("millis", entry.millis());
            entry.result().details().forEach((name, value) -> check.set(name, JsonTools.mapper.valueToTree(value)));
        }
        return body;
    }
}
