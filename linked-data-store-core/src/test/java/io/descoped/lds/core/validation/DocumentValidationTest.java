package io.descoped.lds.core.validation;

import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.core.schema.JsonSchemaBuilder;
import io.descoped.lds.core.specification.JsonSchemaBasedSpecification;
import io.descoped.lds.core.specification.SpecificationJsonSchemaBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a document is refused for before it is written, and what the answer says.
 */
class DocumentValidationTest {

    private static final String LINK_TO_PERSON = "{\"type\":\"object\",\"properties\":{\"Person\":{\"type\":\"null\"}}}";

    private static final String PERSON = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"$ref\":\"#/$defs/Person\",\"$defs\":{\"Person\":{"
            + "\"type\":\"object\",\"properties\":{"
            + "\"name\":{\"type\":\"string\"},"
            + "\"born\":{\"type\":\"string\",\"format\":\"date-time\"},"
            + "\"kind\":{\"type\":\"string\",\"enum\":[\"A\",\"B\"]},"
            + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
            + "\"address\":{\"type\":\"object\",\"properties\":{\"street\":{\"type\":\"string\"}},\"additionalProperties\":false},"
            + "\"friend\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]},\"_link_property_friend\":" + LINK_TO_PERSON + ","
            + "\"friends\":{\"type\":\"array\",\"items\":{\"type\":[\"string\",\"null\"]}},\"_link_property_friends\":" + LINK_TO_PERSON + ","
            // an object that may also be null, or anything else: it says no type
            + "\"family\":{\"properties\":{\"mother\":{\"type\":\"string\"},\"_link_property_mother\":" + LINK_TO_PERSON + "}}"
            + "},\"required\":[\"name\"]}}}";

    private static final LinkedDocumentValidator VALIDATOR = validator();

    private static LinkedDocumentValidator validator() {
        JsonSchemaBasedSpecification specification = SpecificationJsonSchemaBuilder.createBuilder(
                new JsonSchemaBuilder(null, "Person", JsonTools.toJsonNode(PERSON)).build()).build();
        return new LinkedDocumentValidator(specification, specification);
    }

    private static void validate(String document) {
        VALIDATOR.validate("Person", JsonTools.toJsonNode(document.replace('\'', '"')));
    }

    @Test
    void aDocumentThatFollowsTheSchemaIsValid() {
        assertThatCode(() -> validate("{'name':'Ola','born':'2020-01-01T12:00:00Z','kind':'A','tags':['x'],'address':{'street':'s'},"
                + "'friend':'/Person/p2','friends':['/Person/p3','/Person/p4'],'family':{'mother':'/Person/p5'},'more':7}")).doesNotThrowAnyException();
        assertThatCode(() -> validate("{'name':'Ola'}")).doesNotThrowAnyException();
    }

    @Test
    void whatIsWrongIsSaidWithThePlaceInTheDocument() {
        assertThatThrownBy(() -> validate("{}"))
                .isInstanceOf(LinkedDocumentValidationException.class).hasMessage("[$: required property 'name' not found]");
        assertThatThrownBy(() -> validate("{'name':7}"))
                .isInstanceOf(LinkedDocumentValidationException.class).hasMessage("[$.name: integer found, string expected]");
        assertThatThrownBy(() -> validate("{'name':'Ola','tags':['a',2]}"))
                .isInstanceOf(LinkedDocumentValidationException.class).hasMessage("[$.tags[1]: integer found, string expected]");
        assertThatThrownBy(() -> validate("{'name':'Ola','kind':'C'}"))
                .isInstanceOf(LinkedDocumentValidationException.class).hasMessage("[$.kind: does not have a value in the enumeration [\"A\", \"B\"]]");
        assertThatThrownBy(() -> validate("{'name':'Ola','address':{'street':'s','city':'c'}}"))
                .isInstanceOf(LinkedDocumentValidationException.class)
                .hasMessage("[$.address: property 'city' is not defined in the schema and the schema does not allow additional properties]");
    }

    @Test
    void everythingThatIsWrongIsSaid() {
        assertThatThrownBy(() -> validate("{'name':7,'kind':'C','tags':3}"))
                .isInstanceOf(LinkedDocumentValidationException.class)
                .hasMessageContaining("$.name: integer found, string expected")
                .hasMessageContaining("$.kind: does not have a value in the enumeration")
                .hasMessageContaining("$.tags: integer found, array expected");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2020-01-01T00:00:00Z", "2020-01-01T00:00:00.123Z", "2020-01-01T00:00:00.123456789Z", "2020-01-01T00:00:00+01:00", "2020-01-01T00:00:00-00:00"})
    void aDateTimeIsValid(String dateTime) {
        assertThatCode(() -> validate("{'name':'Ola','born':'" + dateTime + "'}")).doesNotThrowAnyException();
    }

    /**
     * In JSON Schema 2020-12 a {@code format} only describes a value, unless it is asked to be checked. LDS asks,
     * as it was checked with draft-04. A date that does not exist (the 30th of February) is refused now; it was
     * accepted before.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "yesterday", "2020-01-01", "2020-01-01T00:00:00", "2020-01-01T00:00Z", "2020-01-01 00:00:00Z", "2020-01-01T00:00:00+0100",
            "2020-01-01T00:00:00Z[Etc/UTC]", "2020-02-30T00:00:00Z", "2020-01-01T24:00:00Z"})
    void aTextInADateTimeFieldThatIsNoDateTimeIsRefused(String text) {
        assertThatThrownBy(() -> validate("{'name':'Ola','born':'" + text + "'}"))
                .isInstanceOf(LinkedDocumentValidationException.class).hasMessageContaining("$.born: does not match the date-time pattern");
    }

    @Test
    void aLinkMustBeALinkToAnEntityTheFieldMayLinkTo() {
        assertThatThrownBy(() -> validate("{'name':'Ola','friend':'nobody'}"))
                .isInstanceOf(LinkedDocumentValidationException.class).hasMessage("Not a valid link. Navigation: friend: \"nobody\"");
        assertThatThrownBy(() -> validate("{'name':'Ola','friend':'/House/h1'}"))
                .isInstanceOf(LinkedDocumentValidationException.class).hasMessage("Illegal linked-domain: \"House\". Navigation: friend: \"/House/h1\"");
        assertThatThrownBy(() -> validate("{'name':'Ola','friends':['/Person/p2','Person/p3']}"))
                .isInstanceOf(LinkedDocumentValidationException.class).hasMessage("Not a valid link. Navigation: friends: \"Person/p3\"");
        assertThatThrownBy(() -> validate("{'name':'Ola','family':{'mother':'/House/h1'}}"))
                .isInstanceOf(LinkedDocumentValidationException.class).hasMessage("Illegal linked-domain: \"House\". Navigation: family.mother: \"/House/h1\"");
    }

    /**
     * Where the schema lets a link be null, a null is no link to check. In an array of links, and in place of an
     * object that holds links, this used to end in an error of the server.
     */
    @Test
    void aNullWhereALinkCouldBeIsNoLink() {
        assertThatCode(() -> validate("{'name':'Ola','friend':null}")).doesNotThrowAnyException();
        assertThatCode(() -> validate("{'name':'Ola','friends':['/Person/p2',null]}")).doesNotThrowAnyException();
        assertThatCode(() -> validate("{'name':'Ola','family':null}")).doesNotThrowAnyException();
        assertThatCode(() -> validate("{'name':'Ola','family':'none'}")).doesNotThrowAnyException();
        assertThatCode(() -> validate("{'name':'Ola','family':{}}")).doesNotThrowAnyException();
    }
}
