package io.descoped.lds.graphql.jsonschema;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import io.descoped.lds.graphql.schemas.GraphQLSchemaBuilder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JSON Schema of a list of scalars: a list of texts says its items are texts; a list of any other scalar says
 * nothing of its items, as the converter writes it today.
 */
class ListOfScalarsTest {

    @Test
    void aListOfTextsHasTextItemsAndAListOfOtherScalarsHasItemsOfNoType() {
        GraphQLSchema schema = GraphQLSchemaBuilder.parseSchema(new SchemaParser().parse("""
                directive @domain on OBJECT
                type Person @domain { name: String! nicknames: [String] scores: [Int] }
                type Query { x: String }
                """));

        JsonNode properties = new GraphQLToJsonConverter(schema).createSpecification(schema).get("Person")
                .get("$defs").get("Person").get("properties");

        assertThat(properties.get("nicknames").get("items").get("type").stringValue()).isEqualTo("string");
        assertThat(properties.get("scores").get("items").has("type")).isFalse();
    }
}
