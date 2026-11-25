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

    @Test
    void aListOfAnEnumIsAListOfItsNames() {
        Model model = Model.of("""
                type Thing @domain { kinds: [Kind] sorts: [Kind!]! }
                enum Kind { A B }
                """);

        model.valid("Thing", "{'kinds':['A','B'],'sorts':['B']}");
        model.refused("Thing", "{'kinds':'A','sorts':[]}", "[$.kinds: string found, array expected]");
        model.refused("Thing", "{'kinds':['C'],'sorts':[]}",
                "[$.kinds[0]: does not have a value in the enumeration [\"A\", \"B\"]]");
        model.refused("Thing", "{'sorts':['C']}", "[$.sorts[0]: does not have a value in the enumeration [\"A\", \"B\"]]");
    }

    @Test
    void aListOfAUnionOfEntityTypesWithoutLinkIsAListOfLinksToThem() {
        Model model = Model.of("""
                type Owner @domain { things: [Thing] }
                type Job @domain { title: String }
                type Car @domain { make: String }
                union Thing = Job | Car
                """);

        model.valid("Owner", "{'things':['/Job/j1','/Car/c1']}");
        model.refused("Owner", "{'things':'/Job/j1'}", "[$.things: string found, array expected]");
        model.refused("Owner", "{'things':['/Person/p1']}",
                "Illegal linked-domain: \"Person\". Navigation: things: \"/Person/p1\"");
    }

    @Test
    void aListFieldWithWrappedItemsInAnEntityTypeHeldAsAnEmbeddedObjectIsRead() {
        Model model = Model.of("""
                type Holder @domain { supports: [Support] }
                type Support @domain { tags: [String!] lines: [[String]] staff: [Person] @link }
                type Person @domain { name: String }
                """);

        model.valid("Holder", "{'supports':{'staff':['/Person/p1']}}");
        assertThat(model.field("Holder", "supports").getProperties()).containsKeys("tags", "lines", "staff");
    }

    /**
     * A reverse link is found from the documents that link to this one, and is not held in it.
     */
    @Test
    void aReverseLinkWrittenInTheModelIsNotAFieldOfTheDocument() {
        Model model = Model.of("""
                directive @reverseLink(mappedBy: String, pagination: Boolean) on FIELD_DEFINITION
                type Source @domain { link: [Target] @link }
                type Target @domain {
                  bar: String
                  back: [Source!]! @reverseLink(mappedBy: "$.link[]", pagination: false)
                  paged: [Source] @reverseLink(mappedBy: "$.link[]")
                }
                """);

        model.valid("Target", "{'bar':'x'}");
        assertThat(model.specification().getRootElement().getProperties().get("Target").getProperties())
                .containsOnlyKeys("bar");
    }

    @Test
    void everyListOfLinksWithNonNullItemsIsALink() {
        Model model = Model.of("""
                type Team @domain { leads: [Person!] @link members: [Person!] @link guests: [Person!]! @link }
                type Person @domain { name: String }
                """);

        model.valid("Team", "{'leads':['/Person/p1'],'members':['/Person/p2'],'guests':['/Person/p3']}");
        for (String field : new String[]{"leads", "members", "guests"}) {
            model.refused("Team", "{'guests':[],'" + field + "':['/Job/j1']}",
                    "Illegal linked-domain: \"Job\". Navigation: " + field + ": \"/Job/j1\"");
            assertThat(model.field("Team", field).getRefTypes()).as(field).containsExactly("Person");
        }
    }
}
