package io.descoped.lds.graphql.schemas;

import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedSchemaElement;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.GraphQLTypeResolvingVisitor;
import graphql.schema.GraphQLUnionType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static graphql.Scalars.GraphQLBoolean;
import static graphql.Scalars.GraphQLInt;
import static graphql.Scalars.GraphQLString;
import static graphql.introspection.Introspection.DirectiveLocation.FIELD_DEFINITION;
import static graphql.introspection.Introspection.DirectiveLocation.OBJECT;
import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The builders and readers of {@link SchemaElements} keep what LDS relies on: the order of the parts of an
 * element, a copy that starts from what the element holds now, a directive that takes the place of the one with
 * the same name, and names and values read as they always were.
 */
class SchemaElementsTest {

    private static final TypeTraverser TRAVERSER = new TypeTraverser();

    private static GraphQLFieldDefinition field(String name) {
        return SchemaElements.newFieldDefinition().name(name).type(GraphQLString).build();
    }

    private static GraphQLDirective directive(String name) {
        return SchemaElements.newDirective().name(name).validLocations(OBJECT, FIELD_DEFINITION).build();
    }

    private static GraphQLObjectType object(String name) {
        return SchemaElements.newObject().name(name).field(field("name")).build();
    }

    @Test
    void thePartsOfAnElementAreInTheOrderOfTheirNamesAndItsDirectivesAsGiven() {
        GraphQLInterfaceType zeta = GraphQLInterfaceType.newInterface().name("Zeta").field(field("name")).build();
        GraphQLInterfaceType alpha = GraphQLInterfaceType.newInterface().name("Alpha").field(field("name")).build();
        GraphQLObjectType object = SchemaElements.newObject().name("Thing")
                .field(field("name")).field(field("b")).field(field("a"))
                .withInterface(zeta).withInterface(alpha)
                .withDirective(directive("zz")).withDirective(directive("aa"))
                .build();
        GraphQLFieldDefinition withArguments = SchemaElements.newFieldDefinition().name("f").type(GraphQLString)
                .argument(newArgument().name("y").type(GraphQLInt))
                .argument(newArgument().name("x").type(GraphQLInt))
                .build();
        GraphQLEnumType anEnum = SchemaElements.newEnum().name("Kind").value("B").value("A").build();
        GraphQLUnionType union = SchemaElements.newUnionType().name("Any").possibleType(object("Dog")).possibleType(object("Cat")).build();
        GraphQLDirective directive = SchemaElements.newDirective().name("d").validLocations(OBJECT)
                .argument(newArgument().name("y").type(GraphQLInt))
                .argument(newArgument().name("x").type(GraphQLInt))
                .build();

        assertThat(object.getFieldDefinitions()).extracting(GraphQLFieldDefinition::getName).containsExactly("a", "b", "name");
        assertThat(object.getInterfaces()).extracting(GraphQLNamedType::getName).containsExactly("Alpha", "Zeta");
        assertThat(object.getDirectives()).extracting(GraphQLDirective::getName).containsExactly("zz", "aa");
        assertThat(withArguments.getArguments()).extracting(GraphQLArgument::getName).containsExactly("x", "y");
        assertThat(anEnum.getValues()).extracting(GraphQLEnumValueDefinition::getName).containsExactly("A", "B");
        assertThat(union.getTypes()).extracting(GraphQLNamedType::getName).containsExactly("Cat", "Dog");
        assertThat(directive.getArguments()).extracting(GraphQLArgument::getName).containsExactly("x", "y");
        assertThat(SchemaElements.newDirective(directive).argument(newArgument().name("a").type(GraphQLInt)).build().getArguments())
                .extracting(GraphQLArgument::getName).containsExactly("a", "x", "y");
    }

