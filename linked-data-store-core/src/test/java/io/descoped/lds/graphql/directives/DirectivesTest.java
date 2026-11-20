package io.descoped.lds.graphql.directives;

import graphql.introspection.Introspection.DirectiveLocation;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeUtil;
import org.junit.jupiter.api.Test;

import static graphql.Scalars.GraphQLString;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three directives of LDS: what they define, and what one of them on an element of a schema says.
 */
class DirectivesTest {

    private static Object value(GraphQLDirective directive, String argument) {
        return GraphQLArgument.getArgumentValue(directive.getArgument(argument));
    }

    private static Object defaultValue(GraphQLDirective directive, String argument) {
        return GraphQLArgument.getArgumentDefaultValue(directive.getArgument(argument));
    }

    // ---- domain ------------------------------------------------------------------------------------------------

    @Test
    void theDomainDirectiveIsDefinedOnObjectsWithSearchableTrueByDefault() {
        GraphQLDirective directive = DomainDirective.INSTANCE.getDirective();

        assertThat(directive.getName()).isEqualTo("domain");
        assertThat(directive.getDescription()).isEqualTo("Marks a type definition as a managed domain object");
        assertThat(directive.validLocations()).containsExactly(DirectiveLocation.OBJECT);
        assertThat(directive.getArguments()).extracting(GraphQLArgument::getName).containsExactly("searchable");
        assertThat(GraphQLTypeUtil.simplePrint(directive.getArgument("searchable").getType())).isEqualTo("Boolean");
        assertThat(defaultValue(directive, "searchable")).isEqualTo(true);
        assertThat(value(directive, "searchable")).isNull();
    }

    @Test
    void aDomainIsSearchableUnlessItSaysOtherwise() {
        assertThat(DomainDirective.INSTANCE.isSearchable()).isTrue();
        assertThat(DomainDirective.newDomainDirective(true).isSearchable()).isTrue();
        assertThat(DomainDirective.newDomainDirective(false).isSearchable()).isFalse();
    }

    @Test
    void anArgumentIsFoundByItsName() {
        assertThat(DomainDirective.INSTANCE.getArgument("searchable")).isSameAs(DomainDirective.INSTANCE.getDirective().getArgument("searchable"));
        assertThat(DomainDirective.INSTANCE.getArgument("other")).isNull();
        assertThat(LinkDirective.INSTANCE.getArgument("other")).isNull();
        assertThat(ReverseLinkDirective.INSTANCE.getArgument("other")).isNull();
    }

    @Test
    void aTypeWithTheDomainDirectiveIsADomain() {
        GraphQLObjectType domain = newObject().name("Cat")
                .withDirective(DomainDirective.newDomainDirective(false).getDirective())
                .field(newFieldDefinition().name("name").type(GraphQLString))
                .build();
        GraphQLObjectType embedded = newObject().name("Address")
                .withDirective(LinkDirective.INSTANCE.getDirective())
                .field(newFieldDefinition().name("street").type(GraphQLString))
                .build();

        assertThat(DomainDirective.hasDomainDirective(domain)).isTrue();
        assertThat(DomainDirective.hasDomainDirective(embedded)).isFalse();
    }

    // ---- link --------------------------------------------------------------------------------------------------

    @Test
    void theLinkDirectiveIsDefinedOnFieldsWithPaginationTrueByDefault() {
        GraphQLDirective directive = LinkDirective.INSTANCE.getDirective();

        assertThat(directive.getName()).isEqualTo("link");
        assertThat(directive.getDescription()).isEqualTo("Defines a field as a link to another managed domain");
        assertThat(directive.validLocations()).containsExactly(DirectiveLocation.FIELD_DEFINITION);
        assertThat(directive.getArguments()).extracting(GraphQLArgument::getName).containsExactly("pagination", "reverseName");
        assertThat(defaultValue(directive, "pagination")).isEqualTo(true);
        assertThat(defaultValue(directive, "reverseName")).isNull();
        assertThat(LinkDirective.INSTANCE.getReverseName()).isEmpty();
        assertThat(LinkDirective.INSTANCE.getPagination()).isTrue();
    }

