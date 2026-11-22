package io.descoped.lds.test.server;

import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.core.UndertowApplication;

public class TestServer implements TestUriResolver {

    final DynamicConfiguration configuration;

    final UndertowApplication application;

    private final int testServerServicePort;

    public TestServer(DynamicConfiguration configuration, int testServerServicePort) {
        this.configuration = configuration;
        this.testServerServicePort = testServerServicePort;
        application = UndertowApplication.initializeUndertowApplication(configuration, testServerServicePort);
    }

    public int getTestServerServicePort() {
        return testServerServicePort;
    }

    @Override
    public String testURL(String uri) {
        return "http://" + application.getHost() + ":" + application.getPort() + uri;
    }

    public DynamicConfiguration getConfiguration() {
        return configuration;
    }

    public UndertowApplication getApplication() {
        return application;
    }
}
