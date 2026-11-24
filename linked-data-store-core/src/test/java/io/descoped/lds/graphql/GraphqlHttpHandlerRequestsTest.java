package io.descoped.lds.graphql;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.undertow.Undertow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static graphql.schema.idl.RuntimeWiring.newRuntimeWiring;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The requests the GraphQL endpoint answers: a query in a GraphQL body, and one in a JSON body with variables and
 * the name of an operation; OPTIONS names the methods; another method and another content type are refused. A
 * parameter given twice and a GET without a query are answered 400 with an error, and an execution that fails 500.
 */
class GraphqlHttpHandlerRequestsTest {

    private Undertow server;
    private HttpClient client;
    private String address;

    @BeforeEach
    void start() {
        serve(graphQl().build());
        client = HttpClient.newHttpClient();
    }

    private static GraphQL.Builder graphQl() {
        return GraphQL.newGraphQL(new SchemaGenerator().makeExecutableSchema(
                new SchemaParser().parse("type Query { greeting(name: String): String }"),
                newRuntimeWiring().type("Query", builder -> builder.dataFetcher("greeting",
                        environment -> "Hello " + environment.getArgument("name"))).build()));
    }

    private void serve(GraphQL graphQl) {
        server = Undertow.builder().addHttpListener(0, "127.0.0.1", new GraphqlHttpHandler(graphQl)).build();
        server.start();
        address = "http://127.0.0.1:" + ((InetSocketAddress) server.getListenerInfo().getFirst().getAddress()).getPort();
    }

    @AfterEach
    void stop() {
        client.close();
        server.stop();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder post(String contentType, String body) {
        return HttpRequest.newBuilder(URI.create(address))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    @Test
    void aQueryInAGraphQlBodyIsAnswered() throws Exception {
        HttpResponse<String> response = send(post("application/graphql", "{ greeting(name: \"Ann\") }"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(JsonTools.toJsonNode(response.body()).at("/data/greeting").stringValue()).isEqualTo("Hello Ann");
    }

    @Test
    void aJsonBodyGivesTheQueryItsVariablesAndTheOperationToRun() throws Exception {
        String body = """
                {
                  "query": "query One($n: String) { greeting(name: $n) } query Two { greeting(name: \\"Bob\\") }",
                  "variables": {"n": "Ann"},
                  "operationName": "One"
                }""";

        JsonNode answer = JsonTools.toJsonNode(send(post("application/json", body)).body());

        assertThat(answer.at("/data/greeting").stringValue()).isEqualTo("Hello Ann");
    }

    @Test
    void variablesThatAreNullAreLeftOut() throws Exception {
        String body = """
                {"query": "query One($n: String) { greeting(name: $n) }", "variables": null}""";

        JsonNode answer = JsonTools.toJsonNode(send(post("application/json", body)).body());

        assertThat(answer.at("/data/greeting").stringValue()).isEqualTo("Hello null");
    }

    /**
     * A query whose execution fails (not a field, which is an error in the answer) is answered 500, without a body.
     */
    @Test
    void aQueryWhoseExecutionFailsIsAnswered500() throws Exception {
        server.stop();
        serve(graphQl().instrumentation(new Instrumentation() {
            @Override
            public CompletableFuture<ExecutionResult> instrumentExecutionResult(
                    ExecutionResult result, InstrumentationExecutionParameters parameters, InstrumentationState state) {
                return CompletableFuture.failedFuture(new IllegalStateException("the execution fails"));
            }
        }).build());

        HttpResponse<String> response = send(post("application/graphql", "{ greeting(name: \"Ann\") }"));

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void aBodyOfAnotherContentTypeIsRefused() throws Exception {
        assertThat(send(post("text/plain", "{ greeting }")).statusCode()).isEqualTo(415);
    }

    @Test
    void anotherMethodIsRefused() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(address))
                .PUT(HttpRequest.BodyPublishers.ofString("{ greeting }")));

        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    void optionsNamesTheMethodsTheEndpointTakesWithGetTwice() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(address))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("Allow")).containsExactly("GET", "POST", "GET", "OPTIONS");
    }

    @Test
    void aQueryParameterGivenTwiceIsAnErrorOfTheCaller() throws Exception {
        String query = URLEncoder.encode("{ greeting }", StandardCharsets.UTF_8);

        HttpResponse<String> response = send(HttpRequest.newBuilder(
                URI.create(address + "/?query=" + query + "&query=" + query)));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).isEqualTo("{\"errors\":[{\"message\":\"more than one \\\"query\\\" parameter\"}]}");
    }

    @Test
    void aGetWithoutAQueryIsAnErrorOfTheCaller() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(address)));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).isEqualTo("{\"errors\":[{\"message\":\"The request has no \\\"query\\\" parameter\"}]}");
    }

    @Test
    void variablesThatAreNotAnObjectAreAnErrorOfTheCaller() throws Exception {
        HttpResponse<String> response = send(post("application/json", "{\"query\": \"{ greeting }\", \"variables\": [1]}"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).isEqualTo("{\"errors\":[{\"message\":\"The variables are not a JSON object\"}]}");
    }
}
