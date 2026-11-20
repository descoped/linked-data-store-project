package io.descoped.lds.graphql.schemas;

import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedSchemaElement;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.GraphQLTypeResolvingVisitor;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static graphql.Scalars.GraphQLString;
import static graphql.introspection.Introspection.DirectiveLocation.FIELD_DEFINITION;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLObjectType.newObject;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The walk over the elements of a schema, as the visitors of LDS need it: the walk of graphql-java 12.
 */
class TypeTraverserTest {

    private static final TypeTraverser TRAVERSER = new TypeTraverser();

    private static final GraphQLObjectType B = newObject().name("B")
            .field(newFieldDefinition().name("name").type(GraphQLString))
            .build();

    /**
     * Writes down what it is shown, and stops the walk at the element it is told to stop at.
     */
    private static class Recorder extends GraphQLTypeVisitorStub {

        final List<String> seen = new ArrayList<>();
        private final Predicate<String> quitAt;

        Recorder() {
            this(event -> false);
        }

        Recorder(Predicate<String> quitAt) {
            this.quitAt = quitAt;
        }

        private TraversalControl record(String event) {
            seen.add(event);
            return quitAt.test(event) ? TraversalControl.QUIT : TraversalControl.CONTINUE;
        }

        private static String text(GraphQLSchemaElement element) {
            if (element instanceof GraphQLType type) {
                return GraphQLTypeUtil.simplePrint(type);
            }
            return ((GraphQLNamedSchemaElement) element).getName();
        }

        @Override
        public TraversalControl visitGraphQLObjectType(GraphQLObjectType node, TraverserContext<GraphQLSchemaElement> context) {
            return record("object " + text(node));
        }

        @Override
        public TraversalControl visitGraphQLFieldDefinition(GraphQLFieldDefinition node, TraverserContext<GraphQLSchemaElement> context) {
            return record("field " + text(node));
        }

        @Override
        public TraversalControl visitGraphQLList(GraphQLList node, TraverserContext<GraphQLSchemaElement> context) {
            return record("list " + text(node));
        }

        @Override
        public TraversalControl visitGraphQLNonNull(GraphQLNonNull node, TraverserContext<GraphQLSchemaElement> context) {
            return record("non-null " + text(node));
        }

        @Override
        public TraversalControl visitGraphQLScalarType(GraphQLScalarType node, TraverserContext<GraphQLSchemaElement> context) {
            return record("scalar " + text(node));
        }

        @Override
        public TraversalControl visitGraphQLDirective(GraphQLDirective node, TraverserContext<GraphQLSchemaElement> context) {
            return record("directive " + text(node));
        }

        @Override
        public TraversalControl visitGraphQLAppliedDirective(GraphQLAppliedDirective node, TraverserContext<GraphQLSchemaElement> context) {
            return record("applied directive " + text(node));
        }

        @Override
        public TraversalControl visitBackRef(TraverserContext<GraphQLSchemaElement> context) {
            return record("back reference " + text(context.thisNode()));
        }
    }

    @Test
    void anElementIsShownBeforeItsChildrenAndTheChildrenInTheirOrder() {
        GraphQLObjectType a = newObject().name("A")
                .field(newFieldDefinition().name("first").type(B))
                .field(newFieldDefinition().name("second").type(nonNull(list(GraphQLString))))
                .build();
        Recorder recorder = new Recorder();

        TRAVERSER.depthFirst(recorder, a);

        assertThat(recorder.seen).containsExactly(
                "object A",
                "field first",
                "object B",
                "field name",
                "scalar String",
                "field second",
                "non-null [String]!",
                "list [String]",
                "back reference String");
    }

    @Test
    void severalRootsAreWalkedInTheOrderGivenAndAnElementIsEnteredOnce() {
        GraphQLObjectType a = newObject().name("A")
                .field(newFieldDefinition().name("b").type(B))
                .build();
        Recorder recorder = new Recorder();

        TRAVERSER.depthFirst(recorder, List.of(B, a));

        assertThat(recorder.seen).containsExactly(
                "object B",
                "field name",
                "scalar String",
                "object A",
                "field b",
                "back reference B");
    }

    @Test
    void aListOfATypeAlreadyEnteredIsABackReferenceAndIsNotWalked() {
        GraphQLObjectType a = newObject().name("A")
                .field(newFieldDefinition().name("first").type(list(B)))
                .field(newFieldDefinition().name("second").type(list(B)))
                .build();
        Recorder recorder = new Recorder();

        TRAVERSER.depthFirst(recorder, a);

        // The second list is another object than the first. It is not entered, and B under it is not looked at:
        // there is no back reference to B.
        assertThat(recorder.seen).containsExactly(
                "object A",
                "field first",
                "list [B]",
                "object B",
                "field name",
                "scalar String",
                "field second",
                "back reference [B]");
    }

