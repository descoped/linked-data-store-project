package io.descoped.lds.graphql.schemas;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.scalars.ExtendedScalars;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DateTime scalar of LDS gives a value out with the fraction of a second the value has, and reads a value as
 * the scalar of graphql-java-extended-scalars does.
 */
class DateTimeScalarTest {

    private static final GraphQLContext CONTEXT = GraphQLContext.getDefault();
    private static final Coercing<?, ?> COERCING = DateTimeScalar.INSTANCE.getCoercing();
    private static final Coercing<?, ?> LIBRARY = ExtendedScalars.DateTime.getCoercing();

    private static Object serialize(Object value) {
        return COERCING.serialize(value, CONTEXT, Locale.ROOT);
    }

    @Test
    void itIsNamedAndDescribedAsItAlwaysWas() {
        assertThat(DateTimeScalar.INSTANCE.getName()).isEqualTo("DateTime");
        assertThat(DateTimeScalar.INSTANCE.getDescription()).isEqualTo("An RFC-3339 compliant DateTime Scalar");
    }

    @Test
    void aValueWithSixDigitsOfFractionComesOutWithSix() {
        assertThat(serialize("2019-03-11T10:15:30.123456Z")).isEqualTo("2019-03-11T10:15:30.123456Z");
        assertThat(serialize("2019-03-11T10:15:30.123456789+01:00")).isEqualTo("2019-03-11T10:15:30.123456789+01:00");
    }

    @Test
    void aValueWithNoFractionComesOutWithNone() {
        assertThat(serialize("2019-03-11T10:15:30Z")).isEqualTo("2019-03-11T10:15:30Z");
        assertThat(serialize("2019-03-11T10:15:30+02:00")).isEqualTo("2019-03-11T10:15:30+02:00");
    }

    @Test
    void aValueWithThreeDigitsOfFractionComesOutWithThree() {
        assertThat(serialize("2019-03-11T10:15:30.120+01:00")).isEqualTo("2019-03-11T10:15:30.12+01:00");
        assertThat(serialize("2019-03-11T10:15:30.123+01:00")).isEqualTo("2019-03-11T10:15:30.123+01:00");
    }

    @Test
    void anOffsetDateTimeIsAccepted() {
        OffsetDateTime value = OffsetDateTime.of(2019, 3, 11, 10, 15, 30, 123_456_000, ZoneOffset.ofHours(2));

        assertThat(serialize(value)).isEqualTo("2019-03-11T10:15:30.123456+02:00");
    }

    @Test
    void aZonedDateTimeIsAccepted() {
        ZonedDateTime value = ZonedDateTime.of(2019, 3, 11, 10, 15, 30, 0, ZoneId.of("Europe/Oslo"));

        assertThat(serialize(value)).isEqualTo("2019-03-11T10:15:30+01:00");
    }

    @Test
    void somethingThatIsNotADateTimeIsRefused() {
        assertThatThrownBy(() -> serialize(42))
                .isInstanceOf(CoercingSerializeException.class)
                .hasMessage("Expected something we can convert to 'java.time.OffsetDateTime' but was 'Integer'.");
        assertThatThrownBy(() -> serialize(null))
                .isInstanceOf(CoercingSerializeException.class)
                .hasMessage("Expected something we can convert to 'java.time.OffsetDateTime' but was 'null'.");
    }

    @Test
    void aMalformedStringIsRefused() {
        assertThatThrownBy(() -> serialize("2019-03-11 10:15"))
                .isInstanceOf(CoercingSerializeException.class)
                .hasMessageStartingWith("Invalid RFC3339 value : '2019-03-11 10:15'. because of : '");
        assertThatThrownBy(() -> serialize("2019-03-11T10:15:30"))
                .isInstanceOf(CoercingSerializeException.class);
    }

    @Test
    void aValueIsParsedAsTheLibraryParsesIt() {
        for (Object value : new Object[]{
                "2019-03-11T10:15:30.123456Z",
                "2019-03-11T10:15:30+02:00",
                OffsetDateTime.of(2019, 3, 11, 10, 15, 30, 0, ZoneOffset.UTC),
                ZonedDateTime.of(2019, 3, 11, 10, 15, 30, 0, ZoneId.of("Europe/Oslo"))}) {
            assertThat(COERCING.parseValue(value, CONTEXT, Locale.ROOT))
                    .isInstanceOf(OffsetDateTime.class)
                    .isEqualTo(LIBRARY.parseValue(value, CONTEXT, Locale.ROOT));
        }
        assertThatThrownBy(() -> COERCING.parseValue("yesterday", CONTEXT, Locale.ROOT))
                .isInstanceOf(CoercingParseValueException.class);
        assertThatThrownBy(() -> COERCING.parseValue(42, CONTEXT, Locale.ROOT))
                .isInstanceOf(CoercingParseValueException.class);
    }

    @Test
    void aLiteralIsParsedAsTheLibraryParsesIt() {
        Value<?> literal = StringValue.newStringValue("2019-03-11T10:15:30.123456+02:00").build();

        assertThat(COERCING.parseLiteral(literal, CoercedVariables.emptyVariables(), CONTEXT, Locale.ROOT))
                .isInstanceOf(OffsetDateTime.class)
                .isEqualTo(LIBRARY.parseLiteral(literal, CoercedVariables.emptyVariables(), CONTEXT, Locale.ROOT));

        Value<?> malformed = StringValue.newStringValue("yesterday").build();
        assertThatThrownBy(() -> COERCING.parseLiteral(malformed, CoercedVariables.emptyVariables(), CONTEXT, Locale.ROOT))
                .isInstanceOf(CoercingParseLiteralException.class);
        Value<?> number = IntValue.of(42);
        assertThatThrownBy(() -> COERCING.parseLiteral(number, CoercedVariables.emptyVariables(), CONTEXT, Locale.ROOT))
                .isInstanceOf(CoercingParseLiteralException.class);
    }

    @Test
    void aValueBecomesALiteralWithTheFractionItHas() {
        Value<?> literal = COERCING.valueToLiteral("2019-03-11T10:15:30.123456Z", CONTEXT, Locale.ROOT);

        assertThat(literal).isInstanceOf(StringValue.class);
        assertThat(((StringValue) literal).getValue()).isEqualTo("2019-03-11T10:15:30.123456Z");
    }
}
