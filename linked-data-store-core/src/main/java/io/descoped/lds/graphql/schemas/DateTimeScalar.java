package io.descoped.lds.graphql.schemas;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.scalars.ExtendedScalars;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;

import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * The DateTime scalar of LDS.
 * <p>
 * It is the scalar of graphql-java-extended-scalars, except in how it gives a value out. That scalar now always
 * writes three digits of the fraction of a second: a value with more loses them, and a value with none gets
 * {@code .000}. LDS gives a value out as it always did, with the fraction the value has.
 */
final class DateTimeScalar {

    static final GraphQLScalarType INSTANCE = GraphQLScalarType.newScalar()
            .name("DateTime")
            .description("An RFC-3339 compliant DateTime Scalar")
            .coercing(new DateTimeCoercing(ExtendedScalars.DateTime.getCoercing()))
            .build();

    private DateTimeScalar() {
    }

    private static final class DateTimeCoercing implements Coercing<Object, String> {

        private final Coercing<?, ?> extended;

        DateTimeCoercing(Coercing<?, ?> extended) {
            this.extended = extended;
        }

        @Override
        public String serialize(Object input, GraphQLContext graphQLContext, Locale locale) throws CoercingSerializeException {
            OffsetDateTime offsetDateTime;
            if (input instanceof OffsetDateTime) {
                offsetDateTime = (OffsetDateTime) input;
            } else if (input instanceof ZonedDateTime) {
                offsetDateTime = ((ZonedDateTime) input).toOffsetDateTime();
            } else if (input instanceof String) {
                try {
                    offsetDateTime = OffsetDateTime.parse(input.toString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                } catch (DateTimeParseException e) {
                    throw new CoercingSerializeException(
                            "Invalid RFC3339 value : '" + input + "'. because of : '" + e.getMessage() + "'");
                }
            } else {
                throw new CoercingSerializeException(
                        "Expected something we can convert to 'java.time.OffsetDateTime' but was '"
                                + (input == null ? "null" : input.getClass().getSimpleName()) + "'."
                );
            }
            try {
                return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(offsetDateTime);
            } catch (DateTimeException e) {
                throw new CoercingSerializeException(
                        "Unable to turn TemporalAccessor into OffsetDateTime because of : '" + e.getMessage() + "'."
                );
            }
        }

        @Override
        public Object parseValue(Object input, GraphQLContext graphQLContext, Locale locale) throws CoercingParseValueException {
            return extended.parseValue(input, graphQLContext, locale);
        }

        @Override
        public Object parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext graphQLContext, Locale locale) throws CoercingParseLiteralException {
            return extended.parseLiteral(input, variables, graphQLContext, locale);
        }

        @Override
        public Value<?> valueToLiteral(Object input, GraphQLContext graphQLContext, Locale locale) {
            return StringValue.newStringValue(serialize(input, graphQLContext, locale)).build();
        }
    }
}
