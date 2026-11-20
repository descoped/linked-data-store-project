package io.descoped.lds.core.controller;

import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.core.domain.embedded.EmbeddedResourceHandler;
import io.descoped.lds.core.domain.managed.ManagedResourceHandler;
import io.descoped.lds.core.domain.reference.ReferenceResourceHandler;
import io.descoped.lds.core.domain.resource.ResourceContext;
import io.descoped.lds.core.domain.resource.ResourceException;
import io.descoped.lds.core.saga.SagaExecutionCoordinator;
import io.descoped.lds.core.saga.SagaRepository;
import io.descoped.lds.core.schema.SchemaRepository;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Deque;

import static java.util.Optional.ofNullable;

class DataController implements HttpHandler {

    private static final String TEXT_PLAIN = "text/plain";
    private static final ZoneId UTC = ZoneId.of("Etc/UTC");

    final Specification specification;
    final SchemaRepository schemaRepository;
    final RxJsonPersistence persistence;
    final SagaExecutionCoordinator sec;
    final SagaRepository sagaRepository;

    DataController(Specification specification, SchemaRepository schemaRepository, RxJsonPersistence persistence, SagaExecutionCoordinator sec, SagaRepository sagaRepository) {
        this.specification = specification;
        this.schemaRepository = schemaRepository;
        this.persistence = persistence;
        this.sec = sec;
        this.sagaRepository = sagaRepository;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        ResourceContext resourceContext;
        try {
            ZonedDateTime timestamp = timestamp(exchange);
            if (timestamp == null) {
                exchange.setStatusCode(StatusCodes.BAD_REQUEST);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
                exchange.getResponseSender().send("The 'timestamp' query-parameter must follow the ISO-8601 standard. Example of a valid formatted timestamp is '2018-12-06T11:05:31.000+01:00'");
                return;
            }
            resourceContext = ResourceContext.createResourceContext(specification, exchange.getRelativePath(), timestamp);
        } catch (ResourceException e) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
            exchange.getResponseSender().send(e.getMessage());
            return;
        }

        /*
         * NOTE: Must check reference before embedded, because ref is also an embedded resource.
         */

        if (resourceContext.isReference()) {
            new ReferenceResourceHandler(persistence, specification, resourceContext, sec, sagaRepository).handleRequest(exchange);
            return;
        }

        if (resourceContext.isManaged()) {
            new ManagedResourceHandler(persistence, specification, schemaRepository, resourceContext, sec, sagaRepository).handleRequest(exchange);
            return;
        }

        if (resourceContext.isEmbedded()) {
            new EmbeddedResourceHandler(persistence, specification, schemaRepository, resourceContext, sec, sagaRepository).handleRequest(exchange);
            return;
        }

        exchange.setStatusCode(StatusCodes.NOT_FOUND);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, TEXT_PLAIN);
        exchange.getResponseSender().send("Unsupported resource path: " + exchange.getRequestPath());
    }

    /*
     * The time of the request in UTC: the last timestamp (or t) query parameter, or now when there is none. Null
     * when the parameter is not an ISO-8601 time, as it is or url-decoded.
     */
    private static ZonedDateTime timestamp(HttpServerExchange exchange) {
        Deque<String> timestampParams = ofNullable(exchange.getQueryParameters().get("timestamp")).orElseGet(() -> exchange.getQueryParameters().get("t"));
        if (timestampParams == null || timestampParams.isEmpty()) {
            // no timestamp given by client, use time now
            return ZonedDateTime.now(UTC);
        }
        String timestampParam = timestampParams.getLast();
        ZonedDateTime timestamp = parse(timestampParam);
        if (timestamp == null) {
            // attempt to url-decode query parameter even though this is already done by web-server
            timestamp = parseUrlDecoded(timestampParam);
        }
        return timestamp == null ? null : timestamp.withZoneSameInstant(UTC);
    }

    private static ZonedDateTime parse(String timestampParam) {
        try {
            return ZonedDateTime.parse(timestampParam); // ISO-8601
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    private static ZonedDateTime parseUrlDecoded(String timestampParam) {
        try {
            return ZonedDateTime.parse(URLDecoder.decode(timestampParam, StandardCharsets.UTF_8)); // ISO-8601
        } catch (IllegalArgumentException | DateTimeParseException _) {
            return null;
        }
    }
}
