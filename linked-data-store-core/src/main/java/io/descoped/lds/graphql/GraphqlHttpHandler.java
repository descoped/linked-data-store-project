package io.descoped.lds.graphql;

import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphqlErrorBuilder;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.core.domain.AsyncAnswer;
import io.descoped.lds.core.domain.BodyParser;
import io.undertow.attribute.ExchangeAttributes;
import io.undertow.predicate.Predicate;
import io.undertow.predicate.Predicates;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.SameThreadExecutor;
import io.undertow.util.StatusCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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

    private static final Logger LOG = LoggerFactory.getLogger(GraphqlHttpHandler.class);

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
            if (parameter.size() > 1) {
                throw new BadRequest(String.format("more than one \"%s\" parameter", name));
            }
            return Optional.of(parameter.getFirst());
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

    private static JsonNode variables(JsonNode variables) {
        if (!variables.isObject()) {
            throw new BadRequest("The variables are not a JSON object");
        }
        return variables;
    }

    private static JsonNode toJson(String body) {
        JsonNode json = readJson("body", body);
        if (!json.isObject()) {
            throw new BadRequest("The body is not a JSON object with the field \"query\"");
        }
        return json;
    }

    /**
     * Reads the query, the variables and the name of the operation from the body of a POST.
     *
     * @return false when the content type is neither GraphQL nor JSON
     */
    private static boolean readBody(HttpServerExchange exchange, String body, ExecutionInput.Builder executionInput) {
        if (IS_GRAPHQL.resolve(exchange)) {
            String query = body;
            if (query.isBlank()) {
                throw new BadRequest("The body is empty");
            }
            executionInput.query(query);
        } else if (IS_JSON.resolve(exchange)) {
            JsonNode json = toJson(body);
            String query = JsonTools.text(json.path(QUERY));
            if (query == null) {
                throw new BadRequest("The body has no \"query\" text");
            }
            executionInput.query(query);
            if (json.has(VARIABLES) && !json.get(VARIABLES).isNull()) {
                executionInput.variables(JsonTools.toMap(variables(json.get(VARIABLES))));
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
                .map(GraphqlHttpHandler::variables).map(JsonTools::toMap).ifPresent(executionInput::variables);
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
            headers.add(ALLOW, GET_STRING);
            headers.add(ALLOW, POST_STRING);
            headers.add(ALLOW, OPTIONS_STRING);
            return;
        }

        ExecutionInput.Builder executionInput = ExecutionInput.newExecutionInput();
        if (method.equals(POST)) {
            // the body is read as it comes; the query runs on a worker thread when it has all come
            AsyncAnswer.withBody(exchange, (ex, body) -> {
                try {
                    if (!readBody(ex, body, executionInput)) {
                        sendError(ex, StatusCodes.UNSUPPORTED_MEDIA_TYPE, "Unsupported Content-Type: "
                                + ex.getRequestHeaders().getFirst(Headers.CONTENT_TYPE)
                                + ". Send application/json or application/graphql");
                        return;
                    }
                } catch (BadRequest e) {
                    sendError(ex, StatusCodes.BAD_REQUEST, e.getMessage());
                    return;
                }
                execute(ex, executionInput);
            }, (ex, e) -> sendError(ex, StatusCodes.BAD_REQUEST, "The body cannot be read: " + e.getMessage()));
        } else if (method.equals(GET)) {
            try {
                readQueryParameters(exchange.getQueryParameters(), executionInput);
            } catch (BadRequest e) {
                sendError(exchange, StatusCodes.BAD_REQUEST, e.getMessage());
                return;
            }
            execute(exchange, executionInput);
        } else {
            exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
        }
    }

    /*
     * Runs the query and answers when it has completed; no thread waits for it. A snapshot that cannot be read is
     * answered as an error, and the query is not run.
     */
    private void execute(HttpServerExchange exchange, ExecutionInput.Builder executionInput) {
        CompletableFuture<ExecutionResult> execution;
        try {
            executionInput.graphQLContext(Map.of(GraphQLContext.class, new GraphQLUndertowContext(exchange, executionInput.build())));
            execution = graphQl.executeAsync(executionInput);
        } catch (IllegalArgumentException e) {
            execution = CompletableFuture.completedFuture(invalid(e));
        }
        CompletableFuture<ExecutionResult> answer = execution;
        exchange.dispatch(SameThreadExecutor.INSTANCE, () -> answer.whenComplete((result, error) -> {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            if (cause instanceof IllegalArgumentException invalid) {
                result = invalid(invalid);
            } else if (cause != null) {
                LOG.error("A GraphQL query failed", cause);
                exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                exchange.endExchange();
                return;
            }
            // Serialize
            Map<String, Object> resultMap = result.toSpecification();
            String jsonResult = JsonTools.toJson(JsonTools.toJsonNode(resultMap));

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send(jsonResult);
        }));
    }

    private static ExecutionResult invalid(IllegalArgumentException e) {
        return ExecutionResult.newExecutionResult()
                .addError(GraphqlErrorBuilder.newError().message(e.getMessage()).errorType(ErrorType.ValidationError).build())
                .build();
    }
}
