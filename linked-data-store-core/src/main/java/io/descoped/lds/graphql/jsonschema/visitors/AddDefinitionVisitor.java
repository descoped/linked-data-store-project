package io.descoped.lds.graphql.jsonschema.visitors;

import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.schema.GraphQLUnionType;
import graphql.schema.GraphQLUnmodifiedType;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import io.descoped.lds.graphql.directives.LinkDirective;
import io.descoped.lds.graphql.schemas.TypeTraverser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static io.descoped.lds.graphql.directives.DomainDirective.hasDomainDirective;
import static io.descoped.lds.graphql.schemas.SchemaElements.name;


public class AddDefinitionVisitor extends GraphQLTypeVisitorStub {
    private static final Logger LOG = LoggerFactory.getLogger(AddDefinitionVisitor.class);
    private static final TypeTraverser TRAVERSER = new TypeTraverser();

    private final ObjectNode jsonElements;
    private String visitedFieldName;
    private final String visitedDefinition;
    private String lastVisitedNodeType;


    public AddDefinitionVisitor(ObjectNode jsonElements, String visitedDefinition) {
        this.jsonElements = jsonElements;
        this.visitedDefinition = visitedDefinition;
    }

    @Override
    public TraversalControl visitGraphQLObjectType(GraphQLObjectType node, TraverserContext<GraphQLSchemaElement> context) {

        List<GraphQLDirective> directives = node.getDirectives();
        boolean isDomainType = directives.stream().map(GraphQLDirective::getName).anyMatch("domain"::equalsIgnoreCase);

        // Add objects in reference list for non-domain types.
        if (isDomainType) {
            return visitGraphQLDomainType(node, context);
        } else {
            ObjectNode definitionElements = object();
            definitionElements.put("type", "object");
            definitionElements.putObject("properties");
            definitionElements.putArray("required");

            ObjectNode definitions = (ObjectNode) jsonElements.get("$defs");
            definitions.set(node.getName(), definitionElements);

            AddDefinitionVisitor addReferencedDefinition = new AddDefinitionVisitor(jsonElements, node.getName());
            List<GraphQLFieldDefinition> graphQLFieldDefinitions = node.getFieldDefinitions();
            TRAVERSER.depthFirst(addReferencedDefinition, graphQLFieldDefinitions);

            return TraversalControl.ABORT;
        }
    }

    @Override
    public TraversalControl visitGraphQLFieldDefinition(GraphQLFieldDefinition node, TraverserContext<GraphQLSchemaElement> context) {
        ObjectNode definitionElements = (ObjectNode) jsonElements.get("$defs").get(visitedDefinition);
        ObjectNode definitionProperties = (ObjectNode) definitionElements.get("properties");

        ObjectNode propertyElements = object();

        GraphQLOutputType type = node.getType();
        visitedFieldName = node.getName();

        lastVisitedNodeType = GraphQLFieldDefinition.class.getName();

        // add non-null type is the required list
        if (GraphQLTypeUtil.isNonNull(type)) {
            ArrayNode defRequiredProperties = (ArrayNode) definitionElements.get("required");
            defRequiredProperties.add(node.getName());
        }

        // handle scalar types(string, date-time)
        GraphQLType graphQLType = GraphQLTypeUtil.unwrapOne(node.getType());
        if (graphQLType instanceof GraphQLScalarType scalarType) {
            String scalarName = scalarType.getName();
            if (scalarName.equalsIgnoreCase("string")) {
                propertyElements.put("type", "string");
            }
            switch (scalarName) {
                case "String":
                    propertyElements.put("type", "string");
                    break;
                case "Boolean":
                    propertyElements.put("type", "boolean");
                    break;
                case "DateTime":
                    propertyElements.put("type", "string");
                    propertyElements.put("format", "date-time");
                    break;
            }
        }

        putOrRemove(propertyElements, "description", node.getDescription());
        propertyElements.put("displayName", "");
        definitionProperties.set(node.getName(), propertyElements);

        // handle link types
        for (GraphQLDirective directive : node.getDirectives()) {
            if (directive.getName().equals(LinkDirective.NAME)) {
                GraphQLType subType;
                if (GraphQLTypeUtil.isNonNull(node.getType())) {
                    subType = GraphQLTypeUtil.unwrapNonNull(node.getType());
                } else {
                    subType = node.getType();
                }
                if (subType instanceof GraphQLUnionType) {
                    ObjectNode properties = (ObjectNode) definitionElements.get("properties").get(visitedFieldName);
                    properties.put("type", "string");
                    visitUnionLinkProperty(node.getType(), context);
                    return TraversalControl.ABORT;
                } else if (subType instanceof GraphQLList) {
                    GraphQLType wrappedType = ((GraphQLList) subType).getWrappedType();
                    if (wrappedType instanceof GraphQLUnionType) {
                        ObjectNode properties = (ObjectNode) definitionElements.get("properties").get(visitedFieldName);
                        ObjectNode itemList = object();
                        properties.put("type", "array");
                        itemList.put("type", "string");
                        properties.set("items", itemList);
                        visitUnionLinkProperty(wrappedType, context);
                        return TraversalControl.ABORT;
                    } else if (wrappedType instanceof GraphQLObjectType) {
                        visitListLinkProperty(wrappedType, context);
                        return TraversalControl.ABORT;
                    }
                } else if (subType instanceof GraphQLObjectType) {
                    visitGraphQLDomainType((GraphQLObjectType) subType, context);
                    return TraversalControl.ABORT;
                }
            }
        }

        return visitGraphQLType(node.getType(), context);
    }

