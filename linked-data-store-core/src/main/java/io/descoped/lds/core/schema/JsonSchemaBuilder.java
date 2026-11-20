package io.descoped.lds.core.schema;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the JSON Schema of one entity into a {@link JsonSchema}: the schema that documents of the entity are
 * validated with, its text, and the definitions that the specification is built from.
 * <p>
 * LDS reads JSON Schema 2020-12. The schema of an entity is an object with {@code $defs}, and a {@code $ref} to
 * the definition that is the entity: {@code {"$ref": "#/$defs/Person", "$defs": {"Person": {...}}}}. A
 * {@code $ref} inside a definition names another definition of the same schema in the same way. A definition
 * that an earlier schema gave under the same name is kept, so entities share the definitions they have in common.
 */
public class JsonSchemaBuilder {

    /**
     * The dialect of JSON Schema that LDS reads and writes: 2020-12.
     */
    public static final String DIALECT = SpecificationVersion.DRAFT_2020_12.getDialectId();

    /*
     * A place in a document is written as LDS writes it everywhere else: $.name[1].first
     */
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            registry -> registry.schemaRegistryConfig(SchemaRegistryConfig.builder().pathType(PathType.JSON_PATH).build()));

    private static final Pattern REF = Pattern.compile("#/\\$defs/(.*)");

    JsonSchema jsonSchema;
    final String schemaName;
    final JsonNode schemaNode;

    /**
     * @param jsonSchema the schemas read so far, or null for the first
     * @param schemaName the name of the entity
     * @param schemaNode the JSON Schema of the entity
     */
    public JsonSchemaBuilder(JsonSchema jsonSchema, String schemaName, JsonNode schemaNode) {
        this.jsonSchema = jsonSchema;
        this.schemaName = schemaName;
        this.schemaNode = schemaNode;
    }

    /**
     * @return the schemas read so far, with this one
     * @throws IllegalArgumentException when the schema is not JSON Schema 2020-12, or is not a schema LDS can read
     * @throws IllegalStateException    when a {@code $ref} names a definition that the schema does not have
     */
    public JsonSchema build() {
        if (jsonSchema == null) {
            jsonSchema = new JsonSchema();
        }
        object(schemaNode, "the schema");
        JsonNode dialect = schemaNode.get("$schema");
        if (dialect != null && !(dialect.isString() && DIALECT.equals(dialect.stringValue()))) {
            throw new IllegalArgumentException("The JSON Schema of " + schemaName + " says it is " + dialect
                    + ". LDS reads JSON Schema 2020-12: " + DIALECT);
        }
        if (schemaNode.has("definitions")) {
            throw new IllegalArgumentException("The JSON Schema of " + schemaName + " has \"definitions\", which is"
                    + " the keyword of an earlier draft. In JSON Schema 2020-12 it is \"$defs\".");
        }
        jsonSchema.addSchemaJson(schemaName, schemaNode.toString());

        JsonNode definitions = schemaNode.get("$defs");
        if (definitions != null) {
            for (Map.Entry<String, JsonNode> definition : object(definitions, "$defs").properties()) {
                if (!jsonSchema.getDefinitions().containsKey(definition.getKey())) {
                    JsonSchemaDefinitionElement jsde = buildElement(definitions, definition.getValue(), definition.getKey());
                    jsonSchema.addDefinition(definition.getKey(), jsde);
                }
            }
        }

        // TODO remove link directives from schema
        Schema schema = REGISTRY.getSchema(schemaNode);
        // now, so that a $ref that leads nowhere fails at start and not at the first write
        schema.initializeValidators();
        jsonSchema.addSchema(schemaName, schema);
        return jsonSchema;
    }

    private JsonSchemaDefinitionElement buildElement(JsonNode definitions, JsonNode jsonElement, String name) {
        object(jsonElement, name);
        if (jsonElement.has("$ref")) {
            String refValue = string(jsonElement.get("$ref"), "$ref of " + name);
            Matcher m = REF.matcher(refValue);
            if (!m.matches()) {
                throw new IllegalStateException("$ref present but unable to resolve: " + refValue);
            }
            String definitionRef = m.group(1);
            if (!jsonSchema.getDefinitions().containsKey(definitionRef)) {
                JsonNode definition = definitions.get(definitionRef);
                if (definition == null) {
                    throw new IllegalStateException("Referenced definition does not exist: " + refValue);
                }
                JsonSchemaDefinitionElement jsde = buildElement(definitions, definition, definitionRef);
                jsonSchema.addDefinition(definitionRef, jsde);
            }
            return jsonSchema.getDefinitions().get(definitionRef);
        }
        String[] types = new String[]{"object"};
        String type = text(jsonElement.get("type"));
        if (type != null) {
            types = new String[]{type};
        } else {
            JsonNode anyOf = jsonElement.get("anyOf");
            if (anyOf != null && anyOf.isArray()) {
                Set<String> set = new LinkedHashSet<>();
                for (JsonNode alternative : anyOf) {
                    String anyOfType = text(alternative.get("type"));
                    if (anyOfType != null) {
                        set.add(anyOfType);
                    }
                }
                types = set.toArray(new String[0]);
            }
        }
        String title = text(jsonElement.get("title"));
        String format = text(jsonElement.get("format"));
        Map<String, JsonSchemaDefinitionElement> properties = new LinkedHashMap<>();
        if (jsonElement.has("properties")) {
            for (Map.Entry<String, JsonNode> property : object(jsonElement.get("properties"), "properties of " + name).properties()) {
                properties.put(property.getKey(), buildElement(definitions, property.getValue(), property.getKey()));
            }
        }
        JsonSchemaDefinitionElement items = null;
        if (jsonElement.has("items")) {
            items = buildElement(definitions, jsonElement.get("items"), "items of " + name);
        }
        Set<String> required = new LinkedHashSet<>();
        if (jsonElement.has("required")) {
            JsonNode requiredNames = jsonElement.get("required");
            if (!requiredNames.isArray()) {
                throw new IllegalArgumentException("In the JSON Schema of " + schemaName + ", required of " + name + " is not an array");
            }
            for (JsonNode requiredName : requiredNames) {
                required.add(string(requiredName, "a name in required of " + name));
            }
        }
        String description = "";
        if (jsonElement.has("description")) {
            description = string(jsonElement.get("description"), "description of " + name);
        }
        return new JsonSchemaDefinitionElement(
                types,
                title,
                description,
                format,
                properties,
                items,
                required
        );
    }

    private JsonNode object(JsonNode node, String what) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("In the JSON Schema of " + schemaName + ", " + what + " is not an object");
        }
        return node;
    }

    private String string(JsonNode node, String what) {
        if (!node.isString()) {
            throw new IllegalArgumentException("In the JSON Schema of " + schemaName + ", " + what + " is not a text");
        }
        return node.stringValue();
    }

    /*
     * The text of a value: null when there is none, and what the value is written as when it is no text.
     */
    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isString() ? node.stringValue() : node.toString();
    }
}
