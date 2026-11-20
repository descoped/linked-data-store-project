package io.descoped.lds.core.domain.reference;

import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.api.specification.SpecificationElement;
import io.descoped.lds.core.domain.resource.ResourceContext;
import io.descoped.lds.core.domain.resource.ResourceElement;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Iterator;

public class ReferenceJsonHelper {

    final Specification specification;
    final ResourceElement resourceElement;
    final SpecificationElement root;

    public ReferenceJsonHelper(Specification specification, ResourceElement resourceElement) {
        this.specification = specification;
        this.resourceElement = resourceElement;
        this.root = specification.getRootElement();
    }

    public boolean deleteReferenceJson(ResourceContext resourceContext, JsonNode rootNode) {
        // TODO Support array-navigation
        return resourceContext.navigateAndCreateJson(rootNode, t -> {
            String referencePropertyName = t.resourceElement.name();
            String referenceValue = t.resourceElement.id();
            if (t.resourceElement.getSpecificationElement().getJsonTypes().contains("array")) {
                ObjectNode refParentNode = t.jsonObject;
                if (!refParentNode.has(referencePropertyName)) {
                    return false;
                }
                ArrayNode refNode = (ArrayNode) refParentNode.get(referencePropertyName);
                for (int i = 0; i < refNode.size(); i++) {
                    String idref = JsonTools.text(refNode.get(i));
                    if (referenceValue.equals(idref)) {
                        refNode.remove(i);
                        return true;
                    }
                }
                return false; // not found
            } else {
                ObjectNode refParentNode = t.jsonObject;
                String existingValue = JsonTools.text(refParentNode.get(referencePropertyName));
                if (referenceValue.equals(existingValue)) {
                    refParentNode.remove(referencePropertyName);
                    return true;
                }
                return false;
            }
        });
    }

    public boolean createReferenceJson(ResourceContext resourceContext, JsonNode rootNode) {
        // TODO Support array-navigation
        return resourceContext.navigateAndCreateJson(rootNode, t -> {
            String referencePropertyName = t.resourceElement.name();
            String referenceValue = t.resourceElement.id();
            if (t.resourceElement.getSpecificationElement().getJsonTypes().contains("array")) {
                ObjectNode refParentNode = t.jsonObject;
                if (!refParentNode.has(referencePropertyName)) {
                    refParentNode.putArray(referencePropertyName);
                }
                ArrayNode refNode = (ArrayNode) refParentNode.get(referencePropertyName);
                // scan array for existing identical reference value
                Iterator<JsonNode> it = refNode.iterator();
                while (it.hasNext()) {
                    JsonNode next = it.next();
                    if (next.isNull()) {
                        continue;
                    }
                    if (referenceValue.equals(JsonTools.text(next))) {
                        // value already in array
                        return false;
                    }
                }
                refNode.add(referenceValue);
                return true;
            } else {
                ObjectNode refParentNode = t.jsonObject;
                if (!refParentNode.has(referencePropertyName)
                        || refParentNode.get(referencePropertyName).isNull()) {
                    // not already set
                    refParentNode.put(referencePropertyName, referenceValue);
                    return true;
                } else if (!JsonTools.text(refParentNode.get(referencePropertyName)).equals(referenceValue)) {
                    // change value
                    refParentNode.put(referencePropertyName, referenceValue);
                    return true;
                }
                // already set to referenceValue value
                return false;
            }
        });
    }
}