    @Override
    public TraversalControl visitGraphQLDirective(GraphQLDirective node, TraverserContext<GraphQLSchemaElement> context) {
        // The reason of @deprecated is a non-null string in graphql-java now, and a non-null type under a field
        // marks the field as required. It was a nullable string when this was written, so it is not looked at.
        if ("deprecated".equals(node.getName())) {
            return TraversalControl.ABORT;
        }
        return super.visitGraphQLDirective(node, context);
    }

    @Override
    public TraversalControl visitGraphQLEnumType(GraphQLEnumType node, TraverserContext<GraphQLSchemaElement> context) {
        ObjectNode definitionElements = (ObjectNode) jsonElements.get("$defs").get(visitedDefinition);
        ObjectNode definitionProperties = (ObjectNode) definitionElements.get("properties");
        ObjectNode fieldProperties = (ObjectNode) definitionProperties.get(visitedFieldName);

        fieldProperties.put("type", "string");
        ArrayNode enumValues = fieldProperties.putArray("enum");
        node.getValues().forEach(value -> enumValues.add(value.getName()));

        lastVisitedNodeType = GraphQLEnumType.class.getName();

        return visitGraphQLType(node, context);
    }

    @Override
    public TraversalControl visitGraphQLList(GraphQLList node, TraverserContext<GraphQLSchemaElement> context) {
        ObjectNode definitionElements = (ObjectNode) jsonElements.get("$defs").get(visitedDefinition);
        ObjectNode definitionProperties = (ObjectNode) definitionElements.get("properties");

        ObjectNode fieldProperties = (ObjectNode) definitionProperties.get(visitedFieldName);
        fieldProperties.put("type", "array");

        ObjectNode itemList = object();

        GraphQLType type;
        boolean isEmbeddedType = false;

        if (GraphQLTypeUtil.isNonNull(node.getWrappedType())) {
            type = ((GraphQLNonNull) node.getWrappedType()).getWrappedType();
        } else {
            type = node.getWrappedType();
        }

        if (type instanceof GraphQLObjectType) {
            if (!hasDomainDirective((GraphQLDirectiveContainer) type)) {
                itemList.put("$ref", "#/$defs/" + name(type));
                fieldProperties.set("items", itemList);
                lastVisitedNodeType = GraphQLList.class.getName();

                return visitGraphQLType(node, context);
            } else {
                for (GraphQLFieldDefinition def : ((GraphQLObjectType) type).getFieldDefinitions()) {
                    GraphQLUnmodifiedType graphQLUnmodifiedType = GraphQLTypeUtil.unwrapAll(def.getType());
                    if (hasDomainDirective(def) || hasDomainDirective((GraphQLDirectiveContainer) graphQLUnmodifiedType)) {
                        isEmbeddedType = true;
                    }
                }
                if (isEmbeddedType) {
                    fieldProperties.put("type", "object");
                    visitEmbeddedType(node, context);
                    return TraversalControl.ABORT;
                }

                if (hasDomainDirective((GraphQLObjectType) type)) {
                    if (type instanceof GraphQLUnionType) {
                        visitUnionLinkProperty(type, context);
                        return TraversalControl.ABORT;
                    } else {
                        visitListLinkProperty(type, context);
                        return TraversalControl.ABORT;
                    }
                }
            }
        } else if (type instanceof GraphQLScalarType scalarType) {
            if (scalarType.getName().equalsIgnoreCase("string")) {
                itemList.put("type", "string");
            }
            fieldProperties.set("items", itemList);
            lastVisitedNodeType = GraphQLList.class.getName();

            return visitGraphQLType(node, context);
        }

        return TraversalControl.CONTINUE;
    }


