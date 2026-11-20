package io.descoped.lds.server;

import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.core.UndertowApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;

public class DevServer {

    private static final Logger LOG = LoggerFactory.getLogger(DevServer.class);

    public static void main() {
        long now = System.currentTimeMillis();

        DynamicConfiguration configuration = DynamicConfiguration.builder()
                .classpath(UndertowApplication.getDefaultConfigurationResourcePath()).file(Path.of(UndertowApplication.getDefaultConfigurationResourcePath()))
                .values(Map.of("persistence.provider", "mem",
                        "persistence.fragment.capacity", "8192",
                        "specification.schema", "spec/demo/contact.json,spec/demo/provisionagreement.json"))
                .classpath("conf/application.properties").file(Path.of("conf/application.properties"))
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

            // wait for termination signal
            try {
                Thread.currentThread().join();
            } catch (InterruptedException _) {
                // the wait is over: the server is stopped below
            }
        } finally {
            application.stop();
        }
    }
}
