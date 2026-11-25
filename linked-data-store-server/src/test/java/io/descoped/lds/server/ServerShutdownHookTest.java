package io.descoped.lds.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.core.UndertowApplication;
import io.descoped.lds.core.lifecycle.Lifecycles;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The shutdown hook is there from before LDS is made: a process asked to end (SIGTERM) while LDS waits for an engine
 * at start stops what was made, in order, and ends; asked once LDS runs, it stops LDS in order.
 */
class ServerShutdownHookTest {

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private final Logger lifecycles = (Logger) LoggerFactory.getLogger(Lifecycles.class);
    private final Logger attempts = (Logger) LoggerFactory.getLogger("io.descoped.lds.core.utils.StartAttempts");
    private final Logger application = (Logger) LoggerFactory.getLogger(UndertowApplication.class);
    private Level lifecyclesLevel;

    @BeforeEach
    void listen() {
        lines.start();
        lifecyclesLevel = lifecycles.getLevel();
        lifecycles.setLevel(Level.DEBUG); // says each subsystem it has stopped
        for (Logger logger : new Logger[]{lifecycles, attempts, application}) {
            logger.addAppender(lines);
        }
    }

    @AfterEach
    void stopListening() {
        for (Logger logger : new Logger[]{lifecycles, attempts, application}) {
            logger.detachAppender(lines);
        }
        lifecycles.setLevel(lifecyclesLevel);
        Schedulers.start(); // the stop of the process shut them down
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private boolean logged(String text) {
        return lines.list.stream().map(ILoggingEvent::getFormattedMessage).anyMatch(line -> line.contains(text));
    }

    @Test
    void aStopAskedForWhileLdsWaitsForTheSagaLogStopsWhatWasMade() throws Exception {
        DynamicConfiguration configuration = DynamicConfiguration.builder()
                .classpath(UndertowApplication.DEFAULT_CONFIGURATION_RESOURCE_PATH)
                .classpath("application-server-defaults.properties")
                .values(Map.of(
                        "http.host", "127.0.0.1",
                        "http.port", String.valueOf(freePort()),
                        // the saga log in PostgreSQL, where nothing listens: waited for for a minute
                        "sagalog.provider", "no.cantara.sagalog.postgres.PostgresSagaLogInitializer",
                        "sagalog.config.postgres.driver.host", "127.0.0.1",
                        "sagalog.config.postgres.driver.port", String.valueOf(freePort()),
                        "sagalog.config.postgres.driver.password", "lds",
                        "sagalog.provider.initialization.max-wait-seconds", "60",
                        "sagalog.provider.initialization.retry-interval-seconds", "1"))
                .build();
        BlockingQueue<Thread> hooks = new LinkedBlockingQueue<>();
        Thread server = Thread.ofPlatform().name("lds-server-test").start(() -> Server.run(configuration, hooks::add));
        try {
            await("the start waiting for the saga log").atMost(Duration.ofSeconds(20))
                    .until(() -> logged("SagaLogPool provider could not be made, tried again"));

            Thread hook = hooks.poll(5, TimeUnit.SECONDS);
            assertThat(hook).as("the shutdown hook, while LDS starts").isNotNull();
            hook.start(); // as the JVM does when the process is asked to end
            hook.join(Duration.ofSeconds(20));

            assertThat(hook.isAlive()).isFalse();
            assertThat(server.isAlive()).as("the start ended").isFalse();
            assertThat(logged("LDS could not be made; what was made is stopped")).isTrue();
            assertThat(logged("Store stopped")).isTrue();
            assertThat(logged("RxJava schedulers stopped")).isTrue();
        } finally {
            server.interrupt();
            server.join(Duration.ofSeconds(70));
        }
    }

    /**
     * Asked to end once it runs, LDS stops in order, the HTTP server first and the RxJava schedulers last.
     */
    @Test
    void aStopAskedForOnceLdsRunsStopsItInOrder() throws Exception {
        Logger server = (Logger) LoggerFactory.getLogger(Server.class);
        server.addAppender(lines);
        DynamicConfiguration configuration = DynamicConfiguration.builder()
                .classpath(UndertowApplication.DEFAULT_CONFIGURATION_RESOURCE_PATH)
                .classpath("application-server-defaults.properties")
                .values(Map.of("http.host", "127.0.0.1", "http.port", String.valueOf(freePort())))
                .build();
        BlockingQueue<Thread> hooks = new LinkedBlockingQueue<>();
        Thread running = Thread.ofPlatform().name("lds-server-test").start(() -> Server.run(configuration, hooks::add));
        try {
            await("the start").atMost(Duration.ofSeconds(30)).until(() -> logged("Server started in"));
            Thread hook = hooks.poll(5, TimeUnit.SECONDS);
            assertThat(hook).isNotNull();
            hook.start();
            hook.join(Duration.ofSeconds(60));

            assertThat(hook.isAlive()).isFalse();
            assertThat(running.isAlive()).isFalse();
            assertThat(lines.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsSubsequence("HTTP server stopped", "Store stopped", "RxJava schedulers stopped");
        } finally {
            server.detachAppender(lines);
            running.interrupt();
            running.join(Duration.ofSeconds(60));
        }
    }
}
