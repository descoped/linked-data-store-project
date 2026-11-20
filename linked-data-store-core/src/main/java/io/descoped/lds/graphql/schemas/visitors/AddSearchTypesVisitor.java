package io.descoped.lds.graphql.schemas.visitors;

import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.schema.GraphQLUnionType;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static graphql.Scalars.GraphQLString;
import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static io.descoped.lds.graphql.directives.DomainDirective.hasDomainDirective;
import static io.descoped.lds.graphql.schemas.SchemaElements.newEnum;
import static io.descoped.lds.graphql.schemas.SchemaElements.newFieldDefinition;
import static io.descoped.lds.graphql.schemas.SchemaElements.newObject;
import static io.descoped.lds.graphql.schemas.SchemaElements.newUnionType;
import static io.descoped.lds.graphql.schemas.SchemaElements.value;

public class AddSearchTypesVisitor extends GraphQLTypeVisitorStub {

    /**
     * The name of the directive that marks the search field of the query type. It is an applied directive:
     * graphql-java does not build a schema that has a directive on a field and no definition of it, and it does
     * not ask for the definition of an applied directive.
     */
    public static final String SEARCH_NAME = "search";

    private static final Logger log = LoggerFactory.getLogger(AddConnectionVisitor.class);

    private final GraphQLObjectType.Builder query;
    private final Map<String, GraphQLNamedType> typeMap;
    private final GraphQLEnumType.Builder typeFilterEnum;
    private final GraphQLUnionType.Builder searchResultType;

    public AddSearchTypesVisitor(Map<String, GraphQLNamedType> typeMap, GraphQLObjectType.Builder query) {
        this.typeMap = typeMap;
        this.query = query;
        // Create a union type for the search results and a Type filter for the query.
        this.typeFilterEnum = newEnum()
                .name("TypeFilters")
                .description("Defines valid type filters");

        this.searchResultType = newUnionType()
                .name("SearchResult")
                .description("Union type for possible search results");
    }

    public AddSearchTypesVisitor(Map<String, GraphQLNamedType> typeMap) {
        this(typeMap, newObject().name("Query"));
    }

    public AddSearchTypesVisitor(Map<String, GraphQLNamedType> typeMap, GraphQLObjectType query) {
        this(typeMap, newObject(query));
    }

    @Deprecated
    private static boolean isSearchable(GraphQLObjectType node) {
        for (GraphQLDirective directive : node.getDirectives()) {
            if ("domain".equals(directive.getName())) {
                GraphQLArgument searchable = directive.getArgument("searchable");
                return searchable != null && value(searchable) != null && value(searchable).equals(true);
            }
        }
        return false;
    }

    @Override
    public TraversalControl visitGraphQLObjectType(GraphQLObjectType node, TraverserContext<GraphQLSchemaElement> context) {
        if (hasDomainDirective(node)) {
            typeFilterEnum.value(node.getName());
            searchResultType.possibleType(node);
        }
        return TraversalControl.CONTINUE;
    }

    public GraphQLType getQuery() {

        if (typeMap.containsKey("TypeFilters")) {
            throw new IllegalArgumentException("type map already contains TypeFilters");
        }
        GraphQLEnumType typeFilterEnum = this.typeFilterEnum.build();
        typeMap.put("TypeFilters", typeFilterEnum);

        if (typeMap.containsKey("SearchResult")) {
            throw new IllegalArgumentException("type map already contains SearchResult");
        }

        GraphQLUnionType searchResultType = this.searchResultType.build();
        typeMap.put("SearchResult", searchResultType);
        log.debug("Creating {} type {}", searchResultType.getName(), GraphQLTypeUtil.simplePrint(searchResultType));

        // Create a new field in the query
        GraphQLFieldDefinition.Builder searchField = newFieldDefinition()
                .name("Search")
                .withAppliedDirective(GraphQLAppliedDirective.newDirective().name(SEARCH_NAME).build())
                .argument(newArgument()
                        .name("query")
                        .type(nonNull(GraphQLString)))
                .argument(newArgument().name("filter")
                        .type(list(typeFilterEnum))
                        .build())
                .type(nonNull(list(nonNull(searchResultType))));

        query.field(searchField);

        return query.build();
    }
}
