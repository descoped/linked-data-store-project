package io.descoped.lds.core.specification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A JSON Schema file names the definition of its entity type with a {@code $ref} at its root. A file without one
 * would validate nothing, and is refused with a message that names the file.
 */
class JsonSchemaFileRootTest {

    private static final String DEFS = """
            "$defs": {"T": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}}""";

    @Test
    void aFileWithTheRefOfItsEntityTypeIsRead(@TempDir Path folder) throws IOException {
        Path file = Files.writeString(folder.resolve("T.json"), "{\"$ref\": \"#/$defs/T\", " + DEFS + "}");

        assertThat(JsonSchemaBasedSpecification.create(file.toString()).getManagedDomains()).containsExactly("T");
    }

    @Test
    void aFileWithoutARefAtItsRootIsRefused(@TempDir Path folder) throws IOException {
        Path file = Files.writeString(folder.resolve("T.json"), "{" + DEFS + "}");

        assertThatThrownBy(() -> JsonSchemaBasedSpecification.create(file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The JSON Schema file " + file + " has no \"$ref\" at its root. It names the definition "
                        + "of the entity type T: \"$ref\": \"#/$defs/T\"");
    }

    @Test
    void aFileWithItsSchemaAtItsRootIsRefused(@TempDir Path folder) throws IOException {
        Path file = Files.writeString(folder.resolve("T.json"),
                "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}}");

        assertThatThrownBy(() -> JsonSchemaBasedSpecification.create(folder.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("The JSON Schema file " + file + " has no \"$ref\" at its root.");
    }
}