    @Test
    void aCopyStartsFromWhatTheElementHoldsNowNotFromTheReferencesItWasBuiltWith() {
        GraphQLInterfaceType named = GraphQLInterfaceType.newInterface().name("Named").field(field("name")).build();
        GraphQLObjectType cat = object("Cat");
        GraphQLInterfaceType pet = GraphQLInterfaceType.newInterface().name("Pet").withInterface(typeRef("Named")).field(field("name")).build();
        GraphQLObjectType dog = SchemaElements.newObject().name("Dog")
                .withInterface(typeRef("Named"))
                .field(SchemaElements.newFieldDefinition().name("friend").type(typeRef("Cat")))
                .build();
        GraphQLUnionType any = SchemaElements.newUnionType().name("Any").possibleType(typeRef("Cat")).build();
        Map<String, GraphQLNamedType> types = Map.of("Named", named, "Cat", cat);
        TRAVERSER.depthFirst(new GraphQLTypeResolvingVisitor(types), java.util.List.of(dog, pet, any));

        GraphQLObjectType dogCopy = SchemaElements.newObject(dog).field(field("extra")).build();
        GraphQLInterfaceType petCopy = SchemaElements.newInterface(pet).field(field("extra")).build();
        GraphQLUnionType anyCopy = SchemaElements.newUnionType(any).possibleType(dogCopy).build();
        GraphQLFieldDefinition friendCopy = SchemaElements.newFieldDefinition(dog.getFieldDefinition("friend")).description("copied").build();

        assertThat(dogCopy.getInterfaces()).containsExactly(named);
        assertThat(dogCopy.getFieldDefinitions()).extracting(GraphQLFieldDefinition::getName).containsExactly("extra", "friend");
        assertThat(petCopy.getInterfaces()).containsExactly(named);
        assertThat(petCopy.getFieldDefinitions()).extracting(GraphQLFieldDefinition::getName).containsExactly("extra", "name");
        assertThat(anyCopy.getTypes()).containsExactly(cat, dogCopy);
        assertThat(friendCopy.getType()).isSameAs(cat);
        assertThat(friendCopy.getDescription()).isEqualTo("copied");
        // what graphql-java itself starts a copy with: the reference
        assertThat(GraphQLFieldDefinition.newFieldDefinition(dog.getFieldDefinition("friend")).build().getType())
                .isInstanceOf(GraphQLTypeReference.class);
    }

    @Test
    void aDirectivePutOnACopyOfAFieldTakesThePlaceOfTheOneWithTheSameName() {
        GraphQLDirective link = SchemaElements.newDirective().name("link").validLocations(FIELD_DEFINITION)
                .argument(newArgument().name("pagination").type(GraphQLBoolean).valueProgrammatic(false))
                .build();
        GraphQLFieldDefinition existing = SchemaElements.newFieldDefinition().name("f").type(GraphQLString)
                .withDirective(directive("first"))
                .withDirective(link)
                .withDirective(directive("last"))
                .withAppliedDirective(GraphQLAppliedDirective.newDirective().name("link").build())
                .withAppliedDirective(GraphQLAppliedDirective.newDirective().name("other").build())
                .build();
        GraphQLDirective newLink = SchemaElements.newDirective(link)
                .argument(newArgument().name("reverseName").type(GraphQLString).valueProgrammatic("owners"))
                .build();

        GraphQLFieldDefinition replaced = SchemaElements.withDirective(SchemaElements.newFieldDefinition(existing), existing, newLink).build();
        GraphQLFieldDefinition added = SchemaElements.withDirective(SchemaElements.newFieldDefinition(existing), existing, directive("new")).build();

        assertThat(replaced.getDirectives()).extracting(GraphQLDirective::getName).containsExactly("first", "link", "last");
        assertThat(replaced.getDirective("link")).isSameAs(newLink);
        // the applied directive of that name went with the directive it was made from
        assertThat(replaced.getAppliedDirectives()).extracting(GraphQLAppliedDirective::getName).containsExactly("other");
        assertThat(added.getDirectives()).extracting(GraphQLDirective::getName).containsExactly("first", "link", "last", "new");
        assertThat(added.getAppliedDirectives()).extracting(GraphQLAppliedDirective::getName).containsExactly("link", "other");
        // what graphql-java itself does: the directive is added, and there are two of the name
        assertThat(GraphQLFieldDefinition.newFieldDefinition(existing).withDirective(newLink).build().getDirectives())
                .extracting(GraphQLNamedSchemaElement::getName).containsExactly("first", "link", "last", "link");
    }

    @Test
    void aListOrNonNullTypeHasNoName() {
        assertThat(SchemaElements.name(GraphQLString)).isEqualTo("String");
        assertThat(SchemaElements.name(field("f"))).isEqualTo("f");
        assertThat(SchemaElements.name(list(GraphQLString))).isNull();
        assertThat(SchemaElements.name(nonNull(GraphQLString))).isNull();
    }

    @Test
    void theValueOfAnArgumentIsReadAsTheJavaValue() {
        GraphQLArgument given = newArgument().name("a").type(GraphQLBoolean).defaultValueProgrammatic(true).valueProgrammatic(false).build();
        GraphQLArgument notGiven = newArgument().name("a").type(GraphQLBoolean).defaultValueProgrammatic(true).build();
        GraphQLArgument bare = newArgument().name("a").type(GraphQLString).build();

        assertThat(SchemaElements.value(given)).isEqualTo(false);
        assertThat(SchemaElements.defaultValue(given)).isEqualTo(true);
        assertThat(SchemaElements.value(notGiven)).isNull();
        assertThat(SchemaElements.defaultValue(notGiven)).isEqualTo(true);
        assertThat(SchemaElements.value(bare)).isNull();
        assertThat(SchemaElements.defaultValue(bare)).isNull();
    }
}
