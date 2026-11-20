package io.descoped.lds.graphql.schemas;

import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.api.specification.SpecificationElement;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Writes what LDS makes of a GraphQL schema as text: the schema it serves, the JSON Schema of every entity and
 * the specification. The text names everything that counts (types, fields and their order, arguments, directives
 * and their values, descriptions, data fetchers and what they were set up with) and is the same from run to run,
 * so it can be compared with a file.
 * <p>
 * The files of {@code graphql-golden} were written with this printer on graphql-java 12, before LDS moved to a
 * later version. The printer does not use the schema printer of graphql-java, whose text changes with the
 * version. Two things are left out because they are the library's own and differ between its versions: the types
 * of introspection ({@code __Schema} and the others) and the definitions of the built-in directives.
 */
final class CanonicalText {

    /**
     * The directives graphql-java defines itself, in any of its versions.
     */
    private static final Set<String> BUILT_IN_DIRECTIVES = Set.of("include", "skip", "defer", "deprecated",
            "specifiedBy", "oneOf", "experimental_disableErrorPropagation");

    private CanonicalText() {
    }

    // ---- the schema --------------------------------------------------------------------------------------------

    /**
     * The schema as text.
     *
     * @param schema                      the schema
     * @param directiveDefinitionsLeftOut the names of directives whose definition is not written
     */
    static String schema(GraphQLSchema schema, Set<String> directiveDefinitionsLeftOut) {
        StringBuilder sb = new StringBuilder();
        sb.append("query=").append(name(schema.getQueryType()))
                .append(" mutation=").append(schema.getMutationType() == null ? null : name(schema.getMutationType()))
                .append(" subscription=").append(schema.getSubscriptionType() == null ? null : name(schema.getSubscriptionType()))
                .append('\n');
        Map<String, GraphQLDirective> definitions = new TreeMap<>();
        for (GraphQLDirective directive : schema.getDirectives()) {
            definitions.put(directive.getName(), directive);
        }
        for (GraphQLDirective directive : definitions.values()) {
            if (BUILT_IN_DIRECTIVES.contains(directive.getName()) || directiveDefinitionsLeftOut.contains(directive.getName())) {
                continue;
            }
            sb.append("directive-definition @").append(directive.getName())
                    .append(" locations=").append(new TreeSet<>(directive.validLocations()))
                    .append(" description=").append(oneLine(directive.getDescription())).append('\n');
            for (GraphQLArgument argument : directive.getArguments()) {
                sb.append("  arg ").append(argument.getName()).append(": ").append(GraphQLTypeUtil.simplePrint(argument.getType()))
                        .append(" default=").append(value(GraphQLArgument.getArgumentDefaultValue(argument)))
                        .append(" value=").append(value(GraphQLArgument.getArgumentValue(argument)))
                        .append(" description=").append(oneLine(argument.getDescription())).append('\n');
            }
        }
        for (Map.Entry<String, GraphQLNamedType> entry : new TreeMap<>(schema.getTypeMap()).entrySet()) {
            if (!entry.getKey().startsWith("__")) {
                type(sb, schema, entry.getKey(), entry.getValue());
            }
        }
        return sb.toString();
    }

    private static void type(StringBuilder sb, GraphQLSchema schema, String key, GraphQLNamedType type) {
        sb.append(type.getClass().getSimpleName()).append(' ').append(key).append('\n');
        if (!key.equals(type.getName())) {
            sb.append("  NAME MISMATCH ").append(type.getName()).append('\n');
        }
        sb.append("  description=").append(oneLine(type.getDescription())).append('\n');
        if (type instanceof GraphQLObjectType object) {
            sb.append("  interfaces=").append(names(object.getInterfaces())).append('\n');
            directives(sb, "  ", object);
            fields(sb, schema, object, true);
        } else if (type instanceof GraphQLInterfaceType anInterface) {
            directives(sb, "  ", anInterface);
            fields(sb, schema, anInterface, false);
            sb.append("  typeResolver=").append(hasTypeResolver(schema, anInterface)).append('\n');
        } else if (type instanceof GraphQLUnionType union) {
            sb.append("  members=").append(names(union.getTypes())).append('\n');
            directives(sb, "  ", union);
            sb.append("  typeResolver=").append(hasTypeResolver(schema, union)).append('\n');
        } else if (type instanceof GraphQLEnumType anEnum) {
            directives(sb, "  ", anEnum);
            for (GraphQLEnumValueDefinition value : anEnum.getValues()) {
                sb.append("  value ").append(value.getName()).append(" = ").append(value(value.getValue()))
                        .append(" description=").append(oneLine(value.getDescription()))
                        .append(" deprecated=").append(oneLine(value.getDeprecationReason())).append('\n');
                directives(sb, "    ", value);
            }
        } else if (type instanceof GraphQLScalarType scalar) {
            directives(sb, "  ", scalar);
        } else if (type instanceof GraphQLInputObjectType input) {
            directives(sb, "  ", input);
            for (GraphQLInputObjectField field : input.getFieldDefinitions()) {
                sb.append("  input-field ").append(field.getName()).append(": ").append(GraphQLTypeUtil.simplePrint(field.getType()))
                        .append(" default=").append(value(GraphQLInputObjectField.getInputFieldDefaultValue(field)))
                        .append(" description=").append(oneLine(field.getDescription())).append('\n');
                directives(sb, "    ", field);
            }
        }
    }

