package io.descoped.lds.core.schema;

import io.descoped.lds.api.persistence.json.JsonTools;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What LDS reads as the JSON Schema of an entity: JSON Schema 2020-12, and nothing earlier.
 */
class JsonSchemaBuilderTest {

    private static final String PERSON = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "$ref": "#/$defs/Person",
              "$defs": {
                "Person": {
                  "type": "object",
                  "title": "A person",
                  "description": "Someone",
                  "properties": {
                    "name": {"type": "string"},
                    "born": {"type": "string", "format": "date-time"},
                    "nick": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                    "alias": {"type": ["string", "null"]},
                    "tags": {"type": "array", "items": {"type": "string"}},
                    "home": {"$ref": "#/$defs/Address"},
                    "more": {}
                  },
                  "required": ["name", "born"]
                },
                "Address": {
                  "type": "object",
                  "properties": {"street": {"type": "string"}}
                }
              }
            }
            """;

    private static JsonNode json(String text) {
        return JsonTools.toJsonNode(text);
    }

    private static JsonSchema read(String name, String text) {
        return new JsonSchemaBuilder(null, name, json(text)).build();
    }

    @Test
    void theDialectIsJsonSchema2020() {
        assertThat(JsonSchemaBuilder.DIALECT).isEqualTo("https://json-schema.org/draft/2020-12/schema");
    }

    @Test
    void theDefinitionsOfASchemaAreRead() {
        JsonSchema schema = read("Person", PERSON);

        assertThat(schema.getSchemaNames()).containsExactly("Person");
        assertThat(schema.getDefinitions()).containsOnlyKeys("Person", "Address");
        JsonSchemaDefinitionElement person = schema.getDefinitions().get("Person");
        assertThat(person.name).isEqualTo("Person");
        assertThat(person.types).containsExactly("object");
        assertThat(person.title).isEqualTo("A person");
        assertThat(person.description).isEqualTo("Someone");
        assertThat(person.required).containsExactly("name", "born");
        assertThat(person.properties).containsOnlyKeys("name", "born", "nick", "alias", "tags", "home", "more");
        assertThat(person.properties.get("name").types).containsExactly("string");
        assertThat(person.properties.get("name").description).isEmpty();
        assertThat(person.properties.get("born").format).isEqualTo("date-time");
        assertThat(person.properties.get("nick").types).containsExactly("string", "null");
        // a list of types is read as anyOf is
        assertThat(person.properties.get("alias").types).containsExactly("string", "null");
        assertThat(person.properties.get("tags").types).containsExactly("array");
        assertThat(person.properties.get("tags").items.types).containsExactly("string");
        // a $ref is the definition it names
        assertThat(person.properties.get("home")).isSameAs(schema.getDefinitions().get("Address"));
        // nothing said is an object, as it always was
        assertThat(person.properties.get("more").types).containsExactly("object");
    }

    @Test
    void theTextOfASchemaIsItsJsonWithoutSpace() {
        JsonSchema schema = read("Person", PERSON);

        assertThat(schema.getSchemaJson("Person"))
                .startsWith("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"$ref\":\"#/$defs/Person\",\"$defs\":{\"Person\":{\"type\":\"object\",\"title\":\"A person\"")
                .isEqualTo(json(PERSON).toString());
    }

    @Test
    void aTextWithAQuoteAndSpaceInItIsKept() {
        String withQuote = "{\"$ref\":\"#/$defs/T\",\"$defs\":{\"T\":{\"type\":\"object\",\"description\":\"say \\\"a  b\\\" twice\"}}}";

        JsonSchema schema = read("T", withQuote);

        assertThat(schema.getSchemaJson("T")).isEqualTo(withQuote);
        assertThat(schema.getDefinitions().get("T").description).isEqualTo("say \"a  b\" twice");
    }

    @Test
    void aSchemaThatDoesNotSayWhatItIsIsReadAs2020() {
        JsonSchema schema = read("T", "{\"$ref\":\"#/$defs/T\",\"$defs\":{\"T\":{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"number\"}}}}}");

        assertThat(schema.validate("T", json("{\"n\":1.5}"))).isEmpty();
        assertThat(schema.validate("T", json("{\"n\":\"1.5\"}"))).containsExactly("$.n: string found, number expected");
    }

    @Test
    void anEntityTheModelDoesNotHaveCannotBeValidated() {
        JsonSchema schema = read("Person", PERSON);

        assertThatThrownBy(() -> schema.validate("House", json("{}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("House");
    }

    @Test
    void aDefinitionAnEarlierSchemaGaveIsKept() {
        JsonSchema schema = read("Person", PERSON);
        JsonSchemaDefinitionElement address = schema.getDefinitions().get("Address");

        new JsonSchemaBuilder(schema, "House", json("{\"$ref\":\"#/$defs/House\",\"$defs\":{"
                + "\"House\":{\"type\":\"object\",\"properties\":{\"at\":{\"$ref\":\"#/$defs/Address\"}}},"
                + "\"Address\":{\"type\":\"object\",\"properties\":{\"other\":{\"type\":\"string\"}}}}}")).build();

        assertThat(schema.getSchemaNames()).containsExactly("Person", "House");
        assertThat(schema.getDefinitions().get("Address")).isSameAs(address);
        assertThat(schema.getDefinitions().get("House").properties.get("at")).isSameAs(address);
    }

    @Test
    void aSchemaOfAnEarlierDraftIsRefused() {
        assertThatThrownBy(() -> read("Old", "{\"$schema\":\"http://json-schema.org/draft-04/schema#\",\"$ref\":\"#/definitions/Old\",\"definitions\":{\"Old\":{\"type\":\"object\"}}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Old").hasMessageContaining("draft-04").hasMessageContaining("https://json-schema.org/draft/2020-12/schema");
        assertThatThrownBy(() -> read("Old", "{\"$schema\":\"https://json-schema.org/draft/2019-09/schema\",\"$ref\":\"#/$defs/Old\",\"$defs\":{\"Old\":{\"type\":\"object\"}}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2019-09");
        // an earlier draft that does not say so
        assertThatThrownBy(() -> read("Old", "{\"$ref\":\"#/definitions/Old\",\"definitions\":{\"Old\":{\"type\":\"object\"}}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("\"definitions\"").hasMessageContaining("\"$defs\"");
    }

    @Test
    void aReferenceThatLeadsNowhereIsRefusedWhenTheSchemaIsRead() {
        // in a definition
        assertThatThrownBy(() -> read("T", "{\"$ref\":\"#/$defs/T\",\"$defs\":{\"T\":{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/$defs/Missing\"}}}}}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("#/$defs/Missing");
        // to something that is not a definition of the schema
        assertThatThrownBy(() -> read("T", "{\"$ref\":\"#/$defs/T\",\"$defs\":{\"T\":{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"other.json\"}}}}}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("other.json");
        // of the schema itself
        assertThatThrownBy(() -> read("T", "{\"$ref\":\"#/$defs/Missing\",\"$defs\":{\"T\":{\"type\":\"object\"}}}"))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("Missing");
    }

    @Test
    void whatIsNoSchemaIsRefused() {
        assertThatThrownBy(() -> read("T", "[]")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not an object");
        assertThatThrownBy(() -> read("T", "{\"$ref\":\"#/$defs/T\",\"$defs\":{\"T\":{\"type\":\"object\",\"properties\":{\"a\":\"text\"}}}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a is not an object");
        assertThatThrownBy(() -> read("T", "{\"$ref\":\"#/$defs/T\",\"$defs\":{\"T\":{\"type\":\"object\",\"required\":\"a\"}}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("required");
    }
}
