package io.descoped.lds.graphql.schemas;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.errors.SchemaProblem;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.core.persistence.StoreReads;
import io.descoped.lds.core.schema.JsonSchema;
import io.descoped.lds.core.schema.JsonSchemaBuilder;
import io.descoped.lds.core.specification.JsonSchemaBasedSpecification;
import io.descoped.lds.core.specification.SpecificationJsonSchemaBuilder;
import io.descoped.lds.graphql.jsonschema.GraphQLToJsonConverter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What LDS makes of a GraphQL schema file, compared with what it made of the same file on graphql-java 12.
 * <p>
 * LDS was written for graphql-java 12 and moved to 26 without a change in what it gives: the schema it serves
 * (with and without search), the JSON Schema of every entity and the specification. The files in
 * {@code src/test/resources/graphql-golden} were written on 12 with the printer in {@link CanonicalText}; this
 * test builds the same things as the application does and compares. A difference is a change in what LDS gives
 * its clients, and is to be understood before an expected file is changed.
 * <p>
 * The JSON Schemas and specifications hold the faults of the walk corrected since, each with its own test: a single
 * field of a type without {@code @domain} refers to its definition, and the items of a list of an enum are its names
 * ({@code GraphQLModelValidationTest}).
 */
class GraphQLSchemaGoldenTest {

    /* The threads a search reads the store on; a schema is only built here, so none is read. */
    private static final StoreReads STORE_READS = new StoreReads(1);

    @AfterAll
    static void closeStoreReads() throws InterruptedException {
        STORE_READS.close(Duration.ofSeconds(5));
    }

    private static final String GOLDEN = "graphql-golden/";

    /**
     * The schema files: the two of the tests of core, and schemas written for this test.
     */
    private static final Map<String, String> SCHEMA_FILES = schemaFiles();

