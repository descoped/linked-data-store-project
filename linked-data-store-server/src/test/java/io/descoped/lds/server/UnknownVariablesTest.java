package io.descoped.lds.server;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A variable that starts with LDS_ and sets no key of the configuration is reported, so that the start is refused.
 */
class UnknownVariablesTest {

    private static final Set<String> KEYS = Set.of("http.port", "namespace.default", "sagalog.config.cluster-name");

    @Test
    void aVariableThatSetsAKeyIsKnown() {
        assertThat(Server.unknownVariables(Map.of(
                "LDS_HTTP_PORT", "9090",
                "LDS_http_port", "9090",
                "LDS_namespace.default", "ns",
                "LDS_SAGALOG_CONFIG_CLUSTER_NAME", "c"), KEYS)).isEmpty();
    }

    @Test
    void aMisspelledVariableIsReported() {
        assertThat(Server.unknownVariables(Map.of(
                "LDS_NAMESPACE_DEFALT", "x",
                "LDS_HTTP_PROT", "1",
                "LDS_HTTP_PORT", "9090"), KEYS)).containsExactly("LDS_HTTP_PROT", "LDS_NAMESPACE_DEFALT");
    }

    @Test
    void aVariableWithoutThePrefixIsNotLookedAt() {
        assertThat(Server.unknownVariables(Map.of("PATH", "/bin", "LDSX", "1", "lds_http_port", "1"), KEYS)).isEmpty();
    }
}
