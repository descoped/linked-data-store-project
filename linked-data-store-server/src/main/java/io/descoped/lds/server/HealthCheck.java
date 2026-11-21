package io.descoped.lds.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The health check of the image: asks {@code /ping} of the LDS in the same container and ends with 0 when it answers
 * 200, and with 1 otherwise. The image has no shell and no HTTP client, so the check is this class, run by the
 * {@code java} of the image. It uses nothing but {@code java.base} and starts no thread, so the JVM that runs it starts
 * and ends quickly.
 *
 * <p>The port is the one of the variable {@code LDS_HTTP_PORT}, 9090 without it. A port or a prefix that is set in a
 * mounted configuration file is one the check does not know.
 */
public final class HealthCheck {

    private static final int DEFAULT_PORT = 9090;
    private static final int TIMEOUT_MILLIS = 2000;

    private HealthCheck() {
    }

    /**
     * Runs the check.
     *
     * @param args not used
     */
    public static void main(String[] args) {
        System.exit(answersPing(port(System.getenv("LDS_HTTP_PORT"))) ? 0 : 1);
    }

    /**
     * The port to ask.
     *
     * @param value the value of {@code LDS_HTTP_PORT}, or null when it is not set
     * @return the port it names, or 9090 when it is not set or is not a number
     */
    static int port(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    /**
     * Whether LDS answers {@code /ping} on the port with 200.
     *
     * @param port the port of LDS on 127.0.0.1
     * @return true when the status line of the answer says 200
     */
    static boolean answersPing(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write("GET /ping HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return statusLine(socket.getInputStream()).matches("HTTP/1\\.[01] 200( .*)?");
        } catch (IOException e) {
            return false;
        }
    }

    private static String statusLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c = in.read(); c != -1 && c != '\n' && line.length() < 256; c = in.read()) {
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }
}
