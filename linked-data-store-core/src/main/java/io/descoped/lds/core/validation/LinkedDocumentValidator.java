package io.descoped.lds.core.validation;

import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.api.specification.SpecificationElement;
import io.descoped.lds.api.specification.SpecificationElementType;
import io.descoped.lds.api.specification.SpecificationTraversal;
import io.descoped.lds.core.schema.SchemaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Says whether a document may be written: it is valid by the JSON Schema of its entity, and each of its links is
 * a link ({@code /Entity/id}) to an entity that the model lets the field link to.
 */
public class LinkedDocumentValidator {

    private static final Logger LOG = LoggerFactory.getLogger(LinkedDocumentValidator.class);

    private final Specification specification;
    private final SchemaRepository schemaRepository;
    private final Pattern linkPattern = Pattern.compile("/([^/]+)/([^/]+)");

    public LinkedDocumentValidator(Specification specification, SchemaRepository schemaRepository) {
        this.specification = specification;
        this.schemaRepository = schemaRepository;
    }

    /**
     * @param managedDomain  the name of the entity
     * @param linkedDocument the document
     * @throws LinkedDocumentValidationException when the document is not valid; the message says what is wrong
     */
    public void validate(String managedDomain, JsonNode linkedDocument) throws LinkedDocumentValidationException {
        List<String> errors = schemaRepository.getJsonSchema().validate(managedDomain, linkedDocument);
        if (!errors.isEmpty()) {
            errors.forEach(m -> LOG.debug("{}", m));
            throw new LinkedDocumentValidationException(errors.toString());
        }

        SpecificationElement managedDomainElement = specification.getRootElement().getProperties().get(managedDomain);
        SpecificationTraversal.depthFirstPreOrderFullTraversal(managedDomainElement, (ancestors, te) -> {
            if (SpecificationElementType.REF == te.getSpecificationElementType()) {
                validateLinks(ancestors, te, linkedDocument);
            }
        });
    }

    /*
     * The links of one link field of the document, if the document has the field.
     */
    private void validateLinks(Deque<SpecificationElement> ancestors, SpecificationElement te, JsonNode linkedDocument) throws LinkedDocumentValidationException {
        JsonNode context = parentOfLink(ancestors, linkedDocument);
        if (context == null) {
            // the document does not have the object the link would be in
            return;
        }
        JsonNode value = context.get(te.getName());
        if (value == null || value.isNull()) {
            return;
        }
        if (te.getJsonTypes().contains("array")) {
            for (JsonNode link : value) {
                if (!link.isNull()) {
                    validateLink(ancestors, te, text(link));
                }
            }
        } else {
            validateLink(ancestors, te, text(value));
        }
    }

    /*
     * The object of the document that the link field is in, or null when the document does not have it.
     */
    private static JsonNode parentOfLink(Deque<SpecificationElement> ancestors, JsonNode linkedDocument) {
        JsonNode context = linkedDocument;
        for (SpecificationElement ancestor : ancestors) {
            SpecificationElementType type = ancestor.getSpecificationElementType();
            if (SpecificationElementType.ROOT != type && SpecificationElementType.MANAGED != type) {
                context = context.get(ancestor.getName());
                if (context == null || !context.isObject()) {
                    return null;
                }
            }
        }
        return context;
    }

    /*
     * A link is a text. What is no text is given as it is written, and is then no link.
     */
    private static String text(JsonNode value) {
        return value.isString() ? value.stringValue() : value.toString();
    }

    private void validateLink(Deque<SpecificationElement> ancestors, SpecificationElement te, String link) throws LinkedDocumentValidationException {
        Matcher m = linkPattern.matcher(link);
        if (!m.matches()) {
            throw new LinkedDocumentValidationException(String.format("Not a valid link. Navigation: %s: \"%s\"", printNavigationPath(ancestors, te), link));
        }
        String linkedDomain = m.group(1);
        if (!te.getRefTypes().contains(linkedDomain)) {
            throw new LinkedDocumentValidationException(String.format("Illegal linked-domain: \"%s\". Navigation: %s: \"%s\"", linkedDomain, printNavigationPath(ancestors, te), link));
        }
    }

    private String printNavigationPath(Deque<SpecificationElement> ancestors, SpecificationElement te) {
        Deque<SpecificationElement> debugListing = new LinkedList<>(ancestors);
        debugListing.removeFirst();
        debugListing.addLast(te);
        return debugListing.stream().map(e -> e.getName()).collect(Collectors.joining("."));
    }
}
