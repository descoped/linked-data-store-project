package io.descoped.lds.graphql;

import com.damnhandy.uri.template.UriTemplate;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.StaticDataFetcher;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.undertow.Undertow;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static graphql.schema.idl.RuntimeWiring.newRuntimeWiring;
import static io.descoped.lds.api.persistence.json.JsonTools.mapper;
import static org.testng.Assert.assertEquals;

public class GraphqlHttpHandlerTest {

    private GraphQL graphql;
    private Undertow server;
    private HttpClient client;
    private UriTemplate uriTemplate;

    @BeforeMethod
    public void setUp() {

        // Test client.
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL).proxy(ProxySelector.getDefault()).build();

        // Build a fake GraphQL instance.
        String schema = "" +
                "type User {" +
                "   name: String," +
                "   address: Address" +
                "}" +
                "type Address {" +
                "   street: String " +
                "}" +
                "type Query{" +
                "   me: User" +
                "   echo(text: String): String" +
                "}";

        SchemaParser schemaParser = new SchemaParser();
        TypeDefinitionRegistry typeDefinitionRegistry = schemaParser.parse(schema);

        RuntimeWiring runtimeWiring = newRuntimeWiring()
                .type("Query", builder -> builder.dataFetcher("me", new StaticDataFetcher(
                        Map.of("name", "Hadrien", "address", Map.of("street", "rue de la paix"))
                )).dataFetcher("echo", environment -> environment.getArgument("text")))
                .build();

        SchemaGenerator schemaGenerator = new SchemaGenerator();
        GraphQLSchema graphQLSchema = schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);

        graphql = GraphQL.newGraphQL(graphQLSchema).build();

        // Test server, on a port the system chooses when it binds.
        server = Undertow.builder().addHttpListener(0, "localhost", new GraphqlHttpHandler(graphql)).build();
        server.start();
        int port = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();

        // Template for the client.
        uriTemplate = UriTemplate.buildFromTemplate("http://localhost:" + port)
                .query("query", "operationName", "variables").build();
    }

    @AfterMethod
    public void tearDown() {
        server.stop();
    }

    @Test
    public void testPostWithoutCharsetIsReadAsUtf8() throws IOException, InterruptedException {
        for (String contentType : new String[]{"application/json", "application/graphql"}) {
            String query = "{ echo(text: \"øl ☕\") }";
            String body = contentType.equals("application/json") ? "{\"query\": \"{ echo(text: \\\"øl ☕\\\") }\"}" : query;
            HttpRequest request = HttpRequest.newBuilder(URI.create(uriTemplate.expand()))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(response.statusCode(), 200, contentType);
            assertEquals(response.body(), "{\"data\":{\"echo\":\"øl ☕\"}}", contentType);
        }
    }

    @Test
    public void testASnapshotThatIsNotATimeIsAnError() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(uriTemplate.set("query", "{me{name}}").expand() + "&snapshot=yesterday")).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(response.statusCode(), 200);
        assertEquals(response.body(), "{\"errors\":[{\"message\":\"The query parameter snapshot 'yesterday' is not an "
                + "ISO-8601 time. Example of a valid formatted time is '2018-12-06T11:05:31.000+01:00'\","
                + "\"locations\":[],\"extensions\":{\"classification\":\"ValidationError\"}}]}");
    }

    @Test
    public void testGetQueryParameter() throws Exception {

        // When receiving an HTTP GET request, the GraphQL query should be
        // specified in the "query" query string.
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(uriTemplate.set("query", "{me{name}}").expand())).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(response.statusCode(), 200);
        assertEquals(response.body(), "{\"data\":{\"me\":{\"name\":\"Hadrien\"}}}");

    }

    @Test
    public void testGetVariablesParameter() throws IOException, InterruptedException {
        // Query variables can be sent as a JSON-encoded string in an additional
        // query parameter called variables

        HttpRequest request = HttpRequest.newBuilder(
                URI.create(uriTemplate
                        .set("query", "" +
                                "query Test($foo: Boolean!){" +
                                "   me {" +
                                "       name, " +
                                "       address @skip(if: $foo) {" +
                                "           street" +
                                "       }" +
                                "   }" +
                                "}")
                        .set("variables", "{\"foo\":true}")
                        .expand())
        ).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(response.statusCode(), 200);
        assertEquals(response.body(), "{\"data\":{\"me\":{\"name\":\"Hadrien\"}}}");
    }

    @Test
    public void testGetOperationNameParameter() throws IOException, InterruptedException {

        // If the query contains several named operations, an operationName query
        // parameter can be used to control which one should be executed.

        HttpRequest request = HttpRequest.newBuilder(
                URI.create(uriTemplate
                        .set("query", "" +
                                "query Foo {" +
                                "   me {" +
                                "       name" +
                                "   }" +
                                "}" +
                                "query Bar {" +
                                "   me {" +
                                "       address {" +
                                "           street" +
                                "       }" +
                                "   }" +
                                "}"
                        )
                        .set("operationName", "Bar")
                        .expand())
        ).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(response.statusCode(), 200);
        assertEquals(response.body(), "{\"data\":{\"me\":{\"address\":{\"street\":\"rue de la paix\"}}}}");

    }

    @Test
    public void testPostValidateContentType() throws IOException, InterruptedException {

        // A standard GraphQL POST request should use the application/json content
        // type, and include a JSON-encoded body: an object with the fields query,
        // operationName and variables, the last an object that maps the name of each
        // variable to its value.
        //
        // operationName and variables are optional fields. operationName is only required
        // if multiple operations are present in the query.

        ObjectNode root = mapper.createObjectNode();
        root.put("query", "query Foo {" +
                "   me {" +
                "       name" +
                "   }" +
                "}" +
                "query Bar($foo: Boolean!) {" +
                "   me {" +
                "       name" +
                "       address @skip(if: $foo) {" +
                "           street" +
                "       }" +
                "   }" +
                "}");
        root.put("operationName", "Bar");
        ObjectNode variables = root.putObject("variables");
        variables.put("foo", true);

        HttpRequest request = HttpRequest.newBuilder(URI.create(uriTemplate.expand()))
                .POST(HttpRequest.BodyPublishers.ofString(JsonTools.toJson(root)))
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(response.statusCode(), 200);
        assertEquals(response.body(), "{\"data\":{\"me\":{\"name\":\"Hadrien\"}}}");

    }

    /**
     * Jackson 2 read the first document of a body and ignored what came after it.
     */
    @Test
    public void testPostBodyWithSomethingAfterTheDocumentIsRefused() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uriTemplate.expand()))
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{me{name}}\"} and more"))
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // answered as every body that cannot be read is: an error, and no data
        assertEquals(response.statusCode(), 500);
        assertEquals(response.body(), "");
    }

    @Test
    public void testGetVariablesWithSomethingAfterTheDocumentAreRefused() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uriTemplate
                .set("query", "{me{name}}")
                .set("variables", "{\"foo\":true} and more")
                .expand())).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // answered as every body that cannot be read is: an error, and no data
        assertEquals(response.statusCode(), 500);
        assertEquals(response.body(), "");
    }
}
