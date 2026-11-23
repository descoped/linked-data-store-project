package io.descoped.lds.core.specification;

import io.descoped.lds.api.specification.SpecificationElement;
import io.descoped.lds.api.specification.SpecificationElementType;
import io.descoped.lds.api.specification.SpecificationValidator;
import io.descoped.lds.core.schema.JsonSchema;
import io.descoped.lds.core.schema.JsonSchemaDefinitionElement;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

class SpecificationElementBuilder {

    private final JsonSchemaDefinitionElement schemaElement;

    private JsonSchema jsonSchema;
    private SpecificationElement specificationElement;
    private String name;
    private String description;
    private SpecificationElement parent;
    private SpecificationElementType specificationElementType;
    private Set<String> refTypes;
    private Set<String> jsonTypes;
    private Set<String> required = Collections.emptySet();

    SpecificationElementBuilder(JsonSchemaDefinitionElement schemaElement) {
        this.schemaElement = schemaElement;
    }

    SpecificationElement build() {
        return new ImmutableSpecificationElement(this);
    }

    SpecificationElementBuilder specificationElement(SpecificationElement specificationElement) {
        this.specificationElement = specificationElement;
        return this;
    }

    String name() {
        return name;
    }

    String format() {
        return schemaElement == null ? null : schemaElement.format;
    }

    SpecificationElementBuilder name(String name) {
        this.name = name;
        return this;
    }

    String description() {
        if (this.schemaElement != null) {
            return this.schemaElement.description;
        } else {
            return description;
        }
    }

    SpecificationElementBuilder description(String description) {
        this.description = description;
        return this;
    }

    SpecificationElement parent() {
        return parent;
    }

    SpecificationElementBuilder parent(SpecificationElement parent) {
        this.parent = parent;
        return this;
    }

    SpecificationElementBuilder required(Set<String> required) {
        this.required = Objects.requireNonNull(required);
        return this;
    }

    SpecificationElementBuilder specificationElementType(SpecificationElementType specificationElementType) {
        this.specificationElementType = specificationElementType;
        return this;
    }

    SpecificationElementType specificationElementType() {
        return specificationElementType;
    }

    SpecificationElementBuilder jsonSchema(JsonSchema jsonSchema) {
        this.jsonSchema = jsonSchema;
        return this;
    }

    SpecificationElementBuilder jsonTypes(Set<String> jsonTypes) {
        this.jsonTypes = jsonTypes;
        return this;
    }

    Set<String> jsonTypes() {
        if (jsonTypes != null) {
            return jsonTypes;
        }
        return new LinkedHashSet<>(Arrays.asList(schemaElement.types));
    }

    SpecificationElementBuilder refTypes(Set<String> refTypes) {
        this.refTypes = refTypes;
        return this;
    }

    Set<String> refTypes() {
        return refTypes;
    }

    /*
     * The entities a property may link to, or empty when the property is not a link (has no _link_property_).
     */
    Optional<Set<String>> linkedDomains(String property) {
        String linkPropertyName = "_link_property_" + property;
        if (!schemaElement.properties.containsKey(linkPropertyName)) {
            return Optional.empty();
        }
        JsonSchemaDefinitionElement jsde = schemaElement.properties.get(linkPropertyName);
        return Optional.of(new LinkedHashSet<>(jsde.properties.keySet()));
    }

    List<SpecificationValidator> validators() {
        return null;
    }

    SpecificationElement items() {
        if (schemaElement == null || schemaElement.items == null) {
            return null;
        }
        // Name is required for graphql implementation.
        // See comment in JsonSchemaDefinitionElement.java.
        String itemsName = schemaElement.items.name != null ? schemaElement.items.name : "";
        return new SpecificationElementBuilder(schemaElement.items)
                .name(itemsName)
                .parent(specificationElement)
                .build();
    }

    Map<String, SpecificationElement> properties() {
        if (SpecificationElementType.ROOT.equals(specificationElementType)) {
            return rootProperties();
        }
        Map<String, SpecificationElement> map = new LinkedHashMap<>();
        if (schemaElement == null || schemaElement.properties == null || schemaElement.properties.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, JsonSchemaDefinitionElement> e : schemaElement.properties.entrySet()) {
            if (e.getKey().startsWith("_link_property_")) {
                continue;
            }
            Set<String> linkedDomains = linkedDomains(e.getKey()).orElse(null);
            SpecificationElementBuilder childBuilder = new SpecificationElementBuilder(e.getValue())
                    .name(e.getKey())
                    .description(e.getValue().description)
                    .parent(specificationElement)
                    .specificationElementType(SpecificationElementType.EMBEDDED)
                    .refTypes(linkedDomains);
            if (linkedDomains != null) {
                childBuilder.specificationElementType(SpecificationElementType.REF);
            }
            SpecificationElement child = childBuilder.build();
            map.put(e.getKey(), child);
        }
        return map;
    }

    /*
     * The entities and the embedded types of the root: a definition that is the schema of an entity is managed.
     */
    private Map<String, SpecificationElement> rootProperties() {
        Map<String, SpecificationElement> map = new LinkedHashMap<>();
        if (jsonSchema == null) {
            return map;
        }
        for (Map.Entry<String, JsonSchemaDefinitionElement> entry : jsonSchema.getDefinitions().entrySet()) {
            SpecificationElementType type;
            if (jsonSchema.getSchemaNames().contains(entry.getKey())) {
                type = SpecificationElementType.MANAGED;
            } else {
                type = SpecificationElementType.EMBEDDED;
            }
            SpecificationElement managed = new SpecificationElementBuilder(entry.getValue())
                    .name(entry.getKey())
                    .parent(specificationElement)
                    .specificationElementType(type)
                    .required(entry.getValue().required)
                    .build();
            map.put(entry.getKey(), managed);
        }
        return map;
    }

    public Set<String> required() {
        return required != null ? required : Collections.emptySet();
    }
}
