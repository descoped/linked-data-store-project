package io.descoped.lds.graphql.directives;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLNonNull;
import io.descoped.lds.graphql.schemas.SchemaElements;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import static graphql.Scalars.GraphQLBoolean;
import static graphql.Scalars.GraphQLString;
import static graphql.introspection.Introspection.DirectiveLocation;

/**
 * The reverse link directive. A directive of graphql-java can no longer be extended, so this class holds one.
 */
public class ReverseLinkDirective {

    public static final String NAME = "reverseLink";
    public static final String DESCRIPTION = "Defines a field as a reverse link to another managed domain";
    public static final String PAGINATION_NAME = "pagination";
    public static final String MAPPED_BY_NAME = "mappedBy";

    public static final GraphQLArgument PAGINATION_ARGUMENT = GraphQLArgument.newArgument()
            .name(PAGINATION_NAME)
            .type(GraphQLBoolean)
            .defaultValueProgrammatic(true)
            .build();

    public static final GraphQLArgument MAPPED_BY_ARGUMENT = GraphQLArgument.newArgument()
            .name(MAPPED_BY_NAME)
            .type(GraphQLString)
            .build();

    public static final ReverseLinkDirective INSTANCE = new ReverseLinkDirective(
            NAME,
            DESCRIPTION,
            EnumSet.of(DirectiveLocation.FIELD_DEFINITION),
            List.of(PAGINATION_ARGUMENT, MAPPED_BY_ARGUMENT)
    );

    public static final GraphQLDirective REVERSE_LINK_DIRECTIVE = SchemaElements.newDirective()
            .name(NAME)
            .argument(GraphQLArgument.newArgument().name(MAPPED_BY_NAME).type(GraphQLNonNull.nonNull(GraphQLString)).build())
            .argument(GraphQLArgument.newArgument().name(PAGINATION_NAME).type(GraphQLBoolean).build())
            .validLocation(DirectiveLocation.FIELD_DEFINITION)
            .build();

    private final GraphQLDirective directive;

    private ReverseLinkDirective(String name, String description, EnumSet<DirectiveLocation> locations, List<GraphQLArgument> arguments) {
        this.directive = SchemaElements.newDirective()
                .name(name)
                .description(description)
                .validLocations(locations.toArray(new DirectiveLocation[0]))
                .replaceArguments(arguments)
                .build();
    }

    /**
     * The directive of graphql-java this reverse link directive stands for: what is put on an element of a
     * schema, or given to a schema as the definition.
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

    public static ReverseLinkDirective newReverseLinkDirective(Boolean pagination, String mappedBy) {
        Objects.requireNonNull(mappedBy);
        Objects.requireNonNull(mappedBy);
        return new ReverseLinkDirective(
                NAME,
                DESCRIPTION,
                EnumSet.of(DirectiveLocation.FIELD_DEFINITION),
                List.of(
                        MAPPED_BY_ARGUMENT.transform(builder -> builder.valueProgrammatic(mappedBy)),
                        PAGINATION_ARGUMENT.transform(builder -> builder.valueProgrammatic(pagination)))
        );
    }

    public ReverseLinkDirective newReverseLinkDirective(String mappedBy) {
        return new ReverseLinkDirective(
                NAME,
                DESCRIPTION,
                EnumSet.of(DirectiveLocation.FIELD_DEFINITION),
                List.of(MAPPED_BY_ARGUMENT.transform(builder -> builder.valueProgrammatic(mappedBy)))
        );
    }

    public String getMappedBy() {
        GraphQLArgument argument = getArgument(MAPPED_BY_NAME);
        return (String) SchemaElements.value(argument);
    }

    /**
     * Whether the link is paged: the value the directive gives, or the default of the argument, true.
     *
     * @return whether the link is paged
     */
    public Boolean getPagination() {
        GraphQLArgument pagination = getArgument(PAGINATION_NAME);
        if (pagination == null || SchemaElements.value(pagination) == null) {
            return (Boolean) SchemaElements.defaultValue(PAGINATION_ARGUMENT);
        }
        return (Boolean) SchemaElements.value(pagination);
    }
}
