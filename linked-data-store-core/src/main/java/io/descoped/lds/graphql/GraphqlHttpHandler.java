package io.descoped.lds.graphql;

import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphqlErrorBuilder;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.core.domain.BodyParser;
import io.undertow.attribute.ExchangeAttributes;
import io.undertow.predicate.Predicate;
import io.undertow.predicate.Predicates;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.StatusCodes;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
                throw new BadRequest(String.format("more than one \"%s\" parameter", name));
            }
            return Optional.of(first);
        }
        return Optional.empty();
    }

    /**
     * A request that is not a GraphQL request LDS can read: answered 400 with the message.
     */
    private static final class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }

    private static JsonNode readJson(String what, String text) {
        if (text.isBlank()) {
            throw new BadRequest("The " + what + " is empty");
        }
        try {
            return mapper.readTree(text);
        } catch (JacksonException e) {
            throw new BadRequest("The " + what + " cannot be read as JSON" + BodyParser.describe(e));
        }
    }

    private static JsonNode toJson(HttpServerExchange exchange) throws IOException {
        JsonNode json = readJson("body", toString(exchange));
        if (!json.isObject()) {
            throw new BadRequest("The body is not a JSON object with the field \"query\"");
        }
        return json;
    }

    private static String toString(HttpServerExchange exchange) throws IOException {
        try (InputStream i = new BufferedInputStream(exchange.getInputStream())) {
            return new String(i.readAllBytes(), StandardCharsets.UTF_8);
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
            String query = toString(exchange);
            if (query.isBlank()) {
                throw new BadRequest("The body is empty");
            }
            executionInput.query(query);
        } else if (IS_JSON.resolve(exchange)) {
            JsonNode json = toJson(exchange);
            String query = JsonTools.text(json.path(QUERY));
            if (query == null) {
                throw new BadRequest("The body has no \"query\" text");
            }
            executionInput.query(query);
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
        String query = extractParam(parameters, QUERY)
                .orElseThrow(() -> new BadRequest("The request has no \"query\" parameter"));
        executionInput.query(query);

        Optional<String> operationName = extractParam(parameters, OPERATION_NAME);
        operationName.ifPresent(executionInput::operationName);

        Optional<String> variables = extractParam(parameters, VARIABLES);
        variables.map(text -> readJson("parameter \"variables\"", text))
                .map(JsonTools::toMap).ifPresent(executionInput::variables);
    }

    private static void sendError(HttpServerExchange exchange, int status, String message) {
        ObjectNode answer = mapper.createObjectNode();
        answer.putArray("errors").addObject().put("message", message);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.setStatusCode(status);
        exchange.getResponseSender().send(JsonTools.toJson(answer));
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
        try {
            if (method.equals(POST)) {
                if (!readBody(exchange, executionInput)) {
                    sendError(exchange, StatusCodes.UNSUPPORTED_MEDIA_TYPE, "Unsupported Content-Type: "
                            + exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE)
                            + ". Send application/json or application/graphql");
                    return;
                }
            } else if (method.equals(GET)) {
                readQueryParameters(exchange.getQueryParameters(), executionInput);
            } else {
                exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
                return;
            }
        } catch (BadRequest e) {
            sendError(exchange, StatusCodes.BAD_REQUEST, e.getMessage());
            return;
        }

        // Add context. A snapshot that cannot be read is answered as an error, and the query is not run.
        ExecutionResult result;
        try {
            executionInput.graphQLContext(Map.of(GraphQLContext.class, new GraphQLUndertowContext(exchange, executionInput.build())));
            result = graphQl.execute(executionInput);
        } catch (IllegalArgumentException e) {
            result = ExecutionResult.newExecutionResult()
                    .addError(GraphqlErrorBuilder.newError().message(e.getMessage()).errorType(ErrorType.ValidationError).build())
                    .build();
        }

        // Serialize
        Map<String, Object> resultMap = result.toSpecification();
        String jsonResult = JsonTools.toJson(JsonTools.toJsonNode(resultMap));

        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.setStatusCode(StatusCodes.OK);
        exchange.getResponseSender().send(jsonResult);

    }
}
