package io.descoped.lds.api.specification;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface SpecificationElement {

    String getName();

    String getDescription();

    SpecificationElement getParent();

    SpecificationElementType getSpecificationElementType();

    Set<String> getJsonTypes();

    List<SpecificationValidator> getValidators();

    Set<String> getRefTypes();

    /**
     * The format of a text value, as the JSON Schema of the model gives it ({@code date-time}, {@code date},
     * {@code time}, ...).
     *
     * @return the format, or null when the model gives none
     */
    String getFormat();

    Map<String, SpecificationElement> getProperties();

    SpecificationElement getItems();

    default boolean isRequired() {
        SpecificationElement parent = getParent();
        if (parent != null) {
            return parent.getRequired().contains(getName());
        }
        return false;
    }

    Set<String> getRequired();
}
