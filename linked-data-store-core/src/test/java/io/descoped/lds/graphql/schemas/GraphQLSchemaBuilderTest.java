package io.descoped.lds.graphql.schemas;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schema builder makes the schema LDS answers GraphQL with from a GraphQL schema file, with a store that holds
 * nothing ({@link EmptyPersistence}): building it reads nothing from the store.
 */
class GraphQLSchemaBuilderTest {

    @Test
    void thatASchemaIsBuiltFromAGraphQLSchemaFile() throws IOException {
        TypeDefinitionRegistry registry;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("gqlschema/schema.graphql");
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            registry = new SchemaParser().parse(reader);
        }
        GraphQLSchemaBuilder builder = new GraphQLSchemaBuilder("namespace", new EmptyPersistence(), null, null);

        GraphQLSchema schema = builder.getGraphQL(GraphQLSchemaBuilder.parseSchema(registry));

        assertThat(schema.getQueryType()).isNotNull();
        assertThat(schema.getQueryType().getFieldDefinitions()).isNotEmpty();
    }
}