    @Test
    void aLinkSaysWhetherItIsPagedAndWhatItsReverseIsCalled() {
        LinkDirective paged = LinkDirective.newLinkDirective();
        assertThat(value(paged.getDirective(), "pagination")).isEqualTo(true);
        assertThat(paged.getArgument("reverseName")).isNull();
        assertThat(paged.getReverseName()).isEmpty();
        assertThat(paged.getPagination()).isTrue();

        LinkDirective plain = LinkDirective.newLinkDirective(false);
        assertThat(value(plain.getDirective(), "pagination")).isEqualTo(false);

        LinkDirective named = LinkDirective.newLinkDirective(false, "owners");
        assertThat(value(named.getDirective(), "pagination")).isEqualTo(false);
        assertThat(value(named.getDirective(), "reverseName")).isEqualTo("owners");
        assertThat(named.getReverseName()).contains("owners");
        assertThat(named.getDirective().getArguments()).extracting(GraphQLArgument::getName).containsExactly("pagination", "reverseName");
    }

    @Test
    void aLinkIsReadFromTheDirectiveOnAField() {
        LinkDirective named = LinkDirective.newLinkDirective(LinkDirective.newLinkDirective(false, "owners").getDirective());
        assertThat(value(named.getDirective(), "pagination")).isEqualTo(false);
        assertThat(named.getReverseName()).contains("owners");

        LinkDirective plain = LinkDirective.newLinkDirective(LinkDirective.newLinkDirective(false).getDirective());
        assertThat(value(plain.getDirective(), "pagination")).isEqualTo(false);
        assertThat(plain.getReverseName()).isEmpty();

        // a directive that gives neither: a link is paged unless it says otherwise
        LinkDirective bare = LinkDirective.newLinkDirective(LinkDirective.INSTANCE.getDirective());
        assertThat(value(bare.getDirective(), "pagination")).isEqualTo(true);
        assertThat(bare.getReverseName()).isEmpty();
    }

    // ---- reverse link ------------------------------------------------------------------------------------------

    @Test
    void theReverseLinkDirectiveIsDefinedOnFields() {
        GraphQLDirective directive = ReverseLinkDirective.INSTANCE.getDirective();

        assertThat(directive.getName()).isEqualTo("reverseLink");
        assertThat(directive.getDescription()).isEqualTo("Defines a field as a reverse link to another managed domain");
        assertThat(directive.validLocations()).containsExactly(DirectiveLocation.FIELD_DEFINITION);
        assertThat(directive.getArguments()).extracting(GraphQLArgument::getName).containsExactly("mappedBy", "pagination");
        assertThat(defaultValue(directive, "pagination")).isEqualTo(true);
        assertThat(ReverseLinkDirective.INSTANCE.getMappedBy()).isNull();
        assertThat(ReverseLinkDirective.INSTANCE.getPagination()).isTrue();
    }

    @Test
    void aReverseLinkSaysWhatItIsMappedBy() {
        ReverseLinkDirective reverseLink = ReverseLinkDirective.newReverseLinkDirective(true, "$.owners[]");

        assertThat(reverseLink.getMappedBy()).isEqualTo("$.owners[]");
        assertThat(reverseLink.getPagination()).isTrue();
        assertThat(value(reverseLink.getDirective(), "mappedBy")).isEqualTo("$.owners[]");
        assertThat(value(reverseLink.getDirective(), "pagination")).isEqualTo(true);
        assertThat(reverseLink.getDirective().getArguments()).extracting(GraphQLArgument::getName).containsExactly("mappedBy", "pagination");

        ReverseLinkDirective other = reverseLink.newReverseLinkDirective("$.friend");
        assertThat(other.getMappedBy()).isEqualTo("$.friend");
        assertThat(other.getArgument("pagination")).isNull();
    }

    @Test
    void aReverseLinkMustBeMappedBySomething() {
        assertThatThrownBy(() -> ReverseLinkDirective.newReverseLinkDirective(true, null))
                .isInstanceOf(NullPointerException.class);
    }
}
