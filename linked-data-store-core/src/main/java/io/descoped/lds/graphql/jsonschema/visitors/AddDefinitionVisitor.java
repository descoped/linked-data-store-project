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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static io.descoped.lds.graphql.directives.DomainDirective.hasDomainDirective;
import static io.descoped.lds.graphql.schemas.SchemaElements.name;


public class AddDefinitionVisitor extends GraphQLTypeVisitorStub {
    private static final TypeTraverser TRAVERSER = new TypeTraverser();
    private static final String DEFS = "$defs";
    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";
    private static final String ITEMS = "items";
    private static final String OBJECT = "object";
    private static final String STRING = "string";
    private static final String FORMAT = "format";
    private static final String ARRAY = "array";
    private static final String LINK_PROPERTY_PREFIX = "_link_property_";

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
            return visitGraphQLDomainType(node);
        } else {
            ObjectNode definitionElements = object();
            definitionElements.put("type", OBJECT);
            definitionElements.putObject(PROPERTIES);
            definitionElements.putArray(REQUIRED);

            ObjectNode definitions = (ObjectNode) jsonElements.get(DEFS);
            definitions.set(node.getName(), definitionElements);

            AddDefinitionVisitor addReferencedDefinition = new AddDefinitionVisitor(jsonElements, node.getName());
            List<GraphQLFieldDefinition> graphQLFieldDefinitions = node.getFieldDefinitions();
            TRAVERSER.depthFirst(addReferencedDefinition, graphQLFieldDefinitions);

            return TraversalControl.ABORT;
        }
    }

    @Override
    public TraversalControl visitGraphQLFieldDefinition(GraphQLFieldDefinition node, TraverserContext<GraphQLSchemaElement> context) {
        ObjectNode definitionElements = definition();
        ObjectNode definitionProperties = (ObjectNode) definitionElements.get(PROPERTIES);

        ObjectNode propertyElements = object();

        GraphQLOutputType type = node.getType();
        visitedFieldName = node.getName();

        lastVisitedNodeType = GraphQLFieldDefinition.class.getName();

        // add non-null type is the required list
        if (GraphQLTypeUtil.isNonNull(type)) {
            ArrayNode defRequiredProperties = (ArrayNode) definitionElements.get(REQUIRED);
            defRequiredProperties.add(node.getName());
        }

        // handle scalar types(string, date-time)
        putScalarType(propertyElements, GraphQLTypeUtil.unwrapOne(node.getType()));

        putOrRemove(propertyElements, "description", node.getDescription());
        propertyElements.put("displayName", "");
        definitionProperties.set(node.getName(), propertyElements);

        // handle link types
        for (GraphQLDirective directive : node.getDirectives()) {
            if (directive.getName().equals(LinkDirective.NAME) && visitLinkField(node, definitionElements)) {
                return TraversalControl.ABORT;
            }
        }

        return visitGraphQLType(node.getType(), context);
    }

    /*
     * The JSON Schema type of a scalar of GraphQL: a value that GraphQL cannot give back (an Int out of the range of
     * 32 bits, a Date that is not a date) is refused when it is written. A scalar LDS does not know gets no type.
     */
    private static void putScalarType(ObjectNode propertyElements, GraphQLType graphQLType) {
        if (graphQLType instanceof GraphQLScalarType scalarType) {
            switch (scalarType.getName()) {
                case "String" -> propertyElements.put("type", STRING);
                case "Boolean" -> propertyElements.put("type", "boolean");
                case "Int" -> propertyElements.put("type", "integer")
                        .put("minimum", Integer.MIN_VALUE)
                        .put("maximum", Integer.MAX_VALUE);
                case "Long" -> propertyElements.put("type", "integer")
                        .put("minimum", Long.MIN_VALUE)
                        .put("maximum", Long.MAX_VALUE);
                case "Float" -> propertyElements.put("type", "number");
                case "DateTime" -> propertyElements.put("type", STRING).put(FORMAT, "date-time");
                case "Date" -> propertyElements.put("type", STRING).put(FORMAT, "date");
                case "Time" -> propertyElements.put("type", STRING).put(FORMAT, "time");
                default -> {
                    // A scalar named string in other letters is a text, as LDS always read it; any other gets no type.
                    if (scalarType.getName().equalsIgnoreCase(STRING)) {
                        propertyElements.put("type", STRING);
                    }
                }
            }
        }
    }

    /*
     * The properties of a field with the link directive. Returns false when the type of the field is not one a link is written
     * for; the field is then walked as any other.
     */
    private boolean visitLinkField(GraphQLFieldDefinition node, ObjectNode definitionElements) {
        GraphQLType subType;
        if (GraphQLTypeUtil.isNonNull(node.getType())) {
            subType = GraphQLTypeUtil.unwrapNonNull(node.getType());
        } else {
            subType = node.getType();
        }
        if (subType instanceof GraphQLUnionType) {
            ObjectNode properties = (ObjectNode) definitionElements.get(PROPERTIES).get(visitedFieldName);
            properties.put("type", STRING);
            visitUnionLinkProperty(node.getType());
            return true;
        } else if (subType instanceof GraphQLList listType) {
            GraphQLType wrappedType = listType.getWrappedType();
            if (wrappedType instanceof GraphQLUnionType) {
                ObjectNode properties = (ObjectNode) definitionElements.get(PROPERTIES).get(visitedFieldName);
                ObjectNode itemList = object();
                properties.put("type", ARRAY);
                itemList.put("type", STRING);
                properties.set(ITEMS, itemList);
                visitUnionLinkProperty(wrappedType);
                return true;
            } else if (wrappedType instanceof GraphQLObjectType) {
                visitListLinkProperty(wrappedType);
                return true;
            }
        } else if (subType instanceof GraphQLObjectType objectType) {
            visitGraphQLDomainType(objectType);
            return true;
        }
        return false;
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
        ObjectNode definitionElements = definition();
        ObjectNode definitionProperties = (ObjectNode) definitionElements.get(PROPERTIES);
        ObjectNode fieldProperties = (ObjectNode) definitionProperties.get(visitedFieldName);

        fieldProperties.put("type", STRING);
        ArrayNode enumValues = fieldProperties.putArray("enum");
        node.getValues().forEach(value -> enumValues.add(value.getName()));

        lastVisitedNodeType = GraphQLEnumType.class.getName();

        return visitGraphQLType(node, context);
    }

    @Override
    public TraversalControl visitGraphQLList(GraphQLList node, TraverserContext<GraphQLSchemaElement> context) {
        ObjectNode definitionElements = definition();
        ObjectNode definitionProperties = (ObjectNode) definitionElements.get(PROPERTIES);

        ObjectNode fieldProperties = (ObjectNode) definitionProperties.get(visitedFieldName);
        fieldProperties.put("type", ARRAY);

        ObjectNode itemList = object();

        GraphQLType type;

        if (GraphQLTypeUtil.isNonNull(node.getWrappedType())) {
            type = ((GraphQLNonNull) node.getWrappedType()).getWrappedType();
        } else {
            type = node.getWrappedType();
        }

        if (type instanceof GraphQLObjectType objectType) {
            return visitListOfObjects(node, objectType, fieldProperties, itemList, context);
        } else if (type instanceof GraphQLScalarType scalarType) {
            putScalarType(itemList, scalarType);
            fieldProperties.set(ITEMS, itemList);
            lastVisitedNodeType = GraphQLList.class.getName();

            return visitGraphQLType(node, context);
        }

        return TraversalControl.CONTINUE;
    }

    /*
     * A list of a type without @domain refers to its definition. A list of an entity type is an embedded object
     * when a field of the entity type has @domain or is of a type with @domain, and a list of links otherwise.
     */
    private TraversalControl visitListOfObjects(GraphQLList node, GraphQLObjectType type, ObjectNode fieldProperties,
                                                ObjectNode itemList, TraverserContext<GraphQLSchemaElement> context) {
        if (!hasDomainDirective(type)) {
            itemList.put("$ref", "#/$defs/" + name(type));
            fieldProperties.set(ITEMS, itemList);
            lastVisitedNodeType = GraphQLList.class.getName();

            return visitGraphQLType(node, context);
        }
        if (hasDomainField(type)) {
            fieldProperties.put("type", OBJECT);
            visitEmbeddedType(node);
            return TraversalControl.ABORT;
        }
        visitListLinkProperty(type);
        return TraversalControl.ABORT;
    }

    private static boolean hasDomainField(GraphQLObjectType type) {
        for (GraphQLFieldDefinition def : type.getFieldDefinitions()) {
            GraphQLUnmodifiedType graphQLUnmodifiedType = GraphQLTypeUtil.unwrapAll(def.getType());
            if (hasDomainDirective(def) || hasDomainDirective((GraphQLDirectiveContainer) graphQLUnmodifiedType)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public TraversalControl visitGraphQLScalarType(GraphQLScalarType node, TraverserContext<GraphQLSchemaElement> context) {
        return visitGraphQLType(node, context);
    }

    @Override
    public TraversalControl visitGraphQLNonNull(GraphQLNonNull node, TraverserContext<GraphQLSchemaElement> context) {
        ObjectNode definitionElements = definition();
        ArrayNode defRequiredProperties = (ArrayNode) definitionElements.get(REQUIRED);

        if (!lastVisitedNodeType.equalsIgnoreCase("graphql.schema.GraphQLList")
                && !contains(defRequiredProperties, visitedFieldName)) {
            defRequiredProperties.add(visitedFieldName);
        }

        GraphQLType graphQLType = GraphQLTypeUtil.unwrapOne(node);

        if (GraphQLTypeUtil.isList(GraphQLTypeUtil.unwrapOne(node))) {
            visitGraphQLList((GraphQLList) graphQLType, context);
        }
        return visitGraphQLType(node.getWrappedType(), context);
    }

    private TraversalControl visitGraphQLDomainType(GraphQLObjectType node) {
        ObjectNode definitionElements = definition();
        ObjectNode definitionProperties = (ObjectNode) definitionElements.get(PROPERTIES);

        ObjectNode fieldProperties = (ObjectNode) definitionProperties.get(visitedFieldName);
        fieldProperties.put("type", STRING);
        fieldProperties.put("displayName", "");
        putOrRemove(fieldProperties, "description", node.getDescription());

        ObjectNode properties = object();
        ObjectNode propertyElements = object();
        ObjectNode fieldObjects = object();

        propertyElements.put("type", "null");
        properties.set(node.getName(), propertyElements);
        fieldObjects.set(PROPERTIES, properties);
        fieldObjects.put("type", OBJECT);

        definitionProperties.set(LINK_PROPERTY_PREFIX + visitedFieldName, fieldObjects);

        return TraversalControl.ABORT;
    }

    private TraversalControl visitListLinkProperty(GraphQLType node) {
        ObjectNode definitionElements = definition();
        ObjectNode definitionsProperties = (ObjectNode) definitionElements.get(PROPERTIES);
        ObjectNode definitionProperties = (ObjectNode) definitionsProperties.get(visitedFieldName);

        ObjectNode fieldObjects = object();
        ObjectNode properties = object();
        ObjectNode propertyElements = object();
        ObjectNode itemList = object();

        definitionProperties.put("type", ARRAY);
        itemList.put("type", STRING);
        definitionProperties.set(ITEMS, itemList);

        definitionsProperties.set(visitedFieldName, definitionProperties);

        propertyElements.put("type", "null");

        if (GraphQLTypeUtil.isWrapped(node) && GraphQLTypeUtil.isList(node)) {
            properties.set(name(((GraphQLList) node).getWrappedType()), propertyElements);
        } else if (GraphQLTypeUtil.isWrapped(node) && GraphQLTypeUtil.isNonNull(node)) {
            properties.set(name(((GraphQLNonNull) node).getWrappedType()), propertyElements);
        } else {
            properties.set(name(node), propertyElements);
        }

        fieldObjects.set(PROPERTIES, properties);
        fieldObjects.put("type", OBJECT);

        definitionsProperties.set(LINK_PROPERTY_PREFIX + visitedFieldName, fieldObjects);

        return TraversalControl.ABORT;
    }

    private TraversalControl visitUnionLinkProperty(GraphQLType node) {
        ObjectNode definitionElements = definition();
        ObjectNode definitionsProperties = (ObjectNode) definitionElements.get(PROPERTIES);

        ObjectNode fieldObjects = object();
        ObjectNode properties = object();
        ObjectNode propertyElements = object();

        for (GraphQLNamedOutputType objectType : ((GraphQLUnionType) GraphQLTypeUtil.unwrapNonNull(node)).getTypes()) {
            propertyElements.put("type", "null");
            properties.set(objectType.getName(), propertyElements);
        }

        fieldObjects.set(PROPERTIES, properties);
        fieldObjects.put("type", OBJECT);

        definitionsProperties.set(LINK_PROPERTY_PREFIX + visitedFieldName, fieldObjects);

        return TraversalControl.ABORT;
    }

    private TraversalControl visitEmbeddedType(GraphQLList node) {
        ObjectNode definitionElements = definition();
        ObjectNode definitionsProperties = (ObjectNode) definitionElements.get(PROPERTIES);
        ObjectNode definitionProperties = (ObjectNode) definitionsProperties.get(visitedFieldName);
        ObjectNode embeddedProperties = object();

        if (GraphQLTypeUtil.isList(node)) {
            GraphQLType graphQLType = GraphQLTypeUtil.unwrapOne(node);
            if (graphQLType instanceof GraphQLObjectType objectType) {
                objectType.getFieldDefinitions().forEach(fieldDef -> {
                    if (GraphQLTypeUtil.isList(fieldDef.getType())) {
                        ObjectNode propertiesValues = object();
                        ObjectNode itemList = object();

                        propertiesValues.put("type", ARRAY);
                        itemList.put("type", STRING);
                        propertiesValues.set(ITEMS, itemList);

                        embeddedProperties.set(fieldDef.getName(), propertiesValues);

                        ObjectNode linkedProperties = object();
                        ObjectNode linkedObjects = object();

                        linkedProperties.put("type", OBJECT);
                        propertiesValues = object();
                        linkedObjects.put("type", "null");
                        propertiesValues.set(name(((GraphQLList) fieldDef.getType()).getWrappedType()), linkedObjects);
                        linkedProperties.set(PROPERTIES, propertiesValues);

                        embeddedProperties.set(LINK_PROPERTY_PREFIX + fieldDef.getName(), linkedProperties);
                    }
                });
            }

        }
        definitionProperties.set(PROPERTIES, embeddedProperties);

        return TraversalControl.ABORT;
    }

    /*
     * The definition being written, under $defs.
     */
    private ObjectNode definition() {
        return (ObjectNode) jsonElements.get(DEFS).get(visitedDefinition);
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
