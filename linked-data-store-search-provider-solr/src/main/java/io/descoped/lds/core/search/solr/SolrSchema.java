package io.descoped.lds.core.search.solr;

import io.descoped.lds.api.search.Language;
import org.apache.solr.common.util.NamedList;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The schema LDS gives its Solr core: the field types and the fields of an entry, the same for every model, so a
 * model that changes needs nothing of Solr. It is put in the first time LDS uses the core, and again when it has
 * changed, which LDS sees by a mark it keeps in the configuration of the core ({@value #MARK}).
 */
final class SolrSchema {

    /**
     * The user property of the core that holds the digest of the schema LDS last put in.
     */
    static final String MARK = "lds.schema";

    static final String ID = "id";
    static final String NAMESPACE = "lds_namespace";
    static final String ENTITY = "lds_entity";
    static final String DOCUMENT_ID = "lds_id";
    static final String DOCUMENT = "lds_doc";
    static final String VERSION = "lds_version";
    /**
     * The version as it was given, to the nanosecond, which a hit gives back: {@link #VERSION} is in microseconds,
     * and a store may keep a version to the nanosecond (Neo4j does), so that a read at the microsecond would be
     * before it.
     */
    static final String EXACT_VERSION = "lds_version_exact";
    static final String DELETED = "lds_deleted";
    static final String INDEXED = "lds_indexed";

    /**
     * The field of the texts of each language.
     */
    static final Map<Language, String> TEXT_FIELDS = textFields();

    private static final String TEXT_FIELD = "solr.TextField";
    private static final String STRING = "lds_string";
    private static final String LONG = "lds_long";
    private static final String BOOLEAN = "lds_boolean";
    private static final String DATE = "lds_date";
    private static final String CLASS = "class";
    private static final String NAME = "name";
    private static final String TYPE = "type";
    private static final String DOC_VALUES = "docValues";
    private static final String STORED = "stored";
    private static final String INDEXED_ATTRIBUTE = "indexed";
    private static final String MULTI_VALUED = "multiValued";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<ObjectNode> fieldTypes = fieldTypes();
    private final List<ObjectNode> fields = fields();
    private final String digest = digest();

    private static Map<Language, String> textFields() {
        Map<Language, String> fields = new EnumMap<>(Language.class);
        fields.put(Language.NONE, "lds_text");
        fields.put(Language.NORWEGIAN, "lds_text_no");
        fields.put(Language.SWEDISH, "lds_text_sv");
        fields.put(Language.ENGLISH, "lds_text_en");
        fields.put(Language.GERMAN, "lds_text_de");
        fields.put(Language.FRENCH, "lds_text_fr");
        return Map.copyOf(fields);
    }

    /*
     * The text types have no stop words, so a phrase is kept whole, and depend on no file of the configuration
     * the core was made from. The stemmers are those Solr's own text_<language> types use, each light: the
     * Norwegian one with both bokmål and nynorsk ("gutane" and "guttene" are both "gutt"/"gut" as their written form
     * is), the Swedish one, which also takes the definite form of a singular ("huset", "flickan").
     */
    private static List<ObjectNode> fieldTypes() {
        List<ObjectNode> types = new ArrayList<>();
        types.add(type(STRING, "solr.StrField").put(DOC_VALUES, true).put("sortMissingLast", true));
        types.add(type(LONG, "solr.LongPointField").put(DOC_VALUES, true));
        types.add(type(BOOLEAN, "solr.BoolField").put(DOC_VALUES, true));
        types.add(type(DATE, "solr.DatePointField").put(DOC_VALUES, true));
        types.add(text(Language.NONE));
        types.add(text(Language.NORWEGIAN, filter("solr.NorwegianLightStemFilterFactory").put("variant", "no")));
        types.add(text(Language.SWEDISH, filter("solr.SwedishLightStemFilterFactory")));
        types.add(text(Language.ENGLISH, filter("solr.EnglishPossessiveFilterFactory"),
                filter("solr.PorterStemFilterFactory")));
        types.add(text(Language.GERMAN, filter("solr.GermanNormalizationFilterFactory"),
                filter("solr.GermanLightStemFilterFactory")));
        // l'école, d'Acme: the article is taken off before the word is lowered and stemmed
        ObjectNode french = text(Language.FRENCH, filter("solr.FrenchLightStemFilterFactory"));
        ((ArrayNode) french.get("analyzer").get("filters")).insert(0,
                filter("solr.ElisionFilterFactory").put("ignoreCase", true));
        types.add(french);
        return List.copyOf(types);
    }

    private static List<ObjectNode> fields() {
        List<ObjectNode> fields = new ArrayList<>();
        for (String name : List.of(NAMESPACE, ENTITY, DOCUMENT_ID, DOCUMENT)) {
            fields.add(field(name, STRING));
        }
        fields.add(field(VERSION, LONG));
        fields.add(field(EXACT_VERSION, STRING).put(INDEXED_ATTRIBUTE, false));
        fields.add(field(DELETED, BOOLEAN));
        // the time Solr took the entry, which Solr sets itself
        fields.add(field(INDEXED, DATE).put("default", "NOW"));
        for (Language language : Language.values()) {
            fields.add(JSON.createObjectNode().put(NAME, TEXT_FIELDS.get(language)).put(TYPE, textType(language))
                    .put(INDEXED_ATTRIBUTE, true).put(STORED, false).put(MULTI_VALUED, true));
        }
        return List.copyOf(fields);
    }

    private static ObjectNode type(String name, String solrClass) {
        return JSON.createObjectNode().put(NAME, name).put(CLASS, solrClass);
    }

    private static ObjectNode field(String name, String type) {
        return JSON.createObjectNode().put(NAME, name).put(TYPE, type).put(INDEXED_ATTRIBUTE, true).put(STORED, true)
                .put(MULTI_VALUED, false);
    }

    private static String textType(Language language) {
        return "lds_text_" + language.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static ObjectNode text(Language language, ObjectNode... stemming) {
        ObjectNode analyzer = JSON.createObjectNode();
        analyzer.set("tokenizer", JSON.createObjectNode().put(CLASS, "solr.StandardTokenizerFactory"));
        ArrayNode filters = analyzer.putArray("filters");
        filters.add(filter("solr.LowerCaseFilterFactory"));
        for (ObjectNode filter : stemming) {
            filters.add(filter);
        }
        ObjectNode type = type(textType(language), TEXT_FIELD).put("positionIncrementGap", "100");
        type.set("analyzer", analyzer);
        return type;
    }

    private static ObjectNode filter(String solrClass) {
        return JSON.createObjectNode().put(CLASS, solrClass);
    }

    private String digest() {
        try {
            byte[] bytes = (JSON.writeValueAsString(fieldTypes) + JSON.writeValueAsString(fields))
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    /**
     * The digest of the schema, kept as {@value #MARK} in the core when the schema is in.
     *
     * @return 16 hex digits
     */
    String mark() {
        return digest;
    }

    /**
     * The one request of the Schema API that adds what the core does not have and replaces what it has (which may
     * be different): every field type before the fields.
     *
     * @param typesInTheCore  the names of the field types the core has
     * @param fieldsInTheCore the names of the fields the core has
     * @return the body of the request
     */
    String changes(Set<String> typesInTheCore, Set<String> fieldsInTheCore) {
        ObjectNode body = JSON.createObjectNode();
        for (ObjectNode type : fieldTypes) {
            body.withArray(typesInTheCore.contains(type.get(NAME).asString()) ? "replace-field-type" : "add-field-type")
                    .add(type);
        }
        for (ObjectNode field : fields) {
            body.withArray(fieldsInTheCore.contains(field.get(NAME).asString()) ? "replace-field" : "add-field")
                    .add(field);
        }
        return JSON.writeValueAsString(body);
    }

    /**
     * Whether a field type or a field of the schema is in the core already, so that putting the schema in again
     * changes it, and what was indexed before is searched as it was indexed until the index is rebuilt.
     *
     * @param typesInTheCore  the names of the field types the core has
     * @param fieldsInTheCore the names of the fields the core has
     * @return whether one is
     */
    boolean replaces(Set<String> typesInTheCore, Set<String> fieldsInTheCore) {
        return fieldTypes.stream().anyMatch(type -> typesInTheCore.contains(type.get(NAME).asString()))
                || fields.stream().anyMatch(field -> fieldsInTheCore.contains(field.get(NAME).asString()));
    }

    /**
     * A value of an answer of Solr, which is a NamedList or a Map at each level depending on how it was read.
     *
     * @param node a level of the answer
     * @param key  the name of the value
     * @return the value, or null
     */
    static Object get(Object node, String key) {
        if (node instanceof NamedList<?> list) {
            return list.get(key);
        }
        if (node instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }
}