    private static String name(GraphQLType type) {
        return type instanceof GraphQLNamedType named ? named.getName() : null;
    }

    private static List<String> names(List<? extends GraphQLType> types) {
        List<String> names = new ArrayList<>();
        for (GraphQLType type : types) {
            names.add(type instanceof GraphQLTypeReference ? "ref:" + name(type) : name(type));
        }
        return names;
    }

    private static void fields(StringBuilder sb, GraphQLSchema schema, GraphQLFieldsContainer container, boolean withFetcher) {
        for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
            sb.append("  field ").append(field.getName()).append(": ").append(GraphQLTypeUtil.simplePrint(field.getType()))
                    .append(" description=").append(oneLine(field.getDescription()))
                    .append(" deprecated=").append(oneLine(field.getDeprecationReason())).append('\n');
            for (GraphQLArgument argument : field.getArguments()) {
                sb.append("    arg ").append(argument.getName()).append(": ").append(GraphQLTypeUtil.simplePrint(argument.getType()))
                        .append(" default=").append(value(GraphQLArgument.getArgumentDefaultValue(argument)))
                        .append(" description=").append(oneLine(argument.getDescription())).append('\n');
                directives(sb, "      ", argument);
            }
            directives(sb, "    ", field);
            if (withFetcher) {
                DataFetcher<?> fetcher = schema.getCodeRegistry().getDataFetcher(
                        FieldCoordinates.coordinates(container.getName(), field.getName()), field);
                sb.append("    fetcher=").append(fetcher(fetcher)).append('\n');
            }
        }
    }

    /**
     * The directives on an element. graphql-java holds a directive on an element in two forms, and LDS reads the
     * older one, so that is what is written. The newer form (an applied directive) is written only when there is
     * no directive of that name in the older form: LDS marks the Search field that way.
     */
    private static void directives(StringBuilder sb, String indent, GraphQLDirectiveContainer container) {
        Set<String> names = new HashSet<>();
        for (GraphQLDirective directive : container.getDirectives()) {
            names.add(directive.getName());
            sb.append(indent).append("@").append(directive.getName()).append('\n');
            for (GraphQLArgument argument : directive.getArguments()) {
                sb.append(indent).append("  ").append(argument.getName()).append(": ").append(argumentType(directive, argument))
                        .append(" value=").append(value(GraphQLArgument.getArgumentValue(argument)))
                        .append(" default=").append(value(GraphQLArgument.getArgumentDefaultValue(argument))).append('\n');
            }
        }
        for (GraphQLAppliedDirective directive : container.getAppliedDirectives()) {
            if (names.contains(directive.getName())) {
                continue;
            }
            sb.append(indent).append("@").append(directive.getName()).append('\n');
            for (GraphQLAppliedDirectiveArgument argument : directive.getArguments()) {
                sb.append(indent).append("  ").append(argument.getName()).append(": ").append(GraphQLTypeUtil.simplePrint(argument.getType()))
                        .append(" value=").append(value(argument.getValue()))
                        .append(" APPLIED-ONLY").append('\n');
            }
        }
    }

    /**
     * The type of an argument of a directive on an element. The reason of {@code @deprecated} is the library's
     * own: it was a String in graphql-java 12 and is a String! now. It is written as it was, without the non-null,
     * so the files written on 12 still hold.
     */
    private static String argumentType(GraphQLDirective directive, GraphQLArgument argument) {
        GraphQLType type = argument.getType();
        if (BUILT_IN_DIRECTIVES.contains(directive.getName())) {
            type = GraphQLTypeUtil.unwrapNonNull(type);
        }
        return GraphQLTypeUtil.simplePrint(type);
    }

    private static boolean hasTypeResolver(GraphQLSchema schema, GraphQLType type) {
        try {
            if (type instanceof GraphQLInterfaceType anInterface) {
                return schema.getCodeRegistry().getTypeResolver(anInterface) != null;
            }
            return schema.getCodeRegistry().getTypeResolver((GraphQLUnionType) type) != null;
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    private static String value(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return oneLine(string);
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.getClass().getSimpleName() + ":" + value;
        }
        return value.getClass().getSimpleName() + ":" + oneLine(String.valueOf(value));
    }

    /**
     * A data fetcher as text: "default" for the one graphql-java gives a field that has none, and for one of LDS
     * its class and what its fields hold.
     */
    private static String fetcher(DataFetcher<?> fetcher) {
        if (fetcher == null) {
            return "null";
        }
        Class<?> type = fetcher.getClass();
        if (!type.getName().startsWith("io.descoped.")) {
            String name = type.getSimpleName();
            return name.equals("PropertyDataFetcher") || name.equals("SingletonPropertyDataFetcher") ? "default" : type.getName();
        }
        Map<String, String> values = new TreeMap<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    values.put(field.getName(), fieldValue(fetcher, field));
                }
            }
        }
        return type.getSimpleName() + "{" + values + "}";
    }

    private static String fieldValue(DataFetcher<?> fetcher, Field field) {
        Object value;
        try {
            field.setAccessible(true);
            value = field.get(fetcher);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?" + e.getClass().getSimpleName();
        }
        if (value == null) {
            return "null";
        } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        } else if (value instanceof Pattern) {
            return "pattern:" + value;
        } else if (value instanceof JsonNavigationPath path) {
            return "path:" + path.serialize();
        } else if (Proxy.isProxyClass(value.getClass())) {
            return String.valueOf(value);
        }
        return value.getClass().getSimpleName();
    }

    // ---- the JSON Schema ---------------------------------------------------------------------------------------

    /**
     * The JSON Schema of every entity as text: the order of the entities, then each of them with its keys
     * sorted.
     */
    static String jsonSchema(Map<String, ? extends JsonNode> jsonMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("ORDER ").append(String.join(",", jsonMap.keySet())).append('\n');
        for (Map.Entry<String, ? extends JsonNode> entry : jsonMap.entrySet()) {
            sb.append("ENTITY ").append(entry.getKey()).append('\n');
            json(sb, "", entry.getValue());
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Writes a JSON value: an object over several lines with its keys sorted, an array on the line it starts on.
     */
    private static void json(StringBuilder sb, String indent, JsonNode json) {
        if (json.isObject()) {
            if (json.isEmpty()) {
                sb.append("{ }");
                return;
            }
            sb.append('{');
            String separator = "\n";
            for (String key : new TreeSet<>(json.propertyNames())) {
                sb.append(separator).append(indent).append("  ").append(quoted(key)).append(" : ");
                json(sb, indent + "  ", json.get(key));
                separator = ",\n";
            }
            sb.append('\n').append(indent).append('}');
        } else if (json.isArray()) {
            if (json.isEmpty()) {
                sb.append("[ ]");
                return;
            }
            String separator = "[ ";
            for (JsonNode element : json) {
                sb.append(separator);
                json(sb, indent, element);
                separator = ", ";
            }
            sb.append(" ]");
        } else if (json.isString()) {
            sb.append(quoted(json.stringValue()));
        } else {
            // a number, true, false or null
            sb.append(json);
        }
    }

    private static String quoted(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // ---- the specification -------------------------------------------------------------------------------------

    /**
     * The specification as text: the managed domains and the tree of elements, sorted by name.
     */
    static String specification(Specification specification) {
        if (specification == null) {
            return "NO SPECIFICATION\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("MANAGED ").append(new TreeSet<>(specification.getManagedDomains())).append('\n');
        element(sb, "", specification.getRootElement());
        return sb.toString();
    }

    private static void element(StringBuilder sb, String indent, SpecificationElement element) {
        sb.append(indent).append(element.getName())
                .append(" elementType=").append(element.getSpecificationElementType())
                .append(" jsonTypes=").append(element.getJsonTypes() == null ? null : new TreeSet<>(element.getJsonTypes()))
                .append(" refTypes=").append(element.getRefTypes() == null ? null : new TreeSet<>(element.getRefTypes()))
                .append(" required=").append(element.getRequired() == null ? null : new TreeSet<>(element.getRequired()))
                .append(" isRequired=").append(element.isRequired())
                .append(" description=").append(oneLine(element.getDescription()))
                .append('\n');
        Map<String, SpecificationElement> properties = element.getProperties();
        if (properties != null) {
            for (Map.Entry<String, SpecificationElement> entry : new TreeMap<>(properties).entrySet()) {
                sb.append(indent).append("  property ").append(entry.getKey()).append('\n');
                element(sb, indent + "    ", entry.getValue());
            }
        }
        if (element.getItems() != null) {
            sb.append(indent).append("  items\n");
            element(sb, indent + "    ", element.getItems());
        }
    }

    private static String oneLine(String text) {
        return text == null ? "null" : "\"" + text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
