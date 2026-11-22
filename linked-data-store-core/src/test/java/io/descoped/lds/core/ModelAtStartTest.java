package io.descoped.lds.core;

import io.descoped.config.DynamicConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

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

    @Test
    void aNamespaceOrAnEntityTypeWithinTheLimitsIsTaken() {
        UndertowApplication.checkNames("n".repeat(64), Set.of("E".repeat(128), "Person"));
        UndertowApplication.checkNames("日".repeat(21), Set.of("Ø".repeat(64)));
    }

    @Test
    void aNamespaceOverItsLimitStopsTheStart() {
        assertThatThrownBy(() -> UndertowApplication.checkNames("n".repeat(65), Set.of("Person")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The namespace is at most 64 bytes in UTF-8: " + "n".repeat(65) + "...");
        refusedAtOnce(DynamicConfiguration.builder()
                        .classpath(UndertowApplication.DEFAULT_CONFIGURATION_RESOURCE_PATH)
                        .classpath("application_test.properties")
                        .values(Map.of("namespace.default", "日".repeat(22),
                                "persistence.initialization.max-wait-seconds", "60"))
                        .build(),
                "The namespace is at most 64 bytes in UTF-8: " + "日".repeat(22) + "...");
    }

    @Test
    void anEntityTypeOverItsLimitStopsTheStart(@TempDir Path folder) throws IOException {
        String name = "E".repeat(129);
        Path schema = folder.resolve("schema.graphql");
        Files.writeString(schema, "directive @domain on OBJECT\n\ntype " + name + " @domain {\n  name: String\n}\n\n"
                + "type Query {\n  entity: " + name + "\n}\n");
        refusedAtOnce(configuration(schema.toString(), ""),
                "The name of an entity type is at most 128 bytes in UTF-8: " + "E".repeat(80) + "...");
    }
}
