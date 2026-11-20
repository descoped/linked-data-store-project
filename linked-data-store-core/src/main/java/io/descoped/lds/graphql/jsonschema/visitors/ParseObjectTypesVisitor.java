
package io.descoped.lds.graphql.jsonschema.visitors;

import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import io.descoped.lds.core.schema.JsonSchemaBuilder;
import io.descoped.lds.graphql.schemas.TypeTraverser;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.stream.Stream;

import static io.descoped.lds.graphql.directives.DomainDirective.hasDomainDirective;

public class ParseObjectTypesVisitor extends GraphQLTypeVisitorStub {
    private static final TypeTraverser TRAVERSER = new TypeTraverser();
    private final Map<String, ObjectNode> jsonMap;

    public ParseObjectTypesVisitor(Map<String, ObjectNode> jsonMap) {
        this.jsonMap = jsonMap;
    }

    @Override
    public TraversalControl visitGraphQLObjectType(GraphQLObjectType node, TraverserContext<GraphQLSchemaElement> context) {
        ObjectNode jsonElements = JsonNodeFactory.instance.objectNode();
        ObjectNode jsonDefinitions = JsonNodeFactory.instance.objectNode();
        ObjectNode definitionValues = JsonNodeFactory.instance.objectNode();

        if (hasDomainDirective(node)) {
            definitionValues.put("type", "object");
            definitionValues.putObject("properties");
            definitionValues.putArray("required");

            String description = node.getDescription();
            // Using the first line of the description as display name if possible.
            if (description != null) {
                String displayName = Stream.of(description.split(System.lineSeparator()))
                        .findFirst().map(String::strip).orElse(description);
                definitionValues.put("displayName", displayName);

                definitionValues.put("description", description.strip());
            }


            jsonDefinitions.set(node.getName(), definitionValues);
            jsonElements.put("$schema", JsonSchemaBuilder.DIALECT);
            jsonElements.put("$ref", "#/$defs/" + node.getName());
            jsonElements.set("$defs", jsonDefinitions);

            String visitedDefinition = node.getName();

            AddDefinitionVisitor addJsonDefinitionVisitor = new AddDefinitionVisitor(jsonElements, visitedDefinition);
            TRAVERSER.depthFirst(addJsonDefinitionVisitor, node.getFieldDefinitions());
            jsonMap.put(node.getName(), jsonElements);

        }
        return TraversalControl.ABORT;
    }
}

