package io.descoped.lds.core;

import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.core.persistence.PersistenceConfigurator;
import io.descoped.lds.core.specification.JsonSchemaBasedSpecification;
import io.descoped.rawdata.api.RawdataClient;
import io.descoped.service.provider.api.ProviderException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * A value of the configuration that can never work ends the start at once, though the part may wait half a minute
 * for its engine; an engine that is not up is waited for until the wait is over.
 */
class ConfigurationFaultsAtStartTest {

    /* Far less than the wait each part is given here (30 s): a fault that is tried again does not end within it. */
    private static final Duration AT_ONCE = Duration.ofSeconds(10);

    private static DynamicConfiguration configuration(Map<String, String> values) {
        Map<String, String> all = new HashMap<>(Map.of(
                "sagalog.provider.initialization.max-wait-seconds", "30",
                "sagalog.provider.initialization.retry-interval-seconds", "1",
                "txlog.rawdata.provider.initialization.max-wait-seconds", "30",
                "txlog.rawdata.provider.initialization.retry-interval-seconds", "1",
                "persistence.initialization.max-wait-seconds", "30",
                "saga.number-of-logs", "1"));
        all.putAll(values);
        return DynamicConfiguration.builder().values(all).build();
    }

    @Test
    void anUnknownTransactionLogProviderEndsTheStartAtOnce() {
        DynamicConfiguration configuration = configuration(Map.of("txlog.rawdata.provider", "nope"));

        assertTimeoutPreemptively(AT_ONCE, () -> assertThatThrownBy(
                () -> UndertowApplication.configureTxLogRawdataClient(configuration))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("nope"));
    }

    @Test
    void theTransactionLogInPostgresWithoutAPasswordEndsTheStartAtOnce() {
        DynamicConfiguration configuration = configuration(Map.of(
                "txlog.rawdata.provider", "postgres",
                "txlog.config.postgres.driver.user", "lds",
                "txlog.config.postgres.driver.password", "",
                "txlog.config.postgres.driver.database", "txlog"));

        assertTimeoutPreemptively(AT_ONCE, () -> assertThatThrownBy(
                () -> UndertowApplication.configureTxLogRawdataClient(configuration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postgres.driver.password"));
    }

    @Test
    void aSagaLogClusterNameThatIsNoNameEndsTheStartAtOnce() {
        DynamicConfiguration configuration = configuration(Map.of(
                "sagalog.provider", "no.cantara.sagalog.postgres.PostgresSagaLogInitializer",
                "sagalog.config.cluster.owner", "lds",
                "sagalog.config.cluster.name", "my_cluster",
                "sagalog.config.cluster.instance-id", "01",
                "sagalog.config.connection-pool.max-size", "50"));

        assertTimeoutPreemptively(AT_ONCE, () -> assertThatThrownBy(
                () -> UndertowApplication.configureSagaLogProvider(configuration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cluster.name"));
    }

    @Test
    void aStoreValueThatIsNoNumberEndsTheStartAtOnce() {
        DynamicConfiguration configuration = configuration(Map.of(
                "persistence.provider", "mem",
                "persistence.fragment.capacity", "abc"));
        Specification specification = JsonSchemaBasedSpecification.create("spec/schemas/contact.json");

        assertTimeoutPreemptively(AT_ONCE, () -> assertThatThrownBy(
                () -> PersistenceConfigurator.configurePersistence(configuration, specification))
                .isInstanceOf(IllegalArgumentException.class));
    }

    /**
     * PostgreSQL that does not answer (nothing listens on the port) is tried again until the wait is over.
     */
    @Test
    void anEngineThatIsNotUpIsWaitedFor() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        DynamicConfiguration configuration = configuration(Map.of(
                "txlog.rawdata.provider", "postgres",
                "txlog.rawdata.provider.initialization.max-wait-seconds", "2",
                "txlog.config.postgres.driver.host", "127.0.0.1",
                "txlog.config.postgres.driver.port", String.valueOf(closedPort),
                "txlog.config.postgres.driver.user", "lds",
                "txlog.config.postgres.driver.password", "lds",
                "txlog.config.postgres.driver.database", "txlog"));

        long start = System.nanoTime();
        assertThatThrownBy(() -> {
            try (RawdataClient _ = UndertowApplication.configureTxLogRawdataClient(configuration)) {
                // not made: nothing listens
            }
        }).isNotInstanceOf(IllegalArgumentException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
    }
}
