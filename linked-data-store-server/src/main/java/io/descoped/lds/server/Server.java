package io.descoped.lds.server;

import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.core.UndertowApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

public class Server {

    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    public static void main(String[] args) {
        long now = System.currentTimeMillis();

        // Each name is looked for on the class path and then as a file under the working directory, and the
        // file wins. A later name wins over an earlier one; the environment and the system properties come last.
        DynamicConfiguration.Builder builder = DynamicConfiguration.builder();
        for (String name : List.of(
                UndertowApplication.getDefaultConfigurationResourcePath(),
                "application-server-defaults.properties",
                "conf/application.properties",
                "conf_dev/application.properties")) {
            builder.classpath(name).file(Path.of(name));
        }
        DynamicConfiguration configuration = builder
                .environment("LDS_")
                .systemProperties()
                .build();

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

            // application.triggerRecoveryOfIncompleteSagas();

            // wait for termination signal
            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            application.stop();
        }
    }
}
