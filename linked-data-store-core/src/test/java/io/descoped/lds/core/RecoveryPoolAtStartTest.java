package io.descoped.lds.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.core.lifecycle.Lifecycles;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The thread pool that recovers sagas is a subsystem from when it is made: a start that fails after it, here at
 * the recovery trigger, whose intervals are wrong, stops it with the rest of what was made.
 */
class RecoveryPoolAtStartTest {

    private static DynamicConfiguration configuration(Map<String, String> values) {
        return DynamicConfiguration.builder()
                .classpath(UndertowApplication.DEFAULT_CONFIGURATION_RESOURCE_PATH)
                .classpath("application_test.properties")
                .values(values)
                .build();
    }

    @Test
    void theRecoveryPoolIsASubsystemMadeBeforeTheSagas() {
        UndertowApplication application = UndertowApplication.initializeUndertowApplication(
                configuration(Map.of()), 0);
        try {
            assertThat(application.getLifecycles().names())
                    .containsSubsequence("Saga thread pool", "Saga recovery thread pool", "Sagas");
        } finally {
            application.stop();
        }
    }

    @Test
    void aStartThatFailsAtTheRecoveryTriggerStopsTheRecoveryPool() {
        Logger logger = (Logger) LoggerFactory.getLogger(Lifecycles.class);
        Level level = logger.getLevel();
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        logger.setLevel(Level.DEBUG);
        try {
            assertThatThrownBy(() -> UndertowApplication.initializeUndertowApplication(configuration(Map.of(
                    "saga.recovery.enabled", "true",
                    "saga.recovery.interval.seconds.min", "60",
                    "saga.recovery.interval.seconds.max", "30")), 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("intervalMaxSec < intervalMinSec");
        } finally {
            logger.detachAppender(lines);
            logger.setLevel(level);
        }
        assertThat(lines.list).extracting(ILoggingEvent::getFormattedMessage)
                .contains("Saga recovery thread pool stopped", "Saga thread pool stopped", "Store stopped");
    }
}
