package io.descoped.lds.graphql.schemas.visitors;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import io.descoped.lds.graphql.directives.DomainDirective;
import io.descoped.lds.graphql.schemas.TypeTraverser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static graphql.Scalars.GraphQLString;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The visitors that make the Query type: a field to read one and a field to list every domain, and the Search
 * field over all of them.
 */
class QueryVisitorsTest {

    private static final TypeTraverser TRAVERSER = new TypeTraverser();

    private static final GraphQLObjectType EXISTING_QUERY = newObject().name("Query")
            .field(newFieldDefinition().name("x").type(GraphQLString))
            .build();

    private static GraphQLObjectType type(String name, boolean domain) {
        GraphQLObjectType.Builder type = newObject().name(name).field(newFieldDefinition().name("name").type(GraphQLString));
        if (domain) {
            type.withDirective(DomainDirective.INSTANCE.getDirective());
        }
        return type.build();
    }

    private static final List<GraphQLObjectType> TYPES = List.of(type("Dog", true), type("Address", false), type("Cat", true));

    @Test
    void everyDomainGetsAFieldToReadOneAndAFieldToListThemInAQueryTypeThatIsThere() {
        QueryBuildingVisitor visitor = new QueryBuildingVisitor(EXISTING_QUERY);

        TRAVERSER.depthFirst(visitor, TYPES);

        GraphQLObjectType query = visitor.getQuery();
        assertThat(query.getName()).isEqualTo("Query");
        assertThat(query.getFieldDefinitions()).extracting(GraphQLFieldDefinition::getName)
                .containsExactly("Cat", "CatById", "Dog", "DogById", "x");
        assertThat(GraphQLTypeUtil.simplePrint(query.getFieldDefinition("Cat").getType())).isEqualTo("[Cat!]!");
        // nullable: an id that no document has is null, and the rest of the answer is kept
        assertThat(GraphQLTypeUtil.simplePrint(query.getFieldDefinition("CatById").getType())).isEqualTo("Cat");
        assertThat(GraphQLTypeUtil.simplePrint(query.getFieldDefinition("CatById").getArgument("id").getType())).isEqualTo("ID!");
    }

    @Test
    void theSearchFieldIsOverEveryDomainInANewQueryType() {
        Map<String, GraphQLNamedType> typeMap = new LinkedHashMap<>();
        AddSearchTypesVisitor visitor = new AddSearchTypesVisitor(typeMap);

        TRAVERSER.depthFirst(visitor, TYPES);

        assertSearch((GraphQLObjectType) visitor.getQuery(), typeMap, "Search");
    }

    @Test
    void theSearchFieldIsAddedToAQueryTypeThatIsThere() {
        Map<String, GraphQLNamedType> typeMap = new LinkedHashMap<>();
        AddSearchTypesVisitor visitor = new AddSearchTypesVisitor(typeMap, EXISTING_QUERY);

        TRAVERSER.depthFirst(visitor, TYPES);

        assertSearch((GraphQLObjectType) visitor.getQuery(), typeMap, "Search", "x");
    }

    private static void assertSearch(GraphQLObjectType query, Map<String, GraphQLNamedType> typeMap, String... fields) {
        assertThat(query.getName()).isEqualTo("Query");
        assertThat(query.getFieldDefinitions()).extracting(GraphQLFieldDefinition::getName).containsExactly(fields);
        GraphQLFieldDefinition search = query.getFieldDefinition("Search");
        assertThat(GraphQLTypeUtil.simplePrint(search.getType())).isEqualTo("[SearchResult!]!");
        assertThat(GraphQLTypeUtil.simplePrint(search.getArgument("query").getType())).isEqualTo("String!");
        assertThat(GraphQLTypeUtil.simplePrint(search.getArgument("filter").getType())).isEqualTo("[TypeFilters]");
        assertThat(search.getAppliedDirective(AddSearchTypesVisitor.SEARCH_NAME)).isNotNull();
        assertThat(typeMap.keySet()).containsExactly("TypeFilters", "SearchResult");
        assertThat(((GraphQLEnumType) typeMap.get("TypeFilters")).getValues()).extracting(GraphQLEnumValueDefinition::getName)
                .containsExactly("Cat", "Dog");
        assertThat(((GraphQLUnionType) typeMap.get("SearchResult")).getTypes()).extracting(GraphQLNamedType::getName)
                .containsExactly("Cat", "Dog");
    }

    @Test
    void theSearchTypesAreMadeOnce() {
        AddSearchTypesVisitor visitor = new AddSearchTypesVisitor(new LinkedHashMap<>());
        TRAVERSER.depthFirst(visitor, TYPES);
        visitor.getQuery();

        assertThatThrownBy(visitor::getQuery)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("type map already contains TypeFilters");
    }
}
