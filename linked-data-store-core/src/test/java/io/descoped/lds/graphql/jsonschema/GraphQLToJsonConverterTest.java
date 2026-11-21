package io.descoped.lds.graphql.jsonschema;

import graphql.Assert;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.descoped.lds.graphql.schemas.GraphQLSchemaBuilder;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.net.URL;
import java.util.Optional;
import java.util.SequencedMap;

public class GraphQLToJsonConverterTest {

    @Test
    public void thatConversionWorks() {
        File graphQLFile = new File("src/test/resources/spec/abstract/graphqlschemas/schema.graphql");

        TypeDefinitionRegistry definitionRegistry = parseSchemaFile(graphQLFile);

        GraphQLSchema schema = GraphQLSchemaBuilder.parseSchema(definitionRegistry);
        GraphQLToJsonConverter graphQLToJsonConverter = new GraphQLToJsonConverter(schema);
        SequencedMap<String, ObjectNode> jsonMap = graphQLToJsonConverter.createSpecification(schema);

        jsonMap.forEach((name, json) -> {
            System.out.printf("%s :: %s%n", name, json);
        });

        Assert.assertTrue(jsonMap.get("Cat").get("$schema").stringValue().equals("https://json-schema.org/draft/2020-12/schema"));

        Assert.assertTrue(jsonMap.get("Cat").get("$ref").stringValue().equals("#/$defs/Cat"));

        Assert.assertTrue(jsonMap.get("Cat").get("$defs").get("Cat")
                .get("displayName").stringValue().equals("This is a cat"));

        Assert.assertTrue(jsonMap.get("Dog").get("$defs").get("Dog")
                .get("displayName").stringValue().equals("This is a dog"));

        Assert.assertTrue(jsonMap.get("Dog").get("$defs").get("Dog")
                .get("properties").get("friendly")
                .get("type").stringValue().equals("boolean"));

        Assert.assertTrue(jsonMap.get("Cat").get("$defs").get("Cat")
                .get("properties").get("alive")
                .get("type").stringValue().equals("boolean"));

        Assert.assertTrue(jsonMap.get("Cat").get("$defs").get("Cat")
                .get("required").size() == 1);

        Assert.assertNotEmpty(jsonMap.values());
    }

    private static TypeDefinitionRegistry parseSchemaFile(File graphQLFile) {
        TypeDefinitionRegistry definitionRegistry;
        URL systemResource = ClassLoader.getSystemResource(graphQLFile.getPath());

        if (Optional.ofNullable(systemResource).isPresent()) {
            definitionRegistry = new SchemaParser().parse(new File(systemResource.getPath()));
        } else {
            definitionRegistry = new SchemaParser().parse(new File(graphQLFile.getPath()));
        }
        return definitionRegistry;
    }
}