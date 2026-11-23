package io.descoped.lds.graphql.schemas.visitors;

import graphql.schema.GraphQLAppliedDirective;
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

import static graphql.Scalars.GraphQLBoolean;
import static graphql.Scalars.GraphQLString;
import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import io.descoped.lds.graphql.directives.DomainDirective;

import static io.descoped.lds.graphql.directives.DomainDirective.hasDomainDirective;
import static io.descoped.lds.graphql.schemas.SchemaElements.newEnum;
import static io.descoped.lds.graphql.schemas.SchemaElements.newFieldDefinition;
import static io.descoped.lds.graphql.schemas.SchemaElements.newObject;
import static io.descoped.lds.graphql.schemas.SchemaElements.newUnionType;

public class AddSearchTypesVisitor extends GraphQLTypeVisitorStub {

    /**
     * The name of the directive that marks the search field of the query type. It is an applied directive:
     * graphql-java does not build a schema that has a directive on a field and no definition of it, and it does
     * not ask for the definition of an applied directive.
     */
    public static final String SEARCH_NAME = "search";

    private static final Logger log = LoggerFactory.getLogger(AddSearchTypesVisitor.class);
    private static final String TYPE_FILTERS = "TypeFilters";
    private static final String SEARCH_RESULT = "SearchResult";

    private final GraphQLObjectType.Builder query;
    private final Map<String, GraphQLNamedType> typeMap;
    private final GraphQLEnumType.Builder typeFilterEnum;
    private final GraphQLUnionType.Builder searchResultType;
    private int searched;

    public AddSearchTypesVisitor(Map<String, GraphQLNamedType> typeMap, GraphQLObjectType.Builder query) {
        this.typeMap = typeMap;
        this.query = query;
        // Create a union type for the search results and a Type filter for the query.
        this.typeFilterEnum = newEnum()
                .name(TYPE_FILTERS)
                .description("Defines valid type filters");

        this.searchResultType = newUnionType()
                .name(SEARCH_RESULT)
                .description("Union type for possible search results");
    }

    public AddSearchTypesVisitor(Map<String, GraphQLNamedType> typeMap) {
        this(typeMap, newObject().name("Query"));
    }

    public AddSearchTypesVisitor(Map<String, GraphQLNamedType> typeMap, GraphQLObjectType query) {
        this(typeMap, newObject(query));
    }

    @Override
    public TraversalControl visitGraphQLObjectType(GraphQLObjectType node, TraverserContext<GraphQLSchemaElement> context) {
        // a type the model marks as not searched is not searched, also for entries the index has from before
        if (hasDomainDirective(node) && DomainDirective.isSearched(node)) {
            typeFilterEnum.value(node.getName());
            searchResultType.possibleType(node);
            searched++;
        }
        return TraversalControl.CONTINUE;
    }

    public GraphQLType getQuery() {
        if (searched == 0) {
            // no type is searched: no search
            return query.build();
        }

        if (typeMap.containsKey(TYPE_FILTERS)) {
            throw new IllegalArgumentException("type map already contains " + TYPE_FILTERS);
        }
        GraphQLEnumType typeFilters = this.typeFilterEnum.build();
        typeMap.put(TYPE_FILTERS, typeFilters);

        if (typeMap.containsKey(SEARCH_RESULT)) {
            throw new IllegalArgumentException("type map already contains " + SEARCH_RESULT);
        }

        GraphQLUnionType searchResult = this.searchResultType.build();
        typeMap.put(SEARCH_RESULT, searchResult);
        if (log.isDebugEnabled()) {
            log.debug("Creating {} type {}", searchResult.getName(), GraphQLTypeUtil.simplePrint(searchResult));
        }

        // Create a new field in the query
        GraphQLFieldDefinition.Builder searchField = newFieldDefinition()
                .name("Search")
                .withAppliedDirective(GraphQLAppliedDirective.newDirective().name(SEARCH_NAME).build())
                .argument(newArgument()
                        .name("query")
                        .type(nonNull(GraphQLString)))
                .argument(newArgument().name("filter")
                        .type(list(typeFilters))
                        .build())
                .argument(newArgument().name("history")
                        .description("Every version up to the snapshot that matches, and not only the one valid at it")
                        .type(GraphQLBoolean)
                        .defaultValueProgrammatic(false)
                        .build())
                .type(nonNull(list(nonNull(searchResult))));

        query.field(searchField);

        return query.build();
    }
}