    @Override
    public TraversalControl visitGraphQLScalarType(GraphQLScalarType node, TraverserContext<GraphQLSchemaElement> context) {
        return visitGraphQLType(node, context);
    }

    @Override
    public TraversalControl visitGraphQLNonNull(GraphQLNonNull node, TraverserContext<GraphQLSchemaElement> context) {
        ObjectNode definitionElements = (ObjectNode) jsonElements.get("$defs").get(visitedDefinition);
        ArrayNode defRequiredProperties = (ArrayNode) definitionElements.get("required");

        if (!lastVisitedNodeType.equalsIgnoreCase("graphql.schema.GraphQLList")) {
            if (!contains(defRequiredProperties, visitedFieldName)) {
                defRequiredProperties.add(visitedFieldName);
            }
        }

        GraphQLType graphQLType = GraphQLTypeUtil.unwrapOne(node);

        if (GraphQLTypeUtil.isList(GraphQLTypeUtil.unwrapOne(node))) {
            visitGraphQLList((GraphQLList) graphQLType, context);
        }
        return visitGraphQLType(node.getWrappedType(), context);
    }

    private TraversalControl visitGraphQLDomainType(GraphQLObjectType node, TraverserContext<GraphQLSchemaElement> context) {
        ObjectNode definitionElements = (ObjectNode) jsonElements.get("$defs").get(visitedDefinition);
        ObjectNode definitionProperties = (ObjectNode) definitionElements.get("properties");

        ObjectNode fieldProperties = (ObjectNode) definitionProperties.get(visitedFieldName);
        fieldProperties.put("type", "string");
        fieldProperties.put("displayName", "");
        putOrRemove(fieldProperties, "description", node.getDescription());

        ObjectNode properties = object();
        ObjectNode propertyElements = object();
        ObjectNode fieldObjects = object();

        propertyElements.put("type", "null");
        properties.set(node.getName(), propertyElements);
        fieldObjects.set("properties", properties);
        fieldObjects.put("type", "object");

        definitionProperties.set("_link_property_" + visitedFieldName, fieldObjects);

        return TraversalControl.ABORT;
    }

