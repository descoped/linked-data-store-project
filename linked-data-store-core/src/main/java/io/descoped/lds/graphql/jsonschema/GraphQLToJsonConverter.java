package io.descoped.lds.graphql.jsonschema;

import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLSchema;
import io.descoped.lds.graphql.jsonschema.visitors.ParseObjectTypesVisitor;
import io.descoped.lds.graphql.schemas.TypeTraverser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

public class GraphQLToJsonConverter {
    private static final Logger LOG = LoggerFactory.getLogger(GraphQLToJsonConverter.class);
    private static final TypeTraverser TRAVERSER = new TypeTraverser();
    GraphQLSchema graphQLSchema;

    public GraphQLToJsonConverter(GraphQLSchema schema) {
        this.graphQLSchema = schema;
    }

    public LinkedHashMap<String, ObjectNode> createSpecification(GraphQLSchema graphQLSchema) {
        Map<String, GraphQLNamedType> typeMap = graphQLSchema.getTypeMap();
        LinkedHashMap<String, ObjectNode> jsonMap = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();

        ParseObjectTypesVisitor parseObjectTypesVisitor = new ParseObjectTypesVisitor(jsonMap);
        TRAVERSER.depthFirst(parseObjectTypesVisitor, typeMap.values());

        jsonMap.forEach((managedDomain, jsonObject) -> {
            sb.append(" /" + managedDomain);
        });

        LOG.info("{}", (sb.isEmpty() ? "No schemas configured!" : "Managed domains: " + sb.toString().substring(1)));

        return jsonMap;
    }
}
