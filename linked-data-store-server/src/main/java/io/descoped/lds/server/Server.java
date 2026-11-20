package io.descoped.lds.server;

import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.core.UndertowApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;

public class Server {

    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    public static void main(String[] args) {
        long now = System.currentTimeMillis();

        DynamicConfiguration configuration = DynamicConfiguration.builder()
                .classpath(UndertowApplication.getDefaultConfigurationResourcePath()).file(Path.of(UndertowApplication.getDefaultConfigurationResourcePath()))
                .classpath("application-server-defaults.properties").file(Path.of("application-server-defaults.properties"))
                .classpath("conf/application.properties").file(Path.of("conf/application.properties"))
                .classpath("conf_dev/application.properties").file(Path.of("conf_dev/application.properties"))
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
