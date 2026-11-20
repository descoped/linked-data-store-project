package io.descoped.lds.graphql.schemas;

import graphql.AssertException;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLUnionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static graphql.Scalars.GraphQLInt;
import static graphql.Scalars.GraphQLString;
import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLInputObjectField.newInputObjectField;
import static graphql.schema.GraphQLObjectType.newObject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The named types of a schema are collected in the order the walk meets them.
 */
class TypeCollectingVisitorTest {

    private static final TypeTraverser TRAVERSER = new TypeTraverser();

    private static TypeCollectingVisitor collect(List<? extends GraphQLSchemaElement> roots) {
        TypeCollectingVisitor visitor = new TypeCollectingVisitor();
        TRAVERSER.depthFirst(visitor, roots);
        return visitor;
    }

    @Test
    void typesOfEveryKindAreCollectedInTheOrderTheyAreMet() {
        GraphQLEnumType kind = GraphQLEnumType.newEnum().name("Kind").value("A").build();
        GraphQLInputObjectType filter = GraphQLInputObjectType.newInputObject().name("Filter")
                .field(newInputObjectField().name("limit").type(GraphQLInt))
                .build();
        GraphQLInterfaceType named = GraphQLInterfaceType.newInterface().name("Named")
                .field(newFieldDefinition().name("name").type(GraphQLString))
                .build();
        GraphQLObjectType zebra = newObject().name("Zebra")
                .withInterface(named)
                .field(newFieldDefinition().name("name").type(GraphQLString))
                .field(newFieldDefinition().name("kind").type(kind).argument(newArgument().name("filter").type(filter)))
                .build();
        GraphQLUnionType animal = GraphQLUnionType.newUnionType().name("Animal").possibleType(zebra).build();
        GraphQLObjectType query = newObject().name("Query")
                .field(newFieldDefinition().name("animal").type(animal))
                .build();

        TypeCollectingVisitor visitor = collect(List.of(query, zebra));

        assertThat(visitor.getResult().keySet())
                .containsExactly("Query", "Animal", "Zebra", "String", "Kind", "Filter", "Int", "Named");
        assertThat(visitor.getResult()).containsEntry("Zebra", zebra).containsEntry("Animal", animal);
    }

    @Test
    void aTypeThatIsMetAgainInAnotherWalkIsKept() {
        GraphQLObjectType thing = newObject().name("Thing").field(newFieldDefinition().name("a").type(GraphQLString)).build();
        TypeCollectingVisitor visitor = new TypeCollectingVisitor();

        TRAVERSER.depthFirst(visitor, thing);
        TRAVERSER.depthFirst(visitor, thing);

        assertThat(visitor.getResult().keySet()).containsExactly("Thing", "String");
        assertThat(visitor.getResult()).containsEntry("Thing", thing);
    }

    @Test
    void twoObjectTypesOfOneNameAreRefused() {
        GraphQLObjectType one = newObject().name("Thing").field(newFieldDefinition().name("a").type(GraphQLString)).build();
        GraphQLObjectType other = newObject().name("Thing").field(newFieldDefinition().name("b").type(GraphQLString)).build();

        assertThatThrownBy(() -> collect(List.of(one, other)))
                .isInstanceOf(AssertException.class)
                .hasMessage("""
                        All types within a GraphQL schema must have unique names. No two provided types may have the same name.
                        No provided type may have a name which conflicts with any built in types (including Scalar and Introspection types).
                        You have redefined the type 'Thing' from being a 'GraphQLObjectType' to a 'GraphQLObjectType'""");
    }

    @Test
    void anEnumOfTheNameOfAnotherTypeIsRefused() {
        GraphQLObjectType object = newObject().name("Thing").field(newFieldDefinition().name("a").type(GraphQLString)).build();
        GraphQLEnumType anEnum = GraphQLEnumType.newEnum().name("Thing").value("A").build();

        assertThatThrownBy(() -> collect(List.of(object, anEnum)))
                .isInstanceOf(AssertException.class)
                .hasMessageEndingWith("You have redefined the type 'Thing' from being a 'GraphQLObjectType' to a 'GraphQLEnumType'");
    }
}
