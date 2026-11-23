package io.descoped.lds.graphql.directives;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import io.descoped.lds.graphql.schemas.SchemaElements;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static graphql.Scalars.GraphQLBoolean;
import static graphql.introspection.Introspection.DirectiveLocation;

/**
 * The domain directive. A directive of graphql-java can no longer be extended, so this class holds one.
 */
public class DomainDirective {

    public static final String NAME = "domain";
    private static final String SEARCHABLE_NAME = "searchable";
    private static final GraphQLArgument SEARCHABLE_ARGUMENT = GraphQLArgument.newArgument()
            .name(SEARCHABLE_NAME)
            .type(GraphQLBoolean)
            .defaultValueProgrammatic(true)
            .build();
    private static final String DESCRIPTION = "Marks a type definition as a managed domain object";

    public static DomainDirective INSTANCE = new DomainDirective(
            NAME,
            DESCRIPTION,
            EnumSet.of(DirectiveLocation.OBJECT),
            List.of(SEARCHABLE_ARGUMENT)
    );

    private final GraphQLDirective directive;

    private DomainDirective(String name, String description, EnumSet<DirectiveLocation> locations, List<GraphQLArgument> arguments) {
        this.directive = SchemaElements.newDirective()
                .name(name)
                .description(description)
                .validLocations(locations.toArray(new DirectiveLocation[0]))
                .replaceArguments(arguments)
                .build();
    }

    /**
     * The directive of graphql-java this domain directive stands for: what is put on an element of a schema, or
     * given to a schema as the definition.
     *
     * @return the directive
     */
    public GraphQLDirective getDirective() {
        return directive;
    }

    /**
     * An argument of the directive.
     *
     * @param name the name of the argument
     * @return the argument, or null when the directive has none of that name
     */
    public GraphQLArgument getArgument(String name) {
        return directive.getArgument(name);
    }

    public static DomainDirective newDomainDirective(Boolean searchable) {
        return new DomainDirective(
                NAME,
                DESCRIPTION,
                EnumSet.of(DirectiveLocation.OBJECT),
                List.of(SEARCHABLE_ARGUMENT.transform(builder -> builder.valueProgrammatic(searchable)))
        );
    }

    public static boolean hasDomainDirective(GraphQLDirectiveContainer container) {
        for (GraphQLDirective directive : container.getDirectives()) {
            if (directive.getName().equals(DomainDirective.NAME)) {
                return true;
            }
        }
        return false;
    }

    public Boolean isSearchable() {
        GraphQLArgument argument = getArgument(SEARCHABLE_NAME);
        Object value = SchemaElements.value(argument);
        if (value != null) {
            return (Boolean) value;
        }
        return (Boolean) SchemaElements.defaultValue(argument);
    }

    /**
     * The entity types of a schema that are marked as not searched: {@code @domain(searchable: false)}. A type whose
     * directive does not say is searched.
     *
     * @param schema the schema
     * @return the names of the types
     */
    public static Set<String> notSearched(GraphQLSchema schema) {
        Set<String> names = new TreeSet<>();
        for (GraphQLObjectType type : schema.getAllTypesAsList().stream()
                .filter(GraphQLObjectType.class::isInstance).map(GraphQLObjectType.class::cast).toList()) {
            if (hasDomainDirective(type) && !isSearched(type)) {
                names.add(type.getName());
            }
        }
        return names;
    }

    /**
     * Whether a type is searched: not when its directive says {@code searchable: false}.
     *
     * @param type the type
     * @return false when the directive of the type says so
     */
    public static boolean isSearched(GraphQLObjectType type) {
        GraphQLAppliedDirective domain = type.getAppliedDirective(NAME);
        GraphQLAppliedDirectiveArgument searchable = domain == null ? null : domain.getArgument(SEARCHABLE_NAME);
        return searchable == null || !Boolean.FALSE.equals(searchable.getValue());
    }

}
