package io.descoped.lds.graphql.directives;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import io.descoped.lds.graphql.schemas.SchemaElements;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static graphql.Scalars.GraphQLBoolean;
import static graphql.Scalars.GraphQLString;
import static graphql.introspection.Introspection.DirectiveLocation;

/**
 * The link directive. A directive of graphql-java can no longer be extended, so this class holds one.
 */
public class LinkDirective {

    public static final String NAME = "link";
    public static final String DESCRIPTION = "Defines a field as a link to another managed domain";
    public static final String PAGINATION_NAME = "pagination";
    public static final String REVERSE_NAME_NAME = "reverseName";
    public static final GraphQLArgument PAGINATION_ARGUMENT = GraphQLArgument.newArgument()
            .name(PAGINATION_NAME)
            .type(GraphQLBoolean)
            .defaultValueProgrammatic(true)
            .build();
    public static final GraphQLArgument REVERSE_NAME_ARGUMENT = GraphQLArgument.newArgument()
            .name(REVERSE_NAME_NAME)
            .type(GraphQLString)
            .build();

    public static final LinkDirective INSTANCE = new LinkDirective(
            NAME,
            DESCRIPTION,
            EnumSet.of(DirectiveLocation.FIELD_DEFINITION),
            List.of(PAGINATION_ARGUMENT, REVERSE_NAME_ARGUMENT)
    );

    private final GraphQLDirective directive;

    private LinkDirective(String name, String description, EnumSet<DirectiveLocation> locations, List<GraphQLArgument> arguments) {
        this.directive = SchemaElements.newDirective()
                .name(name)
                .description(description)
                .validLocations(locations.toArray(new DirectiveLocation[0]))
                .replaceArguments(arguments)
                .build();
    }

    /**
     * The directive of graphql-java this link directive stands for: what is put on an element of a schema, or
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

    public static LinkDirective newLinkDirective() {
        return newLinkDirective(true);
    }

    public static LinkDirective newLinkDirective(Boolean pagination) {
        return new LinkDirective(
                NAME,
                DESCRIPTION,
                EnumSet.of(DirectiveLocation.FIELD_DEFINITION),
                List.of(
                        PAGINATION_ARGUMENT.transform(builder -> builder.valueProgrammatic(pagination))
                )
        );
    }

    public static LinkDirective newLinkDirective(GraphQLDirective directive) {
        Optional<Boolean> pagination = Optional.ofNullable(directive.getArgument(PAGINATION_NAME))
                .map(arg -> (Boolean) SchemaElements.value(arg));
        Optional<String> reverseName = Optional.ofNullable(directive.getArgument(REVERSE_NAME_NAME))
                .map(arg -> (String) SchemaElements.value(arg));
        if (reverseName.isPresent()) {
            return newLinkDirective(pagination.orElse(true), reverseName.get());
        } else {
            return newLinkDirective(pagination.orElse(true));
        }
    }

    public static LinkDirective newLinkDirective(Boolean pagination, String reverseName) {
        return new LinkDirective(
                NAME,
                DESCRIPTION,
                EnumSet.of(DirectiveLocation.FIELD_DEFINITION),
                List.of(
                        PAGINATION_ARGUMENT.transform(builder -> builder.valueProgrammatic(pagination)),
                        REVERSE_NAME_ARGUMENT.transform(builder -> builder.valueProgrammatic(reverseName))
                )
        );
    }

    public Optional<String> getReverseName() {
        GraphQLArgument argument = getArgument(REVERSE_NAME_NAME);
        if (argument == null || SchemaElements.value(argument) == null) {
            return Optional.empty();
        } else {
            return Optional.of((String) SchemaElements.value(argument));
        }
    }

    public Boolean getPagination() {
        GraphQLArgument pagination = getArgument(PAGINATION_NAME);
        if (SchemaElements.value(pagination) == null) {
            return (Boolean) SchemaElements.defaultValue(pagination);
        }
        return (Boolean) SchemaElements.defaultValue(pagination);
    }
}
