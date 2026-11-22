package io.descoped.lds.core;

import io.descoped.config.DynamicConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model with no entity type stops the start at once, with a text that names the model, also when the persistence
 * provider is given time to come up.
 */
class ModelAtStartTest {

    private static DynamicConfiguration configuration(String graphqlSchema, String specificationSchema) {
        return DynamicConfiguration.builder()
                .classpath(UndertowApplication.DEFAULT_CONFIGURATION_RESOURCE_PATH)
                .classpath("application_test.properties")
                .values(Map.of(
                        "graphql.schema", graphqlSchema,
                        "specification.schema", specificationSchema,
                        "persistence.initialization.max-wait-seconds", "60"))
                .build();
    }

    private static void refusedAtOnce(DynamicConfiguration configuration, String message) {
        long start = System.nanoTime();
        assertThatThrownBy(() -> UndertowApplication.initializeUndertowApplication(configuration, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(message);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aGraphQLSchemaWithoutADomainTypeIsRefused() {
        refusedAtOnce(configuration("src/test/resources/spec/no-entity/schema.graphql", ""),
                "The model src/test/resources/spec/no-entity/schema.graphql has no entity type: a GraphQL schema needs a "
                        + "type with @domain");
    }

    @Test
    void noModelIsRefused() {
        refusedAtOnce(configuration("", ""), "No model is given: set graphql.schema or specification.schema");
    }

    @Test
    void aFolderOfJsonSchemasWithoutAnyIsRefused(@TempDir Path folder) {
        refusedAtOnce(configuration("", folder.toString()), "The model " + folder + " has no entity type");
    }
}
