package io.descoped.lds.core.domain.resource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public class ResourceJsonTraversalTuple {

    public final ResourceElement resourceElement;
    public final ObjectNode jsonObject;

    ResourceJsonTraversalTuple(ResourceElement resourceElement, JsonNode jsonObject) {
        this.resourceElement = resourceElement;
        // array-navigation is not supported, so the parent is an object
        if (!jsonObject.isObject()) {
            throw new UnsupportedOperationException("array-navigation not supported");
        }
        this.jsonObject = (ObjectNode) jsonObject;
    }
}
