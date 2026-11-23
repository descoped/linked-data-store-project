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
import io.descoped.lds.core.utils.RequestTime;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Deque;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

class DataController implements HttpHandler {

    private static final String TEXT_PLAIN = "text/plain";

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
                exchange.getResponseSender().send("The 'timestamp' query-parameter must follow the ISO-8601 standard. Example of a valid formatted timestamp is '" + RequestTime.EXAMPLE + "'");
                return;
            }
            resourceContext = ResourceContext.createResourceContext(specification, rawRelativePath(exchange), timestamp);
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
     * when the parameter is not an ISO-8601 time (RequestTime.parse).
     */
    private static ZonedDateTime timestamp(HttpServerExchange exchange) {
        Deque<String> timestampParams = ofNullable(exchange.getQueryParameters().get("timestamp")).orElseGet(() -> exchange.getQueryParameters().get("t"));
        if (timestampParams == null || timestampParams.isEmpty()) {
            // no timestamp given by client, use time now
            return ZonedDateTime.now(RequestTime.UTC);
        }
        return RequestTime.parse(timestampParams.getLast());
    }

    /*
     * The relative path as it was sent, not decoded, so that ResourceContext decodes it once. As Undertow 2.4 parses
     * a request (RequestParser, URLUtils.decode), with the options LDS leaves at their defaults: the request URI is
     * the path as sent; the relative path is it decoded, with an encoded '/' or '\' (%2F, %5C) left as it is and the
     * path parameters (from ';' to the next '/') left out; and a byte outside ASCII or an escape that is not one is
     * refused with 400 before a handler runs. So each segment of the relative path is one segment of the URI, and
     * the relative path is the last segments of the URI, each without its path parameters. A request line in
     * absolute form (GET http://host/path, as a proxy sends it) keeps the scheme and the host in the request URI
     * (HttpServerExchange.isHostIncludedInRequestURI), and they are left out.
     */
    static String rawRelativePath(HttpServerExchange exchange) {
        String relative = exchange.getRelativePath();
        String uri = exchange.getRequestURI();
        if (exchange.isHostIncludedInRequestURI()) {
            int slash = uri.indexOf('/', uri.indexOf("://") + 3);
            uri = slash < 0 ? "/" : uri.substring(slash);
        }
        // the segments after the '/' the relative path begins with; with a prefix the URI has more before them
        boolean rooted = relative.startsWith("/");
        int count = (rooted ? relative.substring(1) : relative).split("/", -1).length;
        String[] uriSegments = uri.split("/", -1);
        if (count > uriSegments.length) {
            return relative;
        }
        String segments = Arrays.stream(uriSegments, uriSegments.length - count, uriSegments.length)
                .map(segment -> segment.indexOf(';') < 0 ? segment : segment.substring(0, segment.indexOf(';')))
                .collect(Collectors.joining("/"));
        return rooted ? "/" + segments : segments;
    }
}