    @Test
    void aNonNullOfATypeAlreadyEnteredIsABackReferenceAndIsNotWalked() {
        GraphQLObjectType a = newObject().name("A")
                .field(newFieldDefinition().name("first").type(nonNull(GraphQLString)))
                .field(newFieldDefinition().name("second").type(nonNull(GraphQLString)))
                .build();
        Recorder recorder = new Recorder();

        TRAVERSER.depthFirst(recorder, a);

        assertThat(recorder.seen).containsExactly(
                "object A",
                "field first",
                "non-null String!",
                "scalar String",
                "field second",
                "back reference String!");
    }

    @Test
    void wrappersThatDifferAreEachEntered() {
        GraphQLObjectType a = newObject().name("A")
                .field(newFieldDefinition().name("f1").type(list(B)))
                .field(newFieldDefinition().name("f2").type(nonNull(list(B))))
                .field(newFieldDefinition().name("f3").type(list(nonNull(B))))
                .field(newFieldDefinition().name("f4").type(list(GraphQLString)))
                .build();
        Recorder recorder = new Recorder();

        TRAVERSER.depthFirst(recorder, a);

        assertThat(recorder.seen).containsExactly(
                "object A",
                "field f1",
                "list [B]",
                "object B",
                "field name",
                "scalar String",
                "field f2",
                "non-null [B]!",
                "back reference [B]",
                "field f3",
                "list [B!]",
                "non-null B!",
                "back reference B",
                "field f4",
                "list [String]",
                "back reference String");
    }

    @Test
    void aWrapperWhoseReferenceWasResolvedSinceItWasEnteredDoesNotCount() {
        GraphQLTypeReference reference = GraphQLTypeReference.typeRef("B");
        GraphQLList first = list(reference);
        GraphQLList second = list(reference);
        GraphQLObjectType a = newObject().name("A")
                .field(newFieldDefinition().name("first").type(first))
                .field(newFieldDefinition().name("second").type(second))
                .build();
        List<String> lists = new ArrayList<>();
        Map<String, GraphQLNamedType> types = Map.of("B", B);

        // Resolves the reference under each list it is shown, as GraphQLSchemaBuilder does.
        TRAVERSER.depthFirst(new GraphQLTypeResolvingVisitor(types) {
            @Override
            public TraversalControl visitGraphQLList(GraphQLList node, TraverserContext<GraphQLSchemaElement> context) {
                lists.add(node == first ? "first" : "second");
                return super.visitGraphQLList(node, context);
            }
        }, a);

        // When the second list is met the first no longer wraps the reference, so the second is entered too, and
        // its reference is resolved.
        assertThat(lists).containsExactly("first", "second");
        assertThat(first.getWrappedType()).isSameAs(B);
        assertThat(second.getWrappedType()).isSameAs(B);
    }

    @Test
    void quitStopsTheWalk() {
        GraphQLObjectType a = newObject().name("A")
                .field(newFieldDefinition().name("first").type(B))
                .field(newFieldDefinition().name("second").type(GraphQLString))
                .build();
        Recorder recorder = new Recorder("object B"::equals);

        TRAVERSER.depthFirst(recorder, a);

        assertThat(recorder.seen).containsExactly("object A", "field first", "object B");
    }

    @Test
    void quitAtABackReferenceToAWrapperStopsTheWalk() {
        GraphQLObjectType a = newObject().name("A")
                .field(newFieldDefinition().name("first").type(list(B)))
                .field(newFieldDefinition().name("second").type(list(B)))
                .field(newFieldDefinition().name("third").type(GraphQLString))
                .build();
        Recorder recorder = new Recorder("back reference [B]"::equals);

        TRAVERSER.depthFirst(recorder, a);

        assertThat(recorder.seen)
                .endsWith("field second", "back reference [B]")
                .doesNotContain("field third");
    }

    @Test
    void theDirectivesOfAnElementAreWalkedAndItsAppliedDirectivesAreNot() {
        GraphQLDirective directive = GraphQLDirective.newDirective().name("marked").validLocation(FIELD_DEFINITION).build();
        GraphQLFieldDefinition field = newFieldDefinition().name("f").type(GraphQLString)
                .withDirective(directive)
                .withAppliedDirective(GraphQLAppliedDirective.newDirective().name("applied").build())
                .build();
        assertThat(field.getChildren()).anyMatch(GraphQLAppliedDirective.class::isInstance);
        Recorder recorder = new Recorder();

        TRAVERSER.depthFirst(recorder, field);

        assertThat(recorder.seen).containsExactly("field f", "scalar String", "directive marked");
    }
}
