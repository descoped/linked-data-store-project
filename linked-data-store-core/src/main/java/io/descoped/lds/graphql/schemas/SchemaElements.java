package io.descoped.lds.graphql.schemas;

import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedSchemaElement;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLUnionType;
import graphql.schema.GraphqlTypeComparatorEnvironment;
import graphql.schema.GraphqlTypeComparatorRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The builders and readers LDS uses for the elements of a schema.
 * <p>
 * LDS builds its schema in passes that copy and replace elements, and what comes out follows from how
 * graphql-java 12 built, copied and read an element. Later versions do some of that in another way. What is here
 * keeps it as it was:
 * <ul>
 * <li>fields, arguments, enum values, interfaces and the members of a union are in the order of their names, and
 * the directives on an element in the order they were given;</li>
 * <li>a copy of an element starts from what the element holds now, with its type references resolved, not from
 * what it was built with;</li>
 * <li>a directive put on an element takes the place of the one with the same name;</li>
 * <li>a list or non-null type has no name;</li>
 * <li>the value of an argument of a directive is read as the Java value.</li>
 * </ul>
 */
public final class SchemaElements {

    /**
     * The order of the parts of an element: by name, except the directives, which stay as they were given.
     */
    public static final GraphqlTypeComparatorRegistry ORDER = new GraphqlTypeComparatorRegistry() {
        @Override
        public <T extends GraphQLSchemaElement> Comparator<? super T> getComparator(GraphqlTypeComparatorEnvironment environment) {
            Class<? extends GraphQLSchemaElement> elementType = environment.getElementType();
            if (elementType == GraphQLDirective.class || elementType == GraphQLAppliedDirective.class) {
                return AS_IS_REGISTRY.getComparator(environment);
            }
            return BY_NAME_REGISTRY.getComparator(environment);
        }
    };

    private SchemaElements() {
    }

    public static GraphQLObjectType.Builder newObject() {
        return GraphQLObjectType.newObject().comparatorRegistry(ORDER);
    }

    public static GraphQLObjectType.Builder newObject(GraphQLObjectType existing) {
        return GraphQLObjectType.newObject(existing).comparatorRegistry(ORDER)
                .replaceInterfaces(existing.getInterfaces());
    }

    public static GraphQLInterfaceType.Builder newInterface(GraphQLInterfaceType existing) {
        return GraphQLInterfaceType.newInterface(existing).comparatorRegistry(ORDER)
                .replaceInterfacesOrReferences(existing.getInterfaces());
    }

    public static GraphQLUnionType.Builder newUnionType() {
        return GraphQLUnionType.newUnionType().comparatorRegistry(ORDER);
    }

    public static GraphQLUnionType.Builder newUnionType(GraphQLUnionType existing) {
        return GraphQLUnionType.newUnionType(existing).comparatorRegistry(ORDER)
                .replacePossibleTypes(existing.getTypes());
    }

    public static GraphQLEnumType.Builder newEnum() {
        return GraphQLEnumType.newEnum().comparatorRegistry(ORDER);
    }

    public static GraphQLFieldDefinition.Builder newFieldDefinition() {
        return GraphQLFieldDefinition.newFieldDefinition().comparatorRegistry(ORDER);
    }

    public static GraphQLFieldDefinition.Builder newFieldDefinition(GraphQLFieldDefinition existing) {
        return GraphQLFieldDefinition.newFieldDefinition(existing).comparatorRegistry(ORDER)
                .type(existing.getType());
    }

    public static GraphQLDirective.Builder newDirective() {
        return GraphQLDirective.newDirective().comparatorRegistry(ORDER);
    }

    public static GraphQLDirective.Builder newDirective(GraphQLDirective existing) {
        return GraphQLDirective.newDirective(existing).comparatorRegistry(ORDER);
    }

    /**
     * Puts a directive on a field that is being copied. It takes the place of the directive with the same name,
     * or comes last when there is none.
     */
    public static GraphQLFieldDefinition.Builder withDirective(GraphQLFieldDefinition.Builder field,
                                                               GraphQLFieldDefinition existing,
                                                               GraphQLDirective directive) {
        Map<String, GraphQLDirective> directives = new LinkedHashMap<>();
        for (GraphQLDirective existingDirective : existing.getDirectives()) {
            directives.putIfAbsent(existingDirective.getName(), existingDirective);
        }
        directives.put(directive.getName(), directive);

        // The applied directive of that name was made from the schema file, and would show what was written
        // there instead of what the directive says now.
        List<GraphQLAppliedDirective> appliedDirectives = new ArrayList<>();
        for (GraphQLAppliedDirective appliedDirective : existing.getAppliedDirectives()) {
            if (!appliedDirective.getName().equals(directive.getName())) {
                appliedDirectives.add(appliedDirective);
            }
        }
        return field.replaceDirectives(new ArrayList<>(directives.values()))
                .replaceAppliedDirectives(appliedDirectives);
    }

    /**
     * The name of an element, or null for one that has none: a list or non-null type.
     */
    public static String name(GraphQLSchemaElement element) {
        if (element instanceof GraphQLNamedSchemaElement) {
            return ((GraphQLNamedSchemaElement) element).getName();
        }
        return null;
    }

    /**
     * The value an argument of a directive was given, as the Java value, or null when it was given none.
     */
    public static Object value(GraphQLArgument argument) {
        return GraphQLArgument.getArgumentValue(argument);
    }

    /**
     * The default value of an argument, as the Java value, or null when it has none.
     */
    public static Object defaultValue(GraphQLArgument argument) {
        return GraphQLArgument.getArgumentDefaultValue(argument);
    }
}
