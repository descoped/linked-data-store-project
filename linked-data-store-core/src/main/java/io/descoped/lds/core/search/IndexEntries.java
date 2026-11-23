package io.descoped.lds.core.search;

import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.search.IndexEntry;
import io.descoped.lds.api.search.Language;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.api.specification.SpecificationElement;
import io.descoped.lds.api.specification.SpecificationElementType;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the search index is given of a version of a document: every text of the document, in the language it is
 * searched in, read with the model.
 * <ul>
 *     <li>A text in an object that has a {@code languageCode} and a {@code languageText} is in the language of the
 *     code; the code itself is not text.</li>
 *     <li>Every other text is in no language, and is matched word for word.</li>
 *     <li>A link (a field the model calls a link) and a date or a time (a text whose format in the model is
 *     {@code date-time}, {@code date} or {@code time}) are not text. Nor is a number, a boolean or a null.</li>
 *     <li>A field the model does not have is read as text when it holds text.</li>
 * </ul>
 * An entity type that the model marks as not searched ({@code @domain(searchable: false)}) has no entries.
 */
public final class IndexEntries {

    private static final String LANGUAGE_CODE = "languageCode";
    private static final String LANGUAGE_TEXT = "languageText";
    private static final Set<String> NOT_TEXT = Set.of("date-time", "date", "time");

    private final Specification specification;
    private final Set<String> notSearched;

    /**
     * Entries as the model says.
     *
     * @param specification the model
     * @param notSearched   the entity types the model marks as not searched
     */
    public IndexEntries(Specification specification, Set<String> notSearched) {
        this.specification = Objects.requireNonNull(specification);
        this.notSearched = Set.copyOf(notSearched);
    }

    /**
     * Whether the documents of an entity type are searched.
     *
     * @param entity the entity type
     * @return false when the model marks it as not searched
     */
    public boolean searched(String entity) {
        return !notSearched.contains(entity);
    }

    /**
     * The entry of a version that is written.
     *
     * @param document the document at the version, under its key
     * @return the entry, or empty when the entity type is not searched
     */
    public Optional<IndexEntry> written(JsonDocument document) {
        String entity = document.key().entity();
        if (!searched(entity)) {
            return Optional.empty();
        }
        Map<Language, List<String>> texts = new EnumMap<>(Language.class);
        SpecificationElement element = specification.getRootElement().getProperties().get(entity);
        if (document.jackson() != null) {
            walk(document.jackson(), element, texts);
        }
        return Optional.of(IndexEntry.written(document.key().namespace(), entity, document.key().id(),
                document.key().timestamp(), texts));
    }

    private static void walk(JsonNode node, SpecificationElement element, Map<Language, List<String>> texts) {
        if (element != null && element.getSpecificationElementType() == SpecificationElementType.REF) {
            return;
        }
        if (node.isString()) {
            if (isText(element)) {
                add(texts, Language.NONE, node.stringValue());
            }
        } else if (node.isArray()) {
            SpecificationElement items = element == null ? null : element.getItems();
            for (JsonNode item : node.values()) {
                walk(item, items, texts);
            }
        } else if (node.isObject()) {
            walkObject(node, element, texts);
        }
    }

    /*
     * A text field is text unless the model gives it the format of a date or a time.
     */
    private static boolean isText(SpecificationElement element) {
        return element == null || element.getFormat() == null || !NOT_TEXT.contains(element.getFormat());
    }

    private static void walkObject(JsonNode node, SpecificationElement element, Map<Language, List<String>> texts) {
        Language language = languageOf(node);
        for (var property : node.properties()) {
            String name = property.getKey();
            if (language != null && LANGUAGE_TEXT.equals(name)) {
                add(texts, language, property.getValue().stringValue());
            } else if (language == null || !LANGUAGE_CODE.equals(name)) {
                walk(property.getValue(), element == null ? null : element.getProperties().get(name), texts);
            }
        }
    }

    /*
     * The language of an object that is a text in a language: one with a languageCode and a languageText, both texts.
     */
    private static Language languageOf(JsonNode node) {
        JsonNode code = node.get(LANGUAGE_CODE);
        JsonNode text = node.get(LANGUAGE_TEXT);
        if (code != null && code.isString() && text != null && text.isString()) {
            return Language.of(code.stringValue());
        }
        return null;
    }

    private static void add(Map<Language, List<String>> texts, Language language, String text) {
        if (!text.isBlank()) {
            texts.computeIfAbsent(language, _ -> new ArrayList<>()).add(text);
        }
    }
}
