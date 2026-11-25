package io.descoped.lds.core.validation;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.specification.SpecificationElement;
import io.descoped.lds.core.schema.JsonSchema;
import io.descoped.lds.core.schema.JsonSchemaBuilder;
import io.descoped.lds.core.specification.JsonSchemaBasedSpecification;
import io.descoped.lds.core.specification.SpecificationJsonSchemaBuilder;
import io.descoped.lds.graphql.jsonschema.GraphQLToJsonConverter;
import io.descoped.lds.graphql.schemas.GraphQLSchemaBuilder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a document of a GraphQL model is checked for before it is written: the JSON Schema LDS writes from the
 * GraphQL schema holds each field to the shape the model gives it.
 */
class GraphQLModelValidationTest {

    private static final String DIRECTIVES = """
            directive @domain(searchable: Boolean = true) on OBJECT
            directive @link(pagination: Boolean = true, reverseName: String) on FIELD_DEFINITION
            type Query { x: String }
            """;

    /*
     * The specification and the validator of a model, made as the application makes them.
     */
    private record Model(JsonSchemaBasedSpecification specification, LinkedDocumentValidator validator) {

        static Model of(String types) {
            GraphQLSchema schema = GraphQLSchemaBuilder.parseSchema(new SchemaParser().parse(DIRECTIVES + types));
            JsonSchemaBasedSpecification specification = null;
            JsonSchema jsonSchema = null;
            for (Map.Entry<String, ObjectNode> item : new GraphQLToJsonConverter(schema).createSpecification(schema)
                    .entrySet()) {
                jsonSchema = new JsonSchemaBuilder(jsonSchema, item.getKey(), item.getValue()).build();
                specification = SpecificationJsonSchemaBuilder.createBuilder(jsonSchema).build();
            }
            return new Model(specification, new LinkedDocumentValidator(specification, specification));
        }

        void valid(String entity, String document) {
            assertThatCode(() -> validator.validate(entity, json(document))).as(document).doesNotThrowAnyException();
        }

        void refused(String entity, String document, String message) {
            assertThatThrownBy(() -> validator.validate(entity, json(document))).as(document)
                    .isInstanceOf(LinkedDocumentValidationException.class)
                    .hasMessage(message);
        }

        SpecificationElement field(String entity, String field) {
            return specification.getRootElement().getProperties().get(entity).getProperties().get(field);
        }

        private static tools.jackson.databind.JsonNode json(String document) {
            return JsonTools.toJsonNode(document.replace('\'', '"'));
        }
    }

    @Test
    void aSingleFieldOfAnEmbeddedTypeIsCheckedAsAListOfThemIs() {
        Model model = Model.of("""
                type Person @domain { name: String home: Address work: Address! homes: [Address] }
                type Address { city: String! zip: Int }
                """);

        model.valid("Person", "{'name':'V','home':{'city':'Oslo','zip':150},'work':{'city':'Bergen'}}");
        model.refused("Person", "{'work':{'city':7}}", "[$.work.city: integer found, string expected]");
        model.refused("Person", "{'work':{'city':'Oslo'},'home':'just text'}", "[$.home: string found, object expected]");
        model.refused("Person", "{'work':{'city':'Oslo'},'home':{'zip':1}}",
                "[$.home: required property 'city' not found]");
        model.refused("Person", "{'work':{'city':'Oslo'},'homes':[{'zip':1}]}",
                "[$.homes[0]: required property 'city' not found]");
        assertThat(model.field("Person", "home").getProperties()).containsOnlyKeys("city", "zip");
    }
}
