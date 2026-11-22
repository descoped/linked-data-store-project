package io.descoped.lds.core.domain.resource;

import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.api.specification.SpecificationElement;
import io.descoped.lds.api.specification.SpecificationElementType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;

/**
 * The resource a request path names. A path does not navigate into the elements of a json array.
 */
public class ResourceContext {

    /**
     * The longest id of a document, in characters.
     */
    public static final int MAX_ID_LENGTH = 512;

    /**
     * The resource a path names.
     *
     * @param requestPath the path as it was sent, not decoded: each segment is decoded once here, and a {@code +} is a
     *                    plus
     * @throws ResourceException when the path names no resource, or an id is not one LDS takes
     */
    public static ResourceContext createResourceContext(Specification specification, String requestPath, ZonedDateTime timestamp) throws ResourceException {
        SpecificationElement specificationRootElement = specification.getRootElement();
        String[] pathParts = requestPath.substring(1).split("/");
        if (pathParts.length < 2) {
            throw new ResourceException("Not a valid resource. The resource path must contain at least namespace, name, and id of managed resource");
        }
        String namespace = urlDecode(pathParts[0]);
        String managedResourceName = urlDecode(pathParts[1]);
        if (!specificationRootElement.getProperties().containsKey(managedResourceName)) {
            throw new ResourceException("Not a managed resource name: \"" + managedResourceName + "\"");
        }
        String managedResourceId = null;
        if (pathParts.length > 2) {
            managedResourceId = urlDecode(pathParts[2]);
            if (managedResourceId.trim().isEmpty()) {
                throw new ResourceException("Managed resource id cannot be empty.");
            }
            checkId(managedResourceId);
        }
        SpecificationElement managedSpecificationElement = specificationRootElement.getProperties().get(managedResourceName);
        ResourceElement secondElement = navigateSpecAndCreateResourceContext(managedSpecificationElement, 3, pathParts);
        ResourceElement firstElement = new ResourceElement(managedSpecificationElement, managedResourceName, managedResourceId, secondElement);
        return new ResourceContext(namespace, firstElement, timestamp);
    }

    private static ResourceElement navigateSpecAndCreateResourceContext(SpecificationElement parentSpecificationElement, int depth, String[] resourcePath) {
        if (depth > (resourcePath.length - 1)) {
            // managed or embedded resource
            return null;
        }
        String pathElement = urlDecode(resourcePath[depth]);
        if (parentSpecificationElement.getProperties() == null
                || !parentSpecificationElement.getProperties().containsKey(pathElement)) {
            throw new ResourceException("Not a valid path element: \"" + pathElement + "\"");
        }
        SpecificationElement matchedElement = parentSpecificationElement.getProperties().get(pathElement);
        if (SpecificationElementType.REF.equals(matchedElement.getSpecificationElementType())) {
            if ((depth + 2) > (resourcePath.length - 1)) {
                if ((depth + 1) > (resourcePath.length - 1)) {
                    // embedded resource with linked-data value(s)
                    return new ResourceElement(matchedElement, pathElement, null, null);
                }
                throw new ResourceException("Reference resource path-element: \"" + pathElement + "\" must be part of pattern ending like: \"/" + pathElement + "/<managed-domain>/<id>\"");
            }
            if ((depth + 2) < (resourcePath.length - 1)) {
                throw new ResourceException("Resource path cannot navigate through REF property: \"" + pathElement + "\"");
            }
            // (depth + 1) == (resourcePath.length - 1)
            // linked-data resource
            String linkedManagedDomain = urlDecode(resourcePath[depth + 1]);
            if (!matchedElement.getRefTypes().contains(linkedManagedDomain)) {
                throw new ResourceException("Reference resource does not support link to managed-domain \"" + linkedManagedDomain + "\". Must be one of: " + matchedElement.getRefTypes());
            }
            String linkedResourceId = urlDecode(resourcePath[depth + 2]);
            checkId(linkedResourceId);
            String referenceValue = "/" + linkedManagedDomain + "/" + linkedResourceId;
            return new ResourceElement(matchedElement, pathElement, referenceValue, null);
        }
        return new ResourceElement(matchedElement, pathElement, null, navigateSpecAndCreateResourceContext(matchedElement, depth + 1, resourcePath));
    }

    private static ResourceType getResourceType(ResourceElement firstElement) {
        if (!firstElement.hasNext()) {
            return ResourceType.MANAGED;
        }
        ResourceElement lastElement = firstElement.next();
        while (lastElement.hasNext()) {
            lastElement = lastElement.next();
        }
        if (lastElement.id() != null) {
            return ResourceType.REFERENCE;
        }
        return ResourceType.EMBEDDED;
    }

    private final String namespace;
    private final ResourceElement firstElement;
    private final ResourceType resourcetype;
    private final ZonedDateTime timestamp;

    private ResourceContext(String namespace, ResourceElement firstElement, ZonedDateTime timestamp) {
        this.namespace = namespace;
        this.firstElement = firstElement;
        this.resourcetype = getResourceType(firstElement);
        this.timestamp = timestamp;
    }

