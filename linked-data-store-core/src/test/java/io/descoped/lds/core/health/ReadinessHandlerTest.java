package io.descoped.lds.core.health;

import io.descoped.lds.api.persistence.json.JsonTools;
import io.reactivex.rxjava3.core.Single;
import io.undertow.Undertow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /health/ready} over HTTP: 200 when LDS is up or degraded, 503 when it is down, and a JSON body with an entry
 * for each check.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ReadinessHandlerTest {

    private static HttpResponse<String> ask(Readiness readiness) throws Exception {
        Undertow server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(new ReadinessHandler(readiness))
                .build();
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            int port = ((InetSocketAddress) server.getListenerInfo().getFirst().getAddress()).getPort();
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ReadinessHandler.PATH)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } finally {
            server.stop();
            readiness.close();
        }
    }

    @Test
    void upIsOkWithAnEntryForEachCheck() throws Exception {
        HttpResponse<String> response = ask(new Readiness()
                .check(new ReadinessCheck("persistence", Single.just(ReadinessCheck.Result.up()), Health.DOWN))
                .check(new ReadinessCheck("sagaLog", Single.just(new ReadinessCheck.Result(Health.UP, Map.of("logs", 2))),
                        Health.DOWN)));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json; charset=utf-8");
        JsonNode body = JsonTools.mapper.readTree(response.body());
        assertThat(body.get("status").asString()).isEqualTo("UP");
        assertThat(body.get("checks").propertyNames()).containsExactly("persistence", "sagaLog");
        assertThat(body.get("checks").get("persistence").get("status").asString()).isEqualTo("UP");
        assertThat(body.get("checks").get("persistence").get("millis").isNumber()).isTrue();
        assertThat(body.get("checks").get("sagaLog").get("logs").asInt()).isEqualTo(2);
    }

    @Test
    void degradedIsOkAndSaysWhatFailed() throws Exception {
        HttpResponse<String> response = ask(new Readiness()
                .check(new ReadinessCheck("persistence", Single.just(ReadinessCheck.Result.up()), Health.DOWN))
                .check(new ReadinessCheck("search", Single.error(new IllegalStateException("Connection refused")),
                        Health.DEGRADED)));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JsonTools.mapper.readTree(response.body());
        assertThat(body.get("status").asString()).isEqualTo("DEGRADED");
        assertThat(body.get("checks").get("search").get("status").asString()).isEqualTo("DEGRADED");
        assertThat(body.get("checks").get("search").get("error").asString()).isEqualTo("IllegalStateException");
    }

    @Test
    void downIsServiceUnavailable() throws Exception {
        HttpResponse<String> response = ask(new Readiness(Duration.ofMillis(200))
                .check(new ReadinessCheck("persistence", Single.never(), Health.DOWN)));

        assertThat(response.statusCode()).isEqualTo(503);
        JsonNode body = JsonTools.mapper.readTree(response.body());
        assertThat(body.get("status").asString()).isEqualTo("DOWN");
        assertThat(body.get("checks").get("persistence").get("error").asString()).isEqualTo("no answer within 200 ms");
    }
}