    private static Map<String, String> schemaFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        // the schema of the tests: an empty Query in the file, and scalars that are declared and not used
        files.put("file-gqlschema", "gqlschema/schema.graphql");
        // interfaces and unions
        files.put("file-abstract", "spec/abstract/graphqlschemas/schema.graphql");
        // links of every shape, every scalar, an enum, descriptions
        files.put("syn-links", GOLDEN + "syn-links.graphql");
        // types that are not domains, embedded in a domain and in each other, with links of their own
        files.put("syn-embedded", GOLDEN + "syn-embedded.graphql");
        // an embedded type that several domains share
        files.put("syn-shared-embedded", GOLDEN + "syn-shared-embedded.graphql");
        // interfaces, a union, and links to them
        files.put("syn-abstract", GOLDEN + "syn-abstract.graphql");
        // links that name their reverse, and a reverse link written in the file
        files.put("syn-reverse", GOLDEN + "syn-reverse.graphql");
        // a Query type with no fields
        files.put("syn-empty-query", GOLDEN + "syn-empty-query.graphql");
        // directives declared with default values
        files.put("syn-directive-defaults", GOLDEN + "syn-directive-defaults.graphql");
        // a deprecated field, arguments of fields, an input type
        files.put("syn-deprecated", GOLDEN + "syn-deprecated.graphql");
        return files;
    }

    static List<String> schemas() {
        return List.copyOf(SCHEMA_FILES.keySet());
    }

    @ParameterizedTest
    @MethodSource("schemas")
    void theSchemaIsWhatItWasOnGraphqlJava12(String label) {
        GraphQLSchema schema = build(SCHEMA_FILES.get(label), false);

        assertSameAsFile(label + ".plain.schema.txt", CanonicalText.schema(schema, Set.of()));
    }

    @ParameterizedTest
    @MethodSource("schemas")
    void theSchemaWithSearchIsWhatItWasOnGraphqlJava12(String label) {
        GraphQLSchema schema = build(SCHEMA_FILES.get(label), true);

        assertSameAsFile(label + ".search.schema.txt", CanonicalText.schema(schema, Set.of()));
    }

    @ParameterizedTest
    @MethodSource("schemas")
    void theJsonSchemaIsWhatItWasOnGraphqlJava12(String label) {
        assertSameAsFile(label + ".jsonschema.txt", CanonicalText.jsonSchema(jsonSchema(SCHEMA_FILES.get(label))));
    }

    @ParameterizedTest
    @MethodSource("schemas")
    void theSpecificationIsWhatItWasOnGraphqlJava12(String label) {
        Specification specification = specification(jsonSchema(SCHEMA_FILES.get(label)));

        assertSameAsFile(label + ".specification.txt", CanonicalText.specification(specification));
    }

    /**
     * The one known difference. A schema file may define directives of its own. On graphql-java 12 the schema LDS
     * served did not define them, though it had them on its types and fields; graphql-java no longer builds such
     * a schema, so now they are defined. Everything else is as it was.
     */
    @Test
    void directivesTheSchemaFileDefinesAreDefinedAndTheRestIsWhatItWas() {
        String schemaFile = GOLDEN + "syn-two-directives.graphql";
        for (boolean search : new boolean[]{false, true}) {
            GraphQLSchema schema = build(schemaFile, search);

            assertThat(schema.getDirective("alpha")).isNotNull();
            assertThat(schema.getDirective("zeta")).isNotNull();
            assertSameAsFile("syn-two-directives." + (search ? "search" : "plain") + ".schema.txt",
                    CanonicalText.schema(schema, Set.of("alpha", "zeta")));
        }
        Map<String, ObjectNode> jsonSchema = jsonSchema(schemaFile);
        assertSameAsFile("syn-two-directives.jsonschema.txt", CanonicalText.jsonSchema(jsonSchema));
        assertSameAsFile("syn-two-directives.specification.txt", CanonicalText.specification(specification(jsonSchema)));
    }

    /**
     * A list of links to an interface is something LDS has never built a schema for: it fails as it did. The JSON
     * Schema and the specification are made.
     */
    @Test
    void aListOfLinksToAnInterfaceIsRefusedAsItWas() {
        String schemaFile = GOLDEN + "syn-link-to-interface.graphql";

        assertThatThrownBy(() -> build(schemaFile, false)).isInstanceOf(ClassCastException.class);
        assertThatThrownBy(() -> build(schemaFile, true)).isInstanceOf(ClassCastException.class);
        Map<String, ObjectNode> jsonSchema = jsonSchema(schemaFile);
        assertSameAsFile("syn-link-to-interface.jsonschema.txt", CanonicalText.jsonSchema(jsonSchema));
        assertSameAsFile("syn-link-to-interface.specification.txt", CanonicalText.specification(specification(jsonSchema)));
    }

    @Test
    void anArgumentTheDirectiveDoesNotDeclareIsRefused() {
        String schemaFile = GOLDEN + "syn-undeclared-arg.graphql";

        assertThatThrownBy(() -> parse(schemaFile))
                .isInstanceOf(SchemaProblem.class)
                .hasMessageContaining("use an unknown argument 'pagination' on directive 'link'");
    }

    // ---- as the application does (UndertowApplication) -------------------------------------------------------

    private static GraphQLSchema parse(String schemaFile) {
        return GraphQLSchemaBuilder.parseSchema(new SchemaParser().parse(file(schemaFile)));
    }

    private static GraphQLSchema build(String schemaFile, boolean search) {
        GraphQLSchemaBuilder schemaBuilder = new GraphQLSchemaBuilder("ns", stub(RxJsonPersistence.class, "persistence"),
                search ? stub(SearchIndex.class, "searchIndex") : null, search ? STORE_READS : null);
        return schemaBuilder.getGraphQL(parse(schemaFile));
    }

    private static Map<String, ObjectNode> jsonSchema(String schemaFile) {
        GraphQLSchema schema = parse(schemaFile);
        return new GraphQLToJsonConverter(schema).createSpecification(schema);
    }

    private static Specification specification(Map<String, ObjectNode> jsonMap) {
        JsonSchemaBasedSpecification specification = null;
        JsonSchema jsonSchema = null;
        for (Map.Entry<String, ObjectNode> item : jsonMap.entrySet()) {
            jsonSchema = new JsonSchemaBuilder(jsonSchema, item.getKey(), item.getValue()).build();
            specification = SpecificationJsonSchemaBuilder.createBuilder(jsonSchema).build();
        }
        return specification;
    }

    /**
     * Something to build a schema with: the schema only holds it, and its name is what the printer writes for it.
     */
    private static <T> T stub(Class<T> type, String name) {
        return type.cast(Proxy.newProxyInstance(GraphQLSchemaGoldenTest.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> name;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
    }

    // ---- the comparison ----------------------------------------------------------------------------------------

    private static void assertSameAsFile(String expectedFile, String actual) {
        String[] expectedLines = resource(GOLDEN + expectedFile).split("\\R", -1);
        String[] actualLines = actual.split("\\R", -1);
        for (int i = 0; i < Math.max(expectedLines.length, actualLines.length); i++) {
            String expectedLine = i < expectedLines.length ? expectedLines[i] : "<the file ends here>";
            String actualLine = i < actualLines.length ? actualLines[i] : "<the text ends here>";
            assertThat(actualLine)
                    .as("%s%s differs from what LDS makes now, first at line %d", GOLDEN, expectedFile, i + 1)
                    .isEqualTo(expectedLine);
        }
    }

    private static File file(String resource) {
        URL url = GraphQLSchemaGoldenTest.class.getClassLoader().getResource(resource);
        assertThat(url).as("the test resource %s", resource).isNotNull();
        try {
            return new File(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String resource(String resource) {
        try (InputStream in = GraphQLSchemaGoldenTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("the test resource %s", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
