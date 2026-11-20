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

    /**
     * A builder of a new object type. Unlike the builder of graphql-java, it puts the fields and the interfaces
     * in the order of their names.
     *
     * @return the builder
     */
    public static GraphQLObjectType.Builder newObject() {
        return GraphQLObjectType.newObject().comparatorRegistry(ORDER);
    }

    /**
     * A builder of a copy of an object type, ordered as {@link #newObject()}. The builder of graphql-java starts
     * a copy with the interfaces the type was built with, which may be references by name; this one starts with
     * the interfaces the type has now.
     *
     * @param existing the type to copy
     * @return the builder
     */
    public static GraphQLObjectType.Builder newObject(GraphQLObjectType existing) {
        return GraphQLObjectType.newObject(existing).comparatorRegistry(ORDER)
                .replaceInterfaces(existing.getInterfaces());
    }

    /**
     * A builder of a copy of an interface type, with the fields in the order of their names and the interfaces
     * the type has now, not the references it was built with.
     *
     * @param existing the type to copy
     * @return the builder
     */
    public static GraphQLInterfaceType.Builder newInterface(GraphQLInterfaceType existing) {
        return GraphQLInterfaceType.newInterface(existing).comparatorRegistry(ORDER)
                .replaceInterfacesOrReferences(existing.getInterfaces());
    }

    /**
     * A builder of a new union type. Unlike the builder of graphql-java, it puts the members in the order of
     * their names.
     *
     * @return the builder
     */
    public static GraphQLUnionType.Builder newUnionType() {
        return GraphQLUnionType.newUnionType().comparatorRegistry(ORDER);
    }

    /**
     * A builder of a copy of a union type, ordered as {@link #newUnionType()}, with the members the type has
     * now, not the references it was built with.
     *
     * @param existing the type to copy
     * @return the builder
     */
    public static GraphQLUnionType.Builder newUnionType(GraphQLUnionType existing) {
        return GraphQLUnionType.newUnionType(existing).comparatorRegistry(ORDER)
                .replacePossibleTypes(existing.getTypes());
    }

    /**
     * A builder of a new enum type. Unlike the builder of graphql-java, it puts the values in the order of their
     * names.
     *
     * @return the builder
     */
    public static GraphQLEnumType.Builder newEnum() {
        return GraphQLEnumType.newEnum().comparatorRegistry(ORDER);
    }

    /**
     * A builder of a new field. Unlike the builder of graphql-java, it puts the arguments in the order of their
     * names.
     *
     * @return the builder
     */
    public static GraphQLFieldDefinition.Builder newFieldDefinition() {
        return GraphQLFieldDefinition.newFieldDefinition().comparatorRegistry(ORDER);
    }

    /**
     * A builder of a copy of a field, ordered as {@link #newFieldDefinition()}, with the type the field has now,
     * not the reference it was built with.
     *
     * @param existing the field to copy
     * @return the builder
     */
    public static GraphQLFieldDefinition.Builder newFieldDefinition(GraphQLFieldDefinition existing) {
        return GraphQLFieldDefinition.newFieldDefinition(existing).comparatorRegistry(ORDER)
                .type(existing.getType());
    }

    /**
     * A builder of a new directive. Unlike the builder of graphql-java, it puts the arguments in the order of
     * their names.
     *
     * @return the builder
     */
    public static GraphQLDirective.Builder newDirective() {
        return GraphQLDirective.newDirective().comparatorRegistry(ORDER);
    }

    /**
     * A builder of a copy of a directive, ordered as {@link #newDirective()}.
     *
     * @param existing the directive to copy
     * @return the builder
     */
    public static GraphQLDirective.Builder newDirective(GraphQLDirective existing) {
        return GraphQLDirective.newDirective(existing).comparatorRegistry(ORDER);
    }

    /**
     * Puts a directive on a field that is being copied. It takes the place of the directive with the same name,
     * or comes last when there is none. The builder of graphql-java would add it after the one it has.
     *
     * @param field     the builder of the copy
     * @param existing  the field that is copied
     * @param directive the directive to put on the copy
     * @return the builder of the copy
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
     * The name of an element, or null for one that has none: a list or non-null type. Every type of graphql-java
     * had a name, null for those, when LDS was written.
     *
     * @param element the element
     * @return its name, or null
     */
    public static String name(GraphQLSchemaElement element) {
        if (element instanceof GraphQLNamedSchemaElement named) {
            return named.getName();
        }
        return null;
    }

    /**
     * The value an argument of a directive was given, as the Java value, or null when it was given none.
     * graphql-java now holds a value in the form it was given in, a literal of a schema file or a Java value;
     * this gives the Java value, as LDS always read it.
     *
     * @param argument the argument
     * @return its value, or null
     */
    public static Object value(GraphQLArgument argument) {
        return GraphQLArgument.getArgumentValue(argument);
    }

    /**
     * The default value of an argument, as the Java value, or null when it has none.
     *
     * @param argument the argument
     * @return its default value, or null
     */
    public static Object defaultValue(GraphQLArgument argument) {
        return GraphQLArgument.getArgumentDefaultValue(argument);
    }
}
