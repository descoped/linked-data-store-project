package io.descoped.lds.graphql.jsonschema;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import io.descoped.lds.graphql.schemas.GraphQLSchemaBuilder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JSON Schema of a list of scalars: its items have the type of the scalar, as a field of that scalar has.
 */
class ListOfScalarsTest {

    @Test
    void theItemsOfAListOfScalarsHaveTheTypeOfTheScalar() {
        GraphQLSchema schema = GraphQLSchemaBuilder.parseSchema(new SchemaParser().parse("""
                directive @domain on OBJECT
                type Person @domain { name: String! nicknames: [String] scores: [Int] }
                type Query { x: String }
                """));

        JsonNode properties = new GraphQLToJsonConverter(schema).createSpecification(schema).get("Person")
                .get("$defs").get("Person").get("properties");

        assertThat(properties.get("nicknames").get("items").get("type").stringValue()).isEqualTo("string");
        assertThat(properties.get("scores").get("items").get("type").stringValue()).isEqualTo("integer");
        assertThat(properties.get("scores").get("items").get("maximum").asLong()).isEqualTo(Integer.MAX_VALUE);
    }
}
