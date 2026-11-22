package io.descoped.lds.core.search.solr;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.util.Map;

/**
 * One Solr 10 container for every test class of the module, started on first use and stopped by Testcontainers
 * when the JVM ends. It is started as the compose file of the server starts Solr: the image as it is, with one
 * core, {@code lds-index}, created from the configuration the image comes with. The tests need Docker.
 */
final class Solr10 {

    private static final String IMAGE = "solr:10.1";
    private static final String CORE = "lds-index";
    private static final int PORT = 8983;

    private static final GenericContainer<?> CONTAINER = start();

    private Solr10() {
    }

    @SuppressWarnings("resource") // the container is returned, and Testcontainers stops it when the JVM ends
    private static GenericContainer<?> start() {
        GenericContainer<?> container = new GenericContainer<>(IMAGE)
                .withCommand("solr-precreate", CORE)
                .withExposedPorts(PORT)
                .waitingFor(Wait.forHttp("/solr/" + CORE + "/admin/ping").forStatusCode(200));
        container.start();
        LoopbackPorts.check(container);
        return container;
    }

    /**
     * The address of the core.
     */
    static String url() {
        return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(PORT) + "/solr/" + CORE;
    }

    /**
     * The provider configuration that reaches the container.
     */
    static Map<String, String> configuration() {
        return Map.of("search.index.url", url());
    }
}
