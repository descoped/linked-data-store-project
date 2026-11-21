package io.descoped.lds.graphql.schemas.visitors;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static graphql.Scalars.GraphQLBoolean;
import static graphql.Scalars.GraphQLInt;
import static graphql.Scalars.GraphQLString;
import static graphql.schema.GraphQLTypeUtil.isList;
import static graphql.schema.GraphQLTypeUtil.simplePrint;
import static graphql.schema.GraphQLTypeUtil.unwrapNonNull;
import static graphql.schema.GraphQLTypeUtil.unwrapType;
import static io.descoped.lds.graphql.schemas.SchemaElements.name;
import static io.descoped.lds.graphql.schemas.SchemaElements.newFieldDefinition;
import static io.descoped.lds.graphql.schemas.SchemaElements.newInterface;
import static io.descoped.lds.graphql.schemas.SchemaElements.newObject;
import static io.descoped.lds.graphql.schemas.SchemaElements.value;
import static io.descoped.lds.graphql.schemas.visitors.AddSearchTypesVisitor.SEARCH_NAME;

/**
 * Add a @pagination annotation to all fields marked with @link or @reverseLink.
 */
public class AddConnectionVisitor extends GraphQLTypeVisitorStub {

    private static final Logger log = LoggerFactory.getLogger(AddConnectionVisitor.class);
    private static final String PAGE_INFO = "PageInfo";
    private final Map<String, GraphQLNamedType> typeMap;

    public AddConnectionVisitor(Map<String, GraphQLNamedType> typeMap) {
        this.typeMap = Objects.requireNonNull(typeMap);
    }

    public static boolean hasLinkWithPagination(GraphQLFieldDefinition node) {
        for (GraphQLDirective directive : node.getDirectives()) {
            if ("link".equals(directive.getName()) || "reverseLink".equals(directive.getName())) {
                GraphQLArgument pagination = directive.getArgument("pagination");
                // A link without the argument pagination, or without a value for it, is paged.
                if (pagination == null) {
                    return true;
                }
                Object value = value(pagination);
                if (value == null) {
                    return true;
                }
                return (Boolean) value;
            }
            if ("search".equals(directive.getName())) {
                return true;
            }
        }
        return node.getAppliedDirective(SEARCH_NAME) != null;
    }

    @Override
    public TraversalControl visitGraphQLInterfaceType(GraphQLInterfaceType node, TraverserContext<GraphQLSchemaElement> context) {
        List<GraphQLFieldDefinition> fieldsWithPagination = findFieldsWithPagination(node);
        if (fieldsWithPagination.isEmpty()) {
            log.trace("No fields marked with pagination in {}", node);
            return TraversalControl.CONTINUE;
        }

        log.debug("Transforming {} fields to pagination fields in {}", fieldsWithPagination.size(), node.getName());

        GraphQLInterfaceType.Builder newObject = newInterface(node);
        for (GraphQLFieldDefinition fieldDefinition : fieldsWithPagination) {
            newObject.field(createConnectionField(fieldDefinition));
        }

        typeMap.put(node.getName(), newObject.build());

        return TraversalControl.CONTINUE;
    }

    private List<GraphQLFieldDefinition> findFieldsWithPagination(GraphQLFieldsContainer node) {
        List<GraphQLFieldDefinition> fieldsWithPagination = new ArrayList<>();
        for (GraphQLFieldDefinition fieldDefinition : node.getFieldDefinitions()) {
            if (hasLinkWithPagination(fieldDefinition) && isList(unwrapNonNull(fieldDefinition.getType()))) {
                if (log.isTraceEnabled()) {
                    log.trace("Found link from {} to {} on field {}", node.getName(),
                            simplePrint(fieldDefinition.getType()), fieldDefinition.getName());
                }
                fieldsWithPagination.add(fieldDefinition);
            }
        }
        return fieldsWithPagination;
    }

