package io.descoped.lds.core.controller;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CORS rules with the default policy: a request from an allowed origin goes on with the CORS headers, a
 * preflight is answered by the handler itself, and anything the rules do not allow goes to the failure handler
 * without them.
 */
class CORSHandlerRulesTest {

    private static final String ALLOWED = "https://allowed.example";

    private Undertow server;
    private HttpClient client;
    private String address;

    @BeforeEach
    void start() {
        HttpHandler next = exchange -> exchange.setStatusCode(200).getResponseSender().send("next");
        HttpHandler fail = exchange -> exchange.setStatusCode(403).getResponseSender().send("fail");
        server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(new CORSHandler(next, fail, List.of(Pattern.compile(Pattern.quote(ALLOWED))), true))
                .build();
        server.start();
        address = "http://127.0.0.1:" + ((InetSocketAddress) server.getListenerInfo().getFirst().getAddress()).getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        client.close();
        server.stop();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder get() {
        return HttpRequest.newBuilder(URI.create(address + "/ns/E/1"));
    }

    private HttpRequest.Builder preflight(String origin) {
        return get().method("OPTIONS", HttpRequest.BodyPublishers.noBody()).header("Origin", origin);
    }

    @Test
    void aRequestFromAnAllowedOriginGoesOnWithTheCorsHeaders() throws Exception {
        HttpResponse<String> response = send(get().header("Origin", ALLOWED));

        assertThat(response.body()).isEqualTo("next");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ALLOWED);
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).hasValue("true");
        assertThat(response.headers().firstValue("Vary")).hasValue("Origin");
        assertThat(response.headers().firstValue("Access-Control-Expose-Headers")).isPresent();
    }

    @Test
    void aRequestWithoutAnOriginOrFromAnotherOneGoesToTheFailureHandler() throws Exception {
        assertThat(send(get()).body()).isEqualTo("fail");

        HttpResponse<String> other = send(get().header("Origin", "https://other.example"));
        assertThat(other.body()).isEqualTo("fail");
        assertThat(other.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void aRequestWithTwoOriginsGoesToTheFailureHandler() throws Exception {
        assertThat(send(get().header("Origin", ALLOWED).header("Origin", ALLOWED)).body()).isEqualTo("fail");
    }

    @Test
    void aPreflightForAnAllowedMethodAndHeadersIsAnsweredWithoutGoingOn() throws Exception {
        HttpResponse<String> response = send(preflight(ALLOWED)
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type, Authorization"));

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ALLOWED);
        assertThat(response.headers().firstValue("Access-Control-Max-Age")).hasValue("864000");
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow().split(","))
                .containsExactlyInAnyOrder("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT");
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElseThrow().split(","))
                .containsExactlyInAnyOrder("Authorization", "Content-Type", "Range");
    }

    @Test
    void aPreflightWithoutAMethodGoesToTheFailureHandler() throws Exception {
        assertThat(send(preflight(ALLOWED)).body()).isEqualTo("fail");
    }

    @Test
    void aPreflightForAMethodThatIsNotAllowedGoesToTheFailureHandler() throws Exception {
        assertThat(send(preflight(ALLOWED).header("Access-Control-Request-Method", "TRACE")).body())
                .isEqualTo("fail");
    }

    @Test
    void aPreflightForAHeaderThatIsNotAllowedGoesToTheFailureHandler() throws Exception {
        HttpResponse<String> response = send(preflight(ALLOWED)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Content-Type, X-Unknown"));

        assertThat(response.body()).isEqualTo("fail");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void theShortConstructorSendsARequestThatIsNotAllowedOnAsItIs() throws Exception {
        stop();
        HttpHandler next = exchange -> exchange.getResponseSender().send("next");
        server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(new CORSHandler(next, List.of(Pattern.compile(Pattern.quote(ALLOWED)))))
                .build();
        server.start();
        address = "http://127.0.0.1:" + ((InetSocketAddress) server.getListenerInfo().getFirst().getAddress()).getPort();
        client = HttpClient.newHttpClient();

        HttpResponse<String> response = send(get().header("Origin", "https://other.example"));

        assertThat(response.body()).isEqualTo("next");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(send(get().header("Origin", ALLOWED)).headers().firstValue("Access-Control-Allow-Credentials"))
                .isEmpty();
    }
}