    private TraversalControl visitListLinkProperty(GraphQLType node, TraverserContext<GraphQLSchemaElement> context) {
        ObjectNode definitionElements = (ObjectNode) jsonElements.get("$defs").get(visitedDefinition);
        ObjectNode definitionsProperties = (ObjectNode) definitionElements.get("properties");
        ObjectNode definitionProperties = (ObjectNode) definitionsProperties.get(visitedFieldName);

        ObjectNode fieldObjects = object();
        ObjectNode properties = object();
        ObjectNode propertyElements = object();
        ObjectNode itemList = object();

        definitionProperties.put("type", "array");
        itemList.put("type", "string");
        definitionProperties.set("items", itemList);

        definitionsProperties.set(visitedFieldName, definitionProperties);

        propertyElements.put("type", "null");

        if (GraphQLTypeUtil.isWrapped(node) && GraphQLTypeUtil.isList(node)) {
            properties.set(name(((GraphQLList) node).getWrappedType()), propertyElements);
        } else if (GraphQLTypeUtil.isWrapped(node) && GraphQLTypeUtil.isNonNull(node)) {
            properties.set(name(((GraphQLNonNull) node).getWrappedType()), propertyElements);
        } else {
            properties.set(name(node), propertyElements);
        }

        fieldObjects.set("properties", properties);
        fieldObjects.put("type", "object");

        definitionsProperties.set("_link_property_" + visitedFieldName, fieldObjects);

        return TraversalControl.ABORT;
    }

    private TraversalControl visitUnionLinkProperty(GraphQLType node, TraverserContext<GraphQLSchemaElement> context) {
        ObjectNode definitionElements = (ObjectNode) jsonElements.get("$defs").get(visitedDefinition);
        ObjectNode definitionsProperties = (ObjectNode) definitionElements.get("properties");

        ObjectNode fieldObjects = object();
        ObjectNode properties = object();
        ObjectNode propertyElements = object();

        for (GraphQLNamedOutputType objectType : ((GraphQLUnionType) GraphQLTypeUtil.unwrapNonNull(node)).getTypes()) {
            propertyElements.put("type", "null");
            properties.set(objectType.getName(), propertyElements);
        }

        fieldObjects.set("properties", properties);
        fieldObjects.put("type", "object");

        definitionsProperties.set("_link_property_" + visitedFieldName, fieldObjects);

        return TraversalControl.ABORT;
    }

    private TraversalControl visitEmbeddedType(GraphQLList node, TraverserContext<GraphQLSchemaElement> context) {
        ObjectNode definitionElements = (ObjectNode) jsonElements.get("$defs").get(visitedDefinition);
        ObjectNode definitionsProperties = (ObjectNode) definitionElements.get("properties");
        ObjectNode definitionProperties = (ObjectNode) definitionsProperties.get(visitedFieldName);
        ObjectNode embeddedProperties = object();

        if (GraphQLTypeUtil.isList(node)) {
            GraphQLType graphQLType = GraphQLTypeUtil.unwrapOne(node);
            if (graphQLType instanceof GraphQLObjectType) {
                ((GraphQLObjectType) graphQLType).getFieldDefinitions().forEach(fieldDef -> {
                    if (GraphQLTypeUtil.isList(fieldDef.getType())) {
                        ObjectNode propertiesValues = object();
                        ObjectNode itemList = object();

                        propertiesValues.put("type", "array");
                        itemList.put("type", "string");
                        propertiesValues.set("items", itemList);

                        embeddedProperties.set(fieldDef.getName(), propertiesValues);

                        ObjectNode linkedProperties = object();
                        ObjectNode linkedObjects = object();

                        linkedProperties.put("type", "object");
                        propertiesValues = object();
                        linkedObjects.put("type", "null");
                        propertiesValues.set(name(((GraphQLList) fieldDef.getType()).getWrappedType()), linkedObjects);
                        linkedProperties.set("properties", propertiesValues);

                        embeddedProperties.set("_link_property_" + fieldDef.getName(), linkedProperties);
                    }
                });
            }

        }
        definitionProperties.set("properties", embeddedProperties);

        return TraversalControl.ABORT;
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static boolean contains(ArrayNode names, String name) {
        for (JsonNode element : names) {
            if (element.isString() && name.equals(element.stringValue())) {
                return true;
            }
        }
        return false;
    }

    /*
     * A text that is not there takes away what the object has under the name.
     */
    private static void putOrRemove(ObjectNode object, String name, String text) {
        if (text == null) {
            object.remove(name);
        } else {
            object.put(name, text);
        }
    }
}
