package io.descoped.lds.graphql.schemas.visitors;

import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedOutputType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.schema.GraphQLUnionType;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.graphql.directives.LinkDirective;
import io.descoped.lds.graphql.directives.ReverseLinkDirective;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.descoped.lds.graphql.directives.DomainDirective.hasDomainDirective;
import static io.descoped.lds.graphql.directives.LinkDirective.NAME;
import static io.descoped.lds.graphql.directives.LinkDirective.newLinkDirective;
import static io.descoped.lds.graphql.schemas.SchemaElements.name;
import static io.descoped.lds.graphql.schemas.SchemaElements.newFieldDefinition;
import static io.descoped.lds.graphql.schemas.SchemaElements.newObject;

/**
 * Update the target of a link annotation with a corresponding reverseLink annotation.
 * <p>
 * The reverseLink contains a mappedBy attribute that is the path to the field of the link annotation.
 */
public class ReverseLinkBuildingVisitor extends GraphQLTypeVisitorStub {

    private static final Logger log = LoggerFactory.getLogger(ReverseLinkBuildingVisitor.class);
    private final Map<String, GraphQLNamedType> typeMap;

    public ReverseLinkBuildingVisitor(Map<String, GraphQLNamedType> typeMap) {
        this.typeMap = Objects.requireNonNull(typeMap);
    }

    @Override
    public TraversalControl visitGraphQLFieldDefinition(GraphQLFieldDefinition node, TraverserContext<GraphQLSchemaElement> context) {
        Optional<LinkDirective> linkDirective = getLinkDirective(node);
        if (linkDirective.isPresent()) {
            log.debug("Found @link annotation on {}", node.getName());
            Optional<String> reverseName = linkDirective.get().getReverseName();
            if (reverseName.isPresent()) {
                addReverseField(node, context, reverseName.get());
            }
        } else if (log.isDebugEnabled()) {
            log.trace("Ignoring field {} of {}", node.getName(), name(context.getParentContext().thisNode()));
        }

        return super.visitGraphQLFieldDefinition(node, context);
    }

    private GraphQLOutputType getObjectType(String name) {
        GraphQLNamedType graphQLType = typeMap.get(name);
        if (graphQLType == null) {
            throw new IllegalArgumentException(String.format("Could not find the type %s", name));
        }
        try {
            return (GraphQLOutputType) graphQLType;
        } catch (ClassCastException cce) {
            log.error("The type {} was not a GraphQLObjectType", name, cce);
            throw cce;
        }
    }

    public static Collection<String> computePath(GraphQLFieldDefinition node, TraverserContext<GraphQLSchemaElement> context) {
        List<GraphQLSchemaElement> types = new ArrayList<>();
        types.add(node.getType());
        types.add(node);
        types.addAll(context.getParentNodes());
        Deque<String> parts = new ArrayDeque<>();
        for (GraphQLSchemaElement type : types) {
            if (type instanceof GraphQLNonNull nonNull) {
                type = nonNull.getWrappedType();
            }
            // A connection is known by its name only; nothing in the model marks it.
            if (type instanceof GraphQLList
                    || name(type).endsWith("Connection")) {
                parts.addFirst("[]");
            } else {
                if (type instanceof GraphQLFieldDefinition) {
                    parts.addFirst(name(type));
                }
                if (type instanceof GraphQLObjectType objectType && !parts.isEmpty() && hasDomainDirective(objectType)) {
                    break;
                }
            }
        }
        parts.addFirst("$");
        return parts;
    }

    /**
     * Checks that the node has a link directive.
     */
    public static Optional<LinkDirective> getLinkDirective(GraphQLFieldDefinition node) {
        for (GraphQLDirective directive : node.getDirectives()) {
            if (directive.getName().equals(NAME)) {
                return Optional.of(newLinkDirective(directive));
            }
        }
        return Optional.empty();
    }

    private boolean addReverseField(GraphQLFieldDefinition node, TraverserContext<GraphQLSchemaElement> context, String reverseName) {

        String sourceName = GraphQLTypeUtil.unwrapAll((GraphQLType) context.getParentNode()).getName();
        String targetName = name(GraphQLTypeUtil.unwrapType(node.getType()).peek());

        GraphQLOutputType source = getObjectType(sourceName);
        GraphQLOutputType target = getObjectType(targetName);

        if (target instanceof GraphQLUnionType union) {
            boolean replaced = false;
            for (GraphQLNamedOutputType concreteType : union.getTypes()) {
                replaced = addReverseField(node, context, reverseName, source, getObjectType(concreteType.getName()));
            }
            return replaced;
        } else {
            return addReverseField(node, context, reverseName, source, target);
        }
    }

    private boolean addReverseField(GraphQLFieldDefinition node, TraverserContext<GraphQLSchemaElement> context, String reverseName, GraphQLOutputType source, GraphQLOutputType target) {
        // Copy the definition
        GraphQLObjectType.Builder nodeCopy = newObject((GraphQLObjectType) target);

        // Add reverse link
        GraphQLOutputType sourceType = GraphQLNonNull.nonNull(
                GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(name(source)))));

        Collection<String> parts = computePath(node, context);
        JsonNavigationPath mappedBy = JsonNavigationPath.from(parts);

        // Annotate reverse link
        GraphQLFieldDefinition fieldDefinition = newFieldDefinition()
                .name(reverseName)
                .type(sourceType)
                .withDirective(ReverseLinkDirective.newReverseLinkDirective(true, mappedBy.serialize()).getDirective())
                .build();

        if (log.isDebugEnabled()) {
            log.debug("Adding reverseLink {} from target {} to source {}", fieldDefinition, name(target), name(source));
        }
        nodeCopy.field(fieldDefinition);

        GraphQLNamedType oldObject = typeMap.put(name(target), nodeCopy.build());
        if (oldObject != null && Objects.equals(oldObject, target)) {
            log.debug("Existing object {} is not equal to visited object {}", target, oldObject);
        }
        return true;
    }
}
