package io.descoped.lds.core.specification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where the JSON Schemas of a specification are read from: a file names one entity type after itself, a folder gives
 * every file in it whose name ends in .json, in any case, and nothing else; a name that is neither a file nor a
 * resource is refused, and a specification without schemas manages nothing.
 */
class JsonSchemaBasedSpecificationLoadTest {

    private static String schemaOf(String entity) {
        return """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "$ref": "#/$defs/%1$s",
                  "$defs": {"%1$s": {"type": "object", "properties": {"name": {"type": "string"}}}}
                }""".formatted(entity);
    }

    @Test
    void aFolderGivesEveryJsonFileInItAndNothingElse(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Person.json"), schemaOf("Person"));
        Files.writeString(dir.resolve("Company.JSON"), schemaOf("Company"));
        Files.writeString(dir.resolve("README.md"), "not a schema");

        JsonSchemaBasedSpecification specification = JsonSchemaBasedSpecification.create(dir.toString());

        assertThat(specification.getManagedDomains()).containsExactlyInAnyOrder("Person", "Company");
        assertThat(specification.getJsonSchema().getSchemaNames()).containsExactlyInAnyOrder("Person", "Company");
        assertThat(specification.getRootElement()).isNotNull();
    }

    @Test
    void filesAndFoldersCanBeGivenTogether(@TempDir Path dir) throws Exception {
        Path folder = Files.createDirectory(dir.resolve("more"));
        Files.writeString(folder.resolve("Company.json"), schemaOf("Company"));
        Path file = Files.writeString(dir.resolve("Person.json"), schemaOf("Person"));

        JsonSchemaBasedSpecification specification =
                JsonSchemaBasedSpecification.create(file.toString(), folder.toString());

        assertThat(specification.getManagedDomains()).containsExactlyInAnyOrder("Person", "Company");
    }

    @Test
    void aNameThatIsNeitherAFileNorAResourceIsRefused(@TempDir Path dir) {
        String missing = dir.resolve("Missing.json").toString();

        assertThatThrownBy(() -> JsonSchemaBasedSpecification.create(missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unable to find resource: " + missing);
    }

    @Test
    void aSpecificationWithoutSchemasManagesNothing() {
        JsonSchemaBasedSpecification specification = new JsonSchemaBasedSpecification();

        assertThat(specification.getManagedDomains()).isEmpty();
        assertThat(specification.getJsonSchema()).isNull();
        assertThat(specification.getRootElement()).isNull();
    }
}