    @Override
    public TraversalControl visitGraphQLObjectType(GraphQLObjectType node, TraverserContext<GraphQLSchemaElement> context) {
        List<GraphQLFieldDefinition> fieldsWithPagination = findFieldsWithPagination(node);
        if (fieldsWithPagination.isEmpty()) {
            log.trace("No fields marked with pagination in {}", node);
            return TraversalControl.CONTINUE;
        }

        log.debug("Transforming {} fields to pagination fields in {}", fieldsWithPagination.size(), node.getName());

        GraphQLObjectType.Builder newObject = newObject(
                (GraphQLObjectType) typeMap.get(node.getName()));
        for (GraphQLFieldDefinition fieldDefinition : fieldsWithPagination) {
            newObject.field(createConnectionField(fieldDefinition));
        }

        GraphQLNamedType oldObject = typeMap.put(node.getName(), newObject.build());
        if (oldObject != null && Objects.equals(oldObject, node)) {
            log.debug("Existing object {} is not equal to visited object {}", node, oldObject);
        }

        return TraversalControl.CONTINUE;
    }

    private GraphQLFieldDefinition createConnectionField(GraphQLFieldDefinition field) {
        GraphQLType to = unwrapType(field.getType()).pop();
        GraphQLFieldDefinition.Builder newFieldDefinition = newFieldDefinition(field);
        return newFieldDefinition
                .argument(GraphQLArgument.newArgument().name("first").type(GraphQLInt).build())
                .argument(GraphQLArgument.newArgument().name("after").type(GraphQLString).build())
                .argument(GraphQLArgument.newArgument().name("last").type(GraphQLInt).build())
                .argument(GraphQLArgument.newArgument().name("before").type(GraphQLString).build())
                .type((GraphQLOutputType) createConnectionType(to))
                .build();
    }

    private GraphQLType createConnectionType(GraphQLType to) {
        // We do not use Relay Connection name convertion to reduce the amount of
        // types.
        String connectionTypeName = name(to) + "Connection";
        if (!typeMap.containsKey(connectionTypeName)) {
            GraphQLFieldDefinition.Builder pageInfoField = newFieldDefinition()
                    .type(GraphQLNonNull.nonNull(createPageInfoType())).name("pageInfo");

            GraphQLFieldDefinition.Builder edgesField = newFieldDefinition()
                    .type(GraphQLNonNull.nonNull(
                            GraphQLList.list(GraphQLNonNull.nonNull(
                                    createEdgeType(to))
                            ))
                    )
                    .name("edges");

            GraphQLObjectType.Builder connectionType = newObject()
                    .name(connectionTypeName)
                    .field(edgesField)
                    .field(pageInfoField);

            typeMap.put(connectionTypeName, connectionType.build());
        }
        return typeMap.get(connectionTypeName);
    }

    private GraphQLType createEdgeType(GraphQLType to) {
        // We do not use Relay Connection name conversion to reduce the amount of
        // types.
        String edgeTypeName = name(to) + "Edge";
        return typeMap.computeIfAbsent(edgeTypeName, edgeName -> {

            GraphQLFieldDefinition.Builder nodeField = newFieldDefinition()
                    .type(GraphQLNonNull.nonNull(to)).name("node");

            GraphQLFieldDefinition.Builder cursorField = newFieldDefinition()
                    .type(GraphQLNonNull.nonNull(GraphQLString)).name("cursor");

            GraphQLObjectType.Builder edgeType = newObject()
                    .name(edgeName)
                    .field(cursorField)
                    .field(nodeField);

            return edgeType.build();
        });
    }

    private GraphQLType createPageInfoType() {
        if (!typeMap.containsKey(PAGE_INFO)) {
            GraphQLObjectType pageInfoType = newObject()
                    .name(PAGE_INFO)
                    .field(newFieldDefinition().name("hasNextPage").type(GraphQLNonNull.nonNull(GraphQLBoolean)))
                    .field(newFieldDefinition().name("hasPreviousPage").type(GraphQLNonNull.nonNull(GraphQLBoolean)))
                    .build();
            typeMap.put(PAGE_INFO, pageInfoType);
        }
        return typeMap.get(PAGE_INFO);
    }
}
