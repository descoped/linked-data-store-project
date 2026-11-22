package io.descoped.lds.server;

import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.core.UndertowApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
     * The variables that start with {@code LDS_} and set no key. A variable sets a key when its name is the prefix
     * followed by the key as it is, by the key with each character that is not a letter or a digit replaced by
     * {@code _}, or by that in upper case: the names dynamic-configuration 3 looks for
     * ({@code io.descoped.config.internal.EnvironmentNames}, the MicroProfile Config mapping).
     *
     * @param environment the environment variables
     * @param keys        the keys of the configuration
     * @return the names of the variables that set no key, sorted
     */
    static List<String> unknownVariables(Map<String, String> environment, Set<String> keys) {
        Set<String> known = new HashSet<>();
        for (String key : keys) {
            String sanitized = key.replaceAll("[^A-Za-z0-9]", "_");
            known.add(VARIABLE_PREFIX + key);
            known.add(VARIABLE_PREFIX + sanitized);
            known.add(VARIABLE_PREFIX + sanitized.toUpperCase(Locale.ROOT));
        }
        return environment.keySet().stream()
                .filter(name -> name.startsWith(VARIABLE_PREFIX) && !known.contains(name))
                .sorted()
                .toList();
    }

    /**
     * Starts LDS. The configuration is read from the defaults of core and of the server, then from
     * {@code conf/application.properties} and {@code conf_dev/application.properties} under the working
     * directory, then from the environment variables that start with {@code LDS_} and from the system
     * properties; a later source wins over an earlier one. It takes no arguments.
     * A variable that starts with {@code LDS_} and sets no key stops the start.
     */
    public static void main() {
        long now = System.currentTimeMillis();

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
                .environment(VARIABLE_PREFIX)
                .systemProperties()
                .build();

        // The configuration ignores a variable that names no key; a misspelled one would be a setting that is not.
        List<String> unknown = unknownVariables(System.getenv(), configuration.asMap().keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("No configuration key for the environment variables " + unknown
                    + ". A variable " + VARIABLE_PREFIX + "<KEY> sets a key that a file has, with each character of the key"
                    + " that is not a letter or a digit written as _ (LDS_HTTP_PORT sets http.port)");
        }

        UndertowApplication application = UndertowApplication.initializeUndertowApplication(configuration);

        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.warn("ShutdownHook triggered..");
                application.stop();
            }));

            application.start();

            long time = System.currentTimeMillis() - now;
            LOG.info("Server started in {}ms..", time);

            application.enableSagaExecutionAutomaticDeadlockDetectionAndResolution();

            // wait for termination signal
            try {
                Thread.currentThread().join();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        } finally {
            application.stop();
        }
    }
}
