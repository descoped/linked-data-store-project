package io.descoped.lds.graphql.schemas;

import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLModifiedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLTypeVisitor;
import graphql.util.TraversalControl;
import graphql.util.Traverser;
import graphql.util.TraverserContext;
import graphql.util.TraverserResult;
import graphql.util.TraverserVisitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks schema elements depth first and shows each of them to a visitor.
 * <p>
 * The visitors of LDS were written for the {@code TypeTraverser} of graphql-java 12, which later versions do not
 * have. What they produce (the names of reverse links, the paths they are mapped by, the JSON Schema of a type)
 * follows from the order of that walk and from where it stops, so this class walks as that one did. Two things
 * differ from the {@code SchemaTraverser} of later versions:
 * <ul>
 * <li>a list or non-null type that wraps the same type as one already entered is not entered again: the visitor
 * is told of it as a back reference, and what it wraps is not walked from there;</li>
 * <li>the directives of an element are among its children, the applied directives that later versions hold
 * beside them are not.</li>
 * </ul>
 */
public class TypeTraverser {

    private static List<GraphQLSchemaElement> children(GraphQLSchemaElement element) {
        List<GraphQLSchemaElement> children = new ArrayList<>();
        for (GraphQLSchemaElement child : element.getChildren()) {
            if (!(child instanceof GraphQLAppliedDirective)) {
                children.add(child);
            }
        }
        return children;
    }

    /**
     * Walks one element and everything under it, depth first, and shows each element to the visitor before its
     * children.
     *
     * @param graphQLTypeVisitor the visitor
     * @param root               the element to start from
     * @return what the walk ended with
     */
    public TraverserResult depthFirst(GraphQLTypeVisitor graphQLTypeVisitor, GraphQLSchemaElement root) {
        return depthFirst(graphQLTypeVisitor, Collections.singletonList(root));
    }

    /**
     * Walks several elements and everything under them, depth first and in the order given, and shows each
     * element to the visitor before its children. An element that was entered before, under any of the roots, is
     * not entered again: the visitor is told of it with {@code visitBackRef}.
     *
     * @param graphQLTypeVisitor the visitor
     * @param roots              the elements to start from
     * @return what the walk ended with
     */
    public TraverserResult depthFirst(GraphQLTypeVisitor graphQLTypeVisitor, Collection<? extends GraphQLSchemaElement> roots) {
        Traverser<GraphQLSchemaElement> traverser = Traverser.depthFirst(TypeTraverser::children);
        return traverser.traverse(roots, new TraverserDelegateVisitor(graphQLTypeVisitor));
    }

    private static class TraverserDelegateVisitor implements TraverserVisitor<GraphQLSchemaElement> {

        private final GraphQLTypeVisitor delegate;
        private final Map<Wrapped, List<GraphQLSchemaElement>> entered = new HashMap<>();

        TraverserDelegateVisitor(GraphQLTypeVisitor delegate) {
            this.delegate = delegate;
        }

        @Override
        public TraversalControl enter(TraverserContext<GraphQLSchemaElement> context) {
            GraphQLSchemaElement node = context.thisNode();
            if (!isWrapper(node)) {
                return node.accept(context, delegate);
            }
            if (isEntered(node)) {
                // A back reference continues or quits. It does not go on to the children.
                TraversalControl control = delegate.visitBackRef(context);
                return control == TraversalControl.QUIT ? TraversalControl.QUIT : TraversalControl.ABORT;
            }
            TraversalControl control = node.accept(context, delegate);
            entered.computeIfAbsent(Wrapped.of(node), wrapped -> new ArrayList<>()).add(node);
            return control;
        }

        /**
         * A wrapper is entered when one that was entered before wrapped the same type then and still does. A
         * wrapper whose type reference was resolved since it was entered no longer counts.
         */
        private boolean isEntered(GraphQLSchemaElement node) {
            Wrapped wrapped = Wrapped.of(node);
            for (GraphQLSchemaElement before : entered.getOrDefault(wrapped, Collections.emptyList())) {
                if (wrapped.equals(Wrapped.of(before))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public TraversalControl leave(TraverserContext<GraphQLSchemaElement> context) {
            return TraversalControl.CONTINUE;
        }

        @Override
        public TraversalControl backRef(TraverserContext<GraphQLSchemaElement> context) {
            return delegate.visitBackRef(context);
        }
    }

    private static boolean isWrapper(GraphQLSchemaElement element) {
        return element instanceof GraphQLList || element instanceof GraphQLNonNull;
    }

    /**
     * What a list or non-null type is made of: the wrappers from the outside in, and the type inside them.
     */
    private record Wrapped(List<Class<?>> wrappers, GraphQLSchemaElement type) {

        static Wrapped of(GraphQLSchemaElement element) {
            List<Class<?>> wrappers = new ArrayList<>();
            GraphQLSchemaElement type = element;
            while (isWrapper(type)) {
                wrappers.add(type.getClass());
                type = ((GraphQLModifiedType) type).getWrappedType();
            }
            return new Wrapped(wrappers, type);
        }
    }
}
