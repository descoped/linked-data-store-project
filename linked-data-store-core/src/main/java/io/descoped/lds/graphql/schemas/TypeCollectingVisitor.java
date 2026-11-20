package io.descoped.lds.graphql.schemas;

import graphql.AssertException;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.schema.GraphQLUnionType;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collects the named types it is shown into a map, in the order it meets them.
 * <p>
 * The passes of {@link GraphQLSchemaBuilder} walk the types in the order of this map, and what they produce
 * follows from that order. The collecting visitor of graphql-java gave this order in version 12 and gives the
 * types by name in later versions, so LDS has its own.
 */
public class TypeCollectingVisitor extends GraphQLTypeVisitorStub {

    private final Map<String, GraphQLNamedType> result = new LinkedHashMap<>();

    @Override
    public TraversalControl visitGraphQLEnumType(GraphQLEnumType node, TraverserContext<GraphQLSchemaElement> context) {
        assertTypeUniqueness(node);
        result.put(node.getName(), node);
        return super.visitGraphQLEnumType(node, context);
    }

    @Override
    public TraversalControl visitGraphQLScalarType(GraphQLScalarType node, TraverserContext<GraphQLSchemaElement> context) {
        assertTypeUniqueness(node);
        result.put(node.getName(), node);
        return super.visitGraphQLScalarType(node, context);
    }

    @Override
    public TraversalControl visitGraphQLObjectType(GraphQLObjectType node, TraverserContext<GraphQLSchemaElement> context) {
        saveOnce(node);
        return super.visitGraphQLObjectType(node, context);
    }

    @Override
    public TraversalControl visitGraphQLInputObjectType(GraphQLInputObjectType node, TraverserContext<GraphQLSchemaElement> context) {
        saveOnce(node);
        return super.visitGraphQLInputObjectType(node, context);
    }

    @Override
    public TraversalControl visitGraphQLInterfaceType(GraphQLInterfaceType node, TraverserContext<GraphQLSchemaElement> context) {
        saveOnce(node);
        return super.visitGraphQLInterfaceType(node, context);
    }

    @Override
    public TraversalControl visitGraphQLUnionType(GraphQLUnionType node, TraverserContext<GraphQLSchemaElement> context) {
        assertTypeUniqueness(node);
        result.put(node.getName(), node);
        return super.visitGraphQLUnionType(node, context);
    }

    private void saveOnce(GraphQLNamedType type) {
        if (result.containsKey(type.getName())) {
            assertTypeUniqueness(type);
        } else {
            result.put(type.getName(), type);
        }
    }

    private void assertTypeUniqueness(GraphQLNamedType type) {
        GraphQLNamedType existingType = result.get(type.getName());
        // the comparison of the objects is deliberate
        if (existingType != null && existingType != type) {
            throw new AssertException("""
                    All types within a GraphQL schema must have unique names. No two provided types may have the same name.
                    No provided type may have a name which conflicts with any built in types (including Scalar and Introspection types).
                    You have redefined the type '%s' from being a '%s' to a '%s'"""
                    .formatted(type.getName(), existingType.getClass().getSimpleName(), type.getClass().getSimpleName()));
        }
    }

    /**
     * The types that were met, by name, in the order they were met. It is the map itself, not a copy: the passes
     * of {@link GraphQLSchemaBuilder} put the types they make into it.
     *
     * @return the types by name
     */
    public Map<String, GraphQLNamedType> getResult() {
        return result;
    }
}
