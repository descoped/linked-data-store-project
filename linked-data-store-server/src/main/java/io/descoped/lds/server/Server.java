package io.descoped.lds.server;

import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.core.UndertowApplication;
import io.descoped.lds.core.lifecycle.Lifecycle;
import io.descoped.lds.core.lifecycle.Lifecycles;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The LDS server: reads the configuration, starts the application and keeps it running until the process is
 * asked to end. The application is then stopped in order, so that what it is handling is completed.
 */
public class Server {

    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    static final String VARIABLE_PREFIX = "LDS_";

    private Server() {
    }

    /**
     * Starts LDS. The configuration is read from the defaults of core and of the server, then from
     * {@code conf/application.properties} and {@code conf_dev/application.properties} under the working
     * directory, then from the environment variables that start with {@code LDS_} and from the system
     * properties; a later source wins over an earlier one. It takes no arguments.
     * A variable that starts with {@code LDS_} and sets no key stops the start.
     */
    public static void main() {
        // Each name is looked for on the class path and then as a file under the working directory, and the
        // file wins. A later name wins over an earlier one; the environment and the system properties come last.
        DynamicConfiguration.Builder builder = DynamicConfiguration.builder();
        for (String name : List.of(
                UndertowApplication.DEFAULT_CONFIGURATION_RESOURCE_PATH,
                "application-server-defaults.properties",
                "conf/application.properties",
                "conf_dev/application.properties")) {
            builder.classpath(name).file(Path.of(name));
        }
        DynamicConfiguration configuration = builder
                // a variable that names no key, a misspelled one, stops the start: it would be a setting that is not
                .strictEnvironment(VARIABLE_PREFIX)
                .systemProperties()
                .build();

        run(configuration, Runtime.getRuntime()::addShutdownHook);
    }

    /**
     * Makes and starts LDS on this thread, and keeps it running until it is asked to end, then stops it in order.
     * The shutdown hook is given before LDS is made: when the process is asked to end, also while LDS waits for an
     * engine at start, the hook interrupts this thread, which ends the wait, stops what was made, and returns; the
     * hook waits for that.
     *
     * @param configuration the configuration
     * @param shutdownHooks takes the shutdown hook, which asks LDS to end
     */
    static void run(DynamicConfiguration configuration, Consumer<Thread> shutdownHooks) {
        long now = System.currentTimeMillis();
        Thread running = Thread.currentThread();
        AtomicBoolean asked = new AtomicBoolean();
        shutdownHooks.accept(new Thread(() -> {
            LOG.warn("ShutdownHook triggered..");
            asked.set(true); // before the interrupt, which may be lost in a part that does not keep it
            running.interrupt();
            try {
                running.join();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }, "lds-shutdown"));

        // the process: RxJava's shared schedulers, which belong to it, then LDS, which uses them
        Lifecycles process = new Lifecycles();
        process.add(Lifecycle.of("RxJava schedulers", _ -> Schedulers.shutdown()));
        try {
            UndertowApplication application = UndertowApplication.initializeUndertowApplication(configuration);
            process.add(application);
            if (asked.get()) {
                return; // asked to end while it was made
            }

            process.start(UndertowApplication.STOP_TIMEOUT);

            long time = System.currentTimeMillis() - now;
            LOG.info("Server started in {}ms..", time);

            application.enableSagaExecutionAutomaticDeadlockDetectionAndResolution();

            // wait for termination signal
            while (!asked.get()) {
                Thread.currentThread().join();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt(); // asked to end: kept for the caller, put aside for the stop below
        } catch (RuntimeException e) {
            if (!asked.get()) {
                throw e;
            }
            LOG.info("LDS was asked to end while it started: {}", String.valueOf(e));
        } finally {
            // the interrupt asked for the stop: the stop waits for what runs, as it is told, and the thread is
            // interrupted again when it is done
            boolean interrupted = Thread.interrupted();
            try {
                process.stop(UndertowApplication.STOP_TIMEOUT);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
