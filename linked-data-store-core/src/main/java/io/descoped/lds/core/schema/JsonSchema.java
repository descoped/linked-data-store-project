package io.descoped.lds.core.schema;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The JSON Schemas of the entities of the model, by the name of the entity: what a document is validated with,
 * the text that is given out for {@code ?schema}, and the definitions that the specification is built from.
 */
public class JsonSchema {

    final Map<String, Schema> schemas = new LinkedHashMap<>();
    final Map<String, String> schemaJsonByName = new LinkedHashMap<>();
    final Map<String, JsonSchemaDefinitionElement> definitions = new LinkedHashMap<>();

    JsonSchema() {
    }

    public Set<String> getSchemaNames() {
        return new LinkedHashSet<>(schemaJsonByName.keySet());
    }

    public Map<String, JsonSchemaDefinitionElement> getDefinitions() {
        return definitions;
    }

    JsonSchema addSchema(String name, Schema schema) {
        schemas.put(name, schema);
        return this;
    }

    /**
     * Validates a document with the schema of an entity. A {@code format} is checked, as it was when LDS read
     * JSON Schema draft-04: a text in a {@code date-time} field that is no date-time is an error.
     *
     * @param name     the name of the entity
     * @param document the document
     * @return what is wrong with the document, each as the place in the document and what is wrong there
     * ({@code $.name: integer found, string expected}); empty when the document is valid
     * @throws IllegalArgumentException when the model has no entity of that name
     */
    public List<String> validate(String name, JsonNode document) {
        Schema schema = schemas.get(name);
        if (schema == null) {
            throw new IllegalArgumentException("No JSON Schema for the entity " + name);
        }
        return schema
                .validate(document, context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))
                .stream()
                .map(Error::toString)
                .toList();
    }

    JsonSchema addSchemaJson(String name, String schemaJson) {
        schemaJsonByName.put(name, schemaJson);
        return this;
    }

    public String getSchemaJson(String name) {
        return schemaJsonByName.get(name);
    }

    JsonSchema addDefinition(String definitionName, JsonSchemaDefinitionElement jsde) {
        jsde.name = definitionName;
        definitions.put(definitionName, jsde);
        return this;
    }
}
