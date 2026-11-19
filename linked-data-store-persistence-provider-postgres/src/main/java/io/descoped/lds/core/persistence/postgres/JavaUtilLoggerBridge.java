package io.descoped.lds.core.persistence.postgres;

import org.slf4j.bridge.SLF4JBridgeHandler;

import java.util.logging.Level;
import java.util.logging.LogManager;

/**
 * Sends what the JDBC driver logs with {@code java.util.logging} to SLF4J.
 */
final class JavaUtilLoggerBridge {

    private JavaUtilLoggerBridge() {
    }

    private static final class Initializer {
        static {
            LogManager.getLogManager().reset();
            SLF4JBridgeHandler.removeHandlersForRootLogger();
            SLF4JBridgeHandler.install();
        }

        private static void configure() {
            // method exists to allow access to static initializer
        }
    }

    static void installJavaUtilLoggerBridgeHandler(Level level) {
        Initializer.configure();
        LogManager.getLogManager().getLogger("").setLevel(level);
    }
}