    /**
     * An id is at most {@link #MAX_ID_LENGTH} characters, and has no {@code /} and no control character.
     */
    private static void checkId(String id) {
        if (id.codePointCount(0, id.length()) > MAX_ID_LENGTH) {
            throw new ResourceException("An id is at most " + MAX_ID_LENGTH + " characters");
        }
        if (id.indexOf('/') >= 0) {
            throw new ResourceException("An id cannot hold a '/': \"" + id + "\"");
        }
        if (id.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResourceException("An id cannot hold a control character");
        }
    }

    /**
     * A segment of the path, decoded once: each {@code %XX} is a byte of UTF-8, and every other character stands
     * for itself, a {@code +} too.
     */
    static String urlDecode(String segment) {
        if (segment.indexOf('%') < 0 && segment.chars().allMatch(c -> c < 0x80)) {
            return segment;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '%') {
                int high = i + 2 < segment.length() ? Character.digit(segment.charAt(i + 1), 16) : -1;
                int low = high < 0 ? -1 : Character.digit(segment.charAt(i + 2), 16);
                if (low < 0) {
                    throw new ResourceException("Not a valid escape in the path: \"" + segment + "\". Write a '%' as %25");
                }
                bytes.write(high * 16 + low);
                i += 2;
            } else if (c < 0x80) {
                bytes.write(c);
            } else {
                // not sent by HTTP, where the path is ASCII; a character given here in a test stands for itself
                bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException _) {
            throw new ResourceException("Not UTF-8 in the path: \"" + segment + "\"");
        }
    }

    public String getNamespace() {
        return namespace;
    }

    public ResourceElement getFirstElement() {
        return firstElement;
    }

    public boolean isReference() {
        return ResourceType.REFERENCE == resourcetype;
    }

    public boolean isManaged() {
        return ResourceType.MANAGED == resourcetype;
    }

    public boolean isEmbedded() {
        return ResourceType.EMBEDDED == resourcetype;
    }

    public ZonedDateTime getTimestamp() {
        return timestamp;
    }

    static class VisitContext {
        final JsonNode node;
        final ResourceElement element;

        VisitContext(JsonNode node, ResourceElement element) {
            this.node = node;
            this.element = element;
        }
    }

    private <R> R navigateJson(JsonNode rootNode,
                               Function<VisitContext, R> objectTargetFunction,
                               Supplier<R> emptySupplier) {
        JsonNode node = rootNode;
        ResourceElement element = firstElement;
        element = element.next();
        // array-navigation is not supported
        while (element.hasNext()) {
            node = node.get(element.name());
            if (node == null) {
                return emptySupplier.get();
            }
            element = element.next();
        }
        return objectTargetFunction.apply(new VisitContext(node, element));
    }

    public boolean referenceToExists(JsonNode rootNode) {
        return navigateJson(rootNode, vc -> {
            if (vc.node == null) {
                return false;
            }
            JsonNode node = vc.node.get(vc.element.name());
            if (node != null && node.isArray()) {
                // scan array
                ArrayNode arrayNode = (ArrayNode) node;
                for (int i = 0; i < arrayNode.size(); i++) {
                    String idref = JsonTools.text(arrayNode.get(i));
                    if (vc.element.id().equals(idref)) {
                        return true;
                    }
                }
                return false;
            }
            return vc.element.id().equals(ofNullable(vc.node.get(vc.element.name())).map(JsonTools::text).orElse(null));
        }, () -> false);
    }

    /**
     * @param rootNode
     * @return null if content is empty, the JsonNode instance representing the root of the sub-tree otherwise.
     */
    public JsonNode subTree(JsonNode rootNode) {
        return navigateJson(rootNode, vc -> {
            if (vc.node == null) {
                return null;
            }
            if (!vc.node.isContainer()) {
                throw new IllegalStateException("The parent of \"" + vc.element.name() + "\" is not an object");
            }
            if (vc.node.isObject()) {
                return vc.node.get(vc.element.name());
            } else {
                // array
                throw new UnsupportedOperationException("array-navigation not supported");
            }
        }, () -> null);
    }

    public <R> R navigateAndCreateJson(JsonNode jn, Function<ResourceJsonTraversalTuple, R> f) {
        return navigateAndCreateJson(firstElement, jn, f);
    }

    private <R> R navigateAndCreateJson(ResourceElement re, JsonNode jn, Function<ResourceJsonTraversalTuple, R> f) {
        // array-navigation is not supported
        ResourceElement nextResourceElement = re.next();
        if (!nextResourceElement.hasNext()) {
            return f.apply(new ResourceJsonTraversalTuple(nextResourceElement, jn));
        }
        if (!jn.has(nextResourceElement.name())) {
            ObjectNode oj = (ObjectNode) jn;
            if (nextResourceElement.getSpecificationElement().getJsonTypes().contains("array")) {
                oj.putArray(nextResourceElement.name());
            } else {
                oj.putObject(nextResourceElement.name());
            }
        }
        JsonNode nextJsonNode = jn.get(nextResourceElement.name());
        return navigateAndCreateJson(nextResourceElement, nextJsonNode, f);
    }

}
