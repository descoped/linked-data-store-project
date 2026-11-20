package io.descoped.lds.graphql.directives;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLDirectiveContainer;
import io.descoped.lds.graphql.schemas.SchemaElements;

import java.util.EnumSet;
import java.util.List;

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

}
