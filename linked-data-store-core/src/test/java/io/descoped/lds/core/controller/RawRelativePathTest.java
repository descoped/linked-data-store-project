package io.descoped.lds.core.controller;

import io.undertow.server.HttpServerExchange;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relative path as it was sent, from the request URI Undertow keeps and the relative path it decoded.
 */
class RawRelativePathTest {

    private static String raw(String requestUri, boolean hostIncluded, String relativePath) {
        HttpServerExchange exchange = new HttpServerExchange(null);
        exchange.setRequestURI(requestUri, hostIncluded);
        exchange.setRelativePath(relativePath);
        return DataController.rawRelativePath(exchange);
    }

    @Test
    void theRelativePathIsTakenAsItWasSent() {
        assertThat(raw("/ns/contact/a%2Bb", false, "/ns/contact/a+b")).isEqualTo("/ns/contact/a%2Bb");
        assertThat(raw("/ns/contact/100%25", false, "/ns/contact/100%")).isEqualTo("/ns/contact/100%25");
    }

    @Test
    void aPrefixBeforeTheRelativePathIsLeftOut() {
        assertThat(raw("/lds/ns/contact/a%20b", false, "/ns/contact/a b")).isEqualTo("/ns/contact/a%20b");
        assertThat(raw("/a/b/c/ns/contact/x", false, "/ns/contact/x")).isEqualTo("/ns/contact/x");
        assertThat(raw("/lds/", false, "/")).isEqualTo("/");
    }

    @Test
    void pathParametersAreLeftOut() {
        assertThat(raw("/ns;x=1/contact/%C3%B8l;v=1", false, "/ns/contact/øl")).isEqualTo("/ns/contact/%C3%B8l");
    }

    @Test
    void aRequestLineInAbsoluteFormLeavesOutTheSchemeAndTheHost() {
        assertThat(raw("http://localhost:9090/ns/contact/a%2Bb", true, "/ns/contact/a+b"))
                .isEqualTo("/ns/contact/a%2Bb");
        assertThat(raw("http://localhost:9090", true, "/")).isEqualTo("/");
    }

    @Test
    void aPathThatHoldsASchemeIsNotTakenForAnAbsoluteForm() {
        assertThat(raw("/ns/contact/http:", false, "/ns/contact/http:")).isEqualTo("/ns/contact/http:");
        assertThat(raw("/ns/x://y/a%2Bb", false, "/ns/x://y/a+b")).isEqualTo("/ns/x://y/a%2Bb");
    }
}
