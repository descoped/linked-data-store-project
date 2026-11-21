package io.descoped.lds.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The health check of the image: the port it asks, and that only a 200 on {@code /ping} is healthy.
 */
class HealthCheckTest {

    private ServerSocket server;

    @AfterEach
    void stop() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    /**
     * Answers one request: with the status when it asks the path, and with 404 otherwise.
     */
    private int serve(String path, int status) throws IOException {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try (Socket socket = server.accept()) {
                BufferedReader request = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                int answer = request.readLine().startsWith("GET " + path + " ") ? status : 404;
                String response = "HTTP/1.1 " + answer + " X\r\nContent-Length: 0\r\n\r\n";
                socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                // the server is closed
            }
        });
        return server.getLocalPort();
    }

    @Test
    void thePortIsTheOneOfTheVariableOr9090() {
        assertThat(HealthCheck.port("19090")).isEqualTo(19090);
        assertThat(HealthCheck.port(" 8080 ")).isEqualTo(8080);
        assertThat(HealthCheck.port(null)).isEqualTo(9090);
        assertThat(HealthCheck.port("")).isEqualTo(9090);
        assertThat(HealthCheck.port("http")).isEqualTo(9090);
    }

    @Test
    void aPingAnswered200IsHealthy() throws IOException {
        assertThat(HealthCheck.answersPing(serve("/ping", 200))).isTrue();
    }

    @Test
    void anotherStatusIsNotHealthy() throws IOException {
        assertThat(HealthCheck.answersPing(serve("/ping", 503))).isFalse();
    }

    @Test
    void aPingThatIsNotThereIsNotHealthy() throws IOException {
        assertThat(HealthCheck.answersPing(serve("/other", 200))).isFalse();
    }

    @Test
    void aPortWhereNothingListensIsNotHealthy() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        assertThat(HealthCheck.answersPing(port)).isFalse();
    }
}
