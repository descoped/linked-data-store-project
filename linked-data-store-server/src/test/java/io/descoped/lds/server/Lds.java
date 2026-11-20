package io.descoped.lds.server;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * A running LDS, as a test of the image talks to it: over HTTP on 127.0.0.1.
 */
final class Lds {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final String address;

    /**
     * @param port the port on 127.0.0.1 that LDS is published on
     */
    Lds(int port) {
        this.address = "http://127.0.0.1:" + port;
    }

    HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(address + path)).GET());
    }

    /**
     * Writes a document and waits until LDS has stored it.
     */
    HttpResponse<String> put(String path, String json) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(address + path + "?sync=true"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)));
    }

    /**
     * @param query a GraphQL query on one line
     * @return the answer of LDS, as the JSON it is
     */
    String graphql(String query) throws IOException, InterruptedException {
        String text = query.replace("\\", "\\\\").replace("\"", "\\\"");
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(address + "/graphql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"" + text + "\"}")));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GraphQL answered " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return CLIENT.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
