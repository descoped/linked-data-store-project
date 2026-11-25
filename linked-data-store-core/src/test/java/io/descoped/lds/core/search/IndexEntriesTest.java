package io.descoped.lds.core.search;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.search.IndexEntry;
import io.descoped.lds.api.search.Language;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.api.specification.SpecificationElement;
import io.descoped.lds.api.specification.SpecificationElementType;
import io.descoped.lds.core.schema.JsonSchema;
import io.descoped.lds.core.schema.JsonSchemaBuilder;
import io.descoped.lds.core.specification.SpecificationJsonSchemaBuilder;
import io.descoped.lds.core.specification.TestSpecificationElement;
import io.descoped.lds.graphql.directives.DomainDirective;
import io.descoped.lds.graphql.jsonschema.GraphQLToJsonConverter;
import io.descoped.lds.graphql.schemas.GraphQLSchemaBuilder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the index is given of a document is its texts, each in its language, read with the model.
 */
class IndexEntriesTest {

    private static final ZonedDateTime VERSION = ZonedDateTime.parse("2026-11-23T10:00:00Z");

    private static final GraphQLSchema SCHEMA = GraphQLSchemaBuilder.parseSchema(new SchemaParser().parse("""
            directive @domain(searchable: Boolean) on OBJECT
            directive @link(pagination: Boolean, reverseName: String) on FIELD_DEFINITION
            type Company @domain {
              name: String!
              founded: DateTime
              about: [Text]
              employees: [Person] @link
              size: Int
              open: Boolean
              tags: [String]
              meetings: [DateTime]
            }
            type Person @domain {
              name: String!
              born: Date
              wakes: Time
              address: Address
              employer: Company @link
            }
            type Address {
              street: String
              city: String!
              since: Date
              landlord: Person @link
            }
            type Secret @domain(searchable: false) {
              name: String
            }
            type Open @domain(searchable: true) {
              name: String
            }
            type Text {
              languageCode: String
              languageText: String!
            }
            type Query {
              x: String
            }
            scalar Date
            scalar DateTime
            scalar Time
            """));

    private static final IndexEntries ENTRIES = new IndexEntries(specification(SCHEMA), DomainDirective.notSearched(SCHEMA));

    @Test
    void eachTextIsInTheLanguageOfItsCodeAndTheRestInNone() {
        IndexEntry entry = written("Company", """
                {
                  "name": "Acme",
                  "founded": "1990-01-01T00:00:00Z",
                  "about": [
                    {"languageCode": "nb", "languageText": "Acme bygger husene i byen"},
                    {"languageCode": "en", "languageText": "Acme builds the houses of the town"},
                    {"languageCode": "nn-NO", "languageText": "Acme byggjer husa"},
                    {"languageCode": "es", "languageText": "Acme construye casas"},
                    {"languageCode": 7, "languageText": "a code that is not a text"}
                  ],
                  "employees": ["/Person/ola"],
                  "size": 120,
                  "open": true,
                  "tags": ["tools", "  "],
                  "meetings": ["2026-11-23T10:00:00Z"],
                  "extra": {"note": "a field the model does not have"},
                  "nothing": null
                }""");

        assertThat(entry.texts()).isEqualTo(Map.of(
                Language.NONE, List.of("Acme", "Acme construye casas", "a code that is not a text", "tools",
                        "a field the model does not have"),
                Language.NORWEGIAN, List.of("Acme bygger husene i byen", "Acme byggjer husa"),
                Language.ENGLISH, List.of("Acme builds the houses of the town")));
        assertThat(entry.deleted()).isFalse();
        assertThat(List.of(entry.namespace(), entry.entity(), entry.id())).containsExactly("ns", "Company", "acme");
        assertThat(entry.version()).isEqualTo(VERSION);
    }

    @Test
    void aLinkADateAndATimeAreNotText() {
        IndexEntry entry = written("Person", """
                {
                  "name": "Ola",
                  "born": "1980-05-17",
                  "wakes": "07:00:00Z",
                  "address": {"street": "Storgata 1", "city": "Oslo"},
                  "employer": "/Company/acme"
                }""");

        assertThat(entry.texts()).isEqualTo(Map.of(Language.NONE, List.of("Ola", "Storgata 1", "Oslo")));
    }

    /**
     * The fields of a single embedded object are known as those of a list of them are.
     */
    @Test
    void aLinkAndADateInASingleEmbeddedObjectAreNotText() {
        IndexEntry entry = written("Person", """
                {
                  "name": "Ola",
                  "address": {"street": "Storgata 1", "city": "Oslo", "since": "2001-01-01", "landlord": "/Person/kari"}
                }""");

        assertThat(entry.texts()).isEqualTo(Map.of(Language.NONE, List.of("Ola", "Storgata 1", "Oslo")));
    }

    @Test
    void anEntityTypeMarkedAsNotSearchedHasNoEntry() {
        assertThat(DomainDirective.notSearched(SCHEMA)).containsExactly("Secret");
        assertThat(ENTRIES.searched("Secret")).isFalse();
        assertThat(ENTRIES.searched("Open")).isTrue();
        assertThat(ENTRIES.searched("Company")).isTrue();
        assertThat(ENTRIES.written(document("Secret", "{\"name\": \"hidden\"}"))).isEmpty();
    }

    @Test
    void aLanguageCodeIsReadInAnyCaseAndWithARegion() {
        assertThat(Language.of("nb")).isEqualTo(Language.NORWEGIAN);
        assertThat(Language.of("NN")).isEqualTo(Language.NORWEGIAN);
        assertThat(Language.of("no")).isEqualTo(Language.NORWEGIAN);
        assertThat(Language.of("nb-NO")).isEqualTo(Language.NORWEGIAN);
        assertThat(Language.of("sv_SE")).isEqualTo(Language.SWEDISH);
        assertThat(Language.of(" en ")).isEqualTo(Language.ENGLISH);
        assertThat(Language.of("de-CH")).isEqualTo(Language.GERMAN);
        assertThat(Language.of("fr")).isEqualTo(Language.FRENCH);
        assertThat(Language.of("es")).isEqualTo(Language.NONE);
        assertThat(Language.of("")).isEqualTo(Language.NONE);
        assertThat(Language.of(null)).isEqualTo(Language.NONE);
    }

    /**
     * Entries without a model: every text of a document is in no language, unless it has a language code.
     *
     * @return the entries
     */
    static IndexEntries withoutAModel() {
        SpecificationElement root = new TestSpecificationElement("root", SpecificationElementType.ROOT, Set.of(),
                List.of(), Set.of(), Map.of(), null);
        return new IndexEntries(new Specification() {
            @Override
            public SpecificationElement getRootElement() {
                return root;
            }

            @Override
            public Set<String> getManagedDomains() {
                return Set.of();
            }
        }, Set.of());
    }

    private static IndexEntry written(String entity, String json) {
        return ENTRIES.written(document(entity, json)).orElseThrow();
    }

    private static JsonDocument document(String entity, String json) {
        return new JsonDocument(new DocumentKey("ns", entity, "acme", VERSION), JsonTools.toJsonNode(json));
    }

    private static Specification specification(GraphQLSchema schema) {
        Specification specification = null;
        JsonSchema jsonSchema = null;
        for (Map.Entry<String, ObjectNode> item : new GraphQLToJsonConverter(schema).createSpecification(schema).entrySet()) {
            jsonSchema = new JsonSchemaBuilder(jsonSchema, item.getKey(), item.getValue()).build();
            specification = SpecificationJsonSchemaBuilder.createBuilder(jsonSchema).build();
        }
        return specification;
    }
}
