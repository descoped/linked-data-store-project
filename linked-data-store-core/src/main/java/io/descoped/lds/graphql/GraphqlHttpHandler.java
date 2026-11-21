package io.descoped.lds.graphql;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.undertow.attribute.ExchangeAttributes;
import io.undertow.predicate.Predicate;
import io.undertow.predicate.Predicates;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.StatusCodes;
import tools.jackson.databind.JsonNode;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;

import static io.descoped.lds.api.persistence.json.JsonTools.mapper;
import static io.undertow.util.Headers.ALLOW;
import static io.undertow.util.Methods.GET;
import static io.undertow.util.Methods.GET_STRING;
import static io.undertow.util.Methods.OPTIONS;
import static io.undertow.util.Methods.OPTIONS_STRING;
import static io.undertow.util.Methods.POST;
import static io.undertow.util.Methods.POST_STRING;

/**
 * Handler that executes GraphQL queries.
 * <p>
 * Supports GET and POST requests as described on the
 * <a href="https://graphql.org/learn/serving-over-http/">GraphQL website</a>
 */
public class GraphqlHttpHandler implements HttpHandler {

    private static final Predicate IS_JSON = Predicates.regex(
            ExchangeAttributes.requestHeader(Headers.CONTENT_TYPE),
            "application/(.*\\+)?json"
    );

    private static final Predicate IS_GRAPHQL = Predicates.regex(
            ExchangeAttributes.requestHeader(Headers.CONTENT_TYPE),
            "application/(.*\\+)?graphql"
    );

    private static final String QUERY = "query";
    private static final String VARIABLES = "variables";
    private static final String OPERATION_NAME = "operationName";

    private final GraphQL graphQl;

    /**
     * Constructs a handler with the specified GraphQL instance.
     *
     * @param graphQl the instance that will execute the queries.
     * @throws NullPointerException if the graphQl was null.
     */
    public GraphqlHttpHandler(GraphQL graphQl) {
        this.graphQl = Objects.requireNonNull(graphQl);
    }

    private static Optional<String> extractParam(Map<String, Deque<String>> parameters, String name) {
        Deque<String> parameter = parameters.get(name);
        if (parameter != null && !parameter.isEmpty()) {
            String first = parameter.removeFirst();
            if (!parameter.isEmpty()) {
                throw new IllegalArgumentException(
                        String.format("more than one \"%s\" parameter", name)
                );
            }
            return Optional.of(first);
        }
        return Optional.empty();
    }

    private static JsonNode toJson(HttpServerExchange exchange) throws IOException {
        try (InputStream bi = new BufferedInputStream(exchange.getInputStream())) {
            return mapper.readTree(new InputStreamReader(bi, Charset.forName(exchange.getRequestCharset())));
        }
    }

    private static String toString(HttpServerExchange exchange) throws IOException {
        try (InputStream i = new BufferedInputStream(exchange.getInputStream())) {
            Scanner scanner = new Scanner(i, Charset.forName(exchange.getRequestCharset())).useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    /**
     * Reads the query, the variables and the name of the operation from the body of a POST.
     *
     * @return false when the content type is neither GraphQL nor JSON
     */
    private static boolean readBody(HttpServerExchange exchange, ExecutionInput.Builder executionInput)
            throws IOException {
        if (IS_GRAPHQL.resolve(exchange)) {
            executionInput.query(toString(exchange));
        } else if (IS_JSON.resolve(exchange)) {
            JsonNode json = toJson(exchange);
            executionInput.query(JsonTools.text(json.get(QUERY)));
            if (json.has(VARIABLES) && !json.get(VARIABLES).isNull()) {
                executionInput.variables(JsonTools.toMap(json.get(VARIABLES)));
            }
            if (json.has(OPERATION_NAME)) {
                executionInput.operationName(JsonTools.text(json.get(OPERATION_NAME)));
            }
        } else {
            return false;
        }
        return true;
    }

    /**
     * Reads the query, the variables and the name of the operation from the query parameters of a GET.
     */
    private static void readQueryParameters(Map<String, Deque<String>> parameters,
                                            ExecutionInput.Builder executionInput) {
        Optional<String> query = extractParam(parameters, QUERY);
        query.ifPresent(executionInput::query);

        Optional<String> operationName = extractParam(parameters, OPERATION_NAME);
        operationName.ifPresent(executionInput::operationName);

        Optional<String> variables = extractParam(parameters, VARIABLES);
        variables.map(JsonTools::toJsonNode).map(JsonTools::toMap).ifPresent(executionInput::variables);
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        HttpString method = exchange.getRequestMethod();
        if (method.equals(OPTIONS)) {
            HeaderMap headers = exchange.getResponseHeaders();
            headers.add(ALLOW, GET.toString());
            headers.add(ALLOW, POST_STRING);
            headers.add(ALLOW, GET_STRING);
            headers.add(ALLOW, OPTIONS_STRING);
            return;
        }

        exchange.startBlocking();
        ExecutionInput.Builder executionInput = ExecutionInput.newExecutionInput();
        if (method.equals(POST)) {
            if (!readBody(exchange, executionInput)) {
                exchange.setStatusCode(StatusCodes.UNSUPPORTED_MEDIA_TYPE);
                return;
            }
        } else if (method.equals(GET)) {
            readQueryParameters(exchange.getQueryParameters(), executionInput);
        } else {
            exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
            return;
        }

        // Add context.
        executionInput.graphQLContext(Map.of(GraphQLContext.class, new GraphQLUndertowContext(exchange, executionInput.build())));

        // Execute
        ExecutionResult result = graphQl.execute(executionInput);

        // Serialize
        Map<String, Object> resultMap = result.toSpecification();
        String jsonResult = JsonTools.toJson(JsonTools.toJsonNode(resultMap));

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.setStatusCode(StatusCodes.OK);
        exchange.getResponseSender().send(jsonResult);

    }
}
