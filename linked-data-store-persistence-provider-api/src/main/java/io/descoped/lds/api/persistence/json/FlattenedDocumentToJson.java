package io.descoped.lds.api.persistence.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.descoped.lds.api.persistence.flattened.FlattenedDocument;
import io.descoped.lds.api.persistence.flattened.FlattenedDocumentLeafNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.descoped.lds.api.persistence.json.JsonTools.mapper;

/**
 * Builds the JSON document that the leaves of a {@link FlattenedDocument} are.
 * <p>
 * A leaf is one value of the document and the path to it, as {@link JsonToFlattenedDocument} writes it:
 * {@code $.name[1].first} is the field {@code first} of the second element of the array {@code name}, and
 * {@code $.grid[0][2]} the third element of the first array in the array {@code grid}. An object or an array with
 * nothing in it is a leaf of its own, and so is a null.
 * <p>
 * Each leaf is put where its path says, in whatever order the leaves come: an array may hold values, objects,
 * arrays, empty ones and nulls side by side. The elements of an array are in the order of their places; a place
 * that no leaf names leaves no gap.
 */
public class FlattenedDocumentToJson {

    /*
     * One part of a path, between two dots: a name, and the places in arrays that follow it. A place has at most
     * nine digits, so that it is a number an array can have; what is longer is part of the name.
     */
    private static final Pattern PART = Pattern.compile("(.*?)((?:\\[[0-9]{1,9}])*)", Pattern.DOTALL);

    private static final Pattern PLACE = Pattern.compile("\\[([0-9]+)]");

    final FlattenedDocument document;

    /**
     * @param document the leaves to build the JSON document of
     */
    public FlattenedDocumentToJson(FlattenedDocument document) {
        this.document = document;
    }

    /**
     * @return the JSON document; an empty object for a document without leaves
     */
    public JsonNode toJsonNode() {
        // The document is what is at place 0 of this, so that the root is put like everything below it.
        Branch holder = new Branch(true);
        for (Map.Entry<String, FlattenedDocumentLeafNode> entry : document.leafNodesByPath().entrySet()) {
            Branch parent = holder;
            Object at = 0;
            for (Object step : steps(entry.getKey())) {
                parent = parent.branchAt(at, step instanceof Integer);
                at = step;
            }
            parent.children.put(at, valueOf(entry.getValue()));
        }
        Object root = holder.children.get(0);
        return root == null ? mapper.createObjectNode() : toJson(root);
    }

    /*
     * The steps from the document to a leaf: a String is the name of a field, an Integer a place in an array.
     * "$.name[1].first" is name, 1, first; "$" is no step at all.
     */
    static List<Object> steps(String path) {
        List<Object> steps = new ArrayList<>();
        String[] parts = path.split("\\.", -1);
        for (int i = 0; i < parts.length; i++) {
            Matcher part = PART.matcher(parts[i]);
            if (!part.matches()) {
                throw new IllegalArgumentException("Not a path of a leaf: " + path);
            }
            if (i > 0) {
                // the first part is the document itself, "$"
                steps.add(part.group(1));
            }
            Matcher place = PLACE.matcher(part.group(2));
            while (place.find()) {
                steps.add(Integer.valueOf(place.group(1)));
            }
        }
        return steps;
    }

    private static JsonNode valueOf(FlattenedDocumentLeafNode leaf) {
        return switch (leaf.type()) {
            case STRING -> leaf.value() == null ? NullNode.getInstance() : TextNode.valueOf((String) leaf.value());
            case NUMERIC -> number((String) leaf.value());
            case BOOLEAN -> BooleanNode.valueOf(Boolean.parseBoolean((String) leaf.value()));
            case EMPTY_ARRAY -> mapper.createArrayNode();
            case EMPTY_OBJECT -> mapper.createObjectNode();
            case NULL, DELETED -> NullNode.getInstance();
            default -> throw new UnsupportedOperationException("Unsupported FragmentType: " + leaf.type());
        };
    }

    /*
     * A number is stored as its text. It is read as the smallest of int, long and double that holds it.
     */
    private static JsonNode number(String text) {
        try {
            return IntNode.valueOf(Integer.parseInt(text));
        } catch (NumberFormatException notAnInt) {
            try {
                return LongNode.valueOf(Long.parseLong(text));
            } catch (NumberFormatException notALong) {
                return DoubleNode.valueOf(Double.parseDouble(text));
            }
        }
    }

    private static JsonNode toJson(Object child) {
        if (child instanceof JsonNode value) {
            return value;
        }
        Branch branch = (Branch) child;
        if (branch.array) {
            ArrayNode array = mapper.createArrayNode();
            for (Object element : branch.children.values()) {
                array.add(toJson(element));
            }
            return array;
        }
        ObjectNode object = mapper.createObjectNode();
        for (Map.Entry<Object, Object> field : branch.children.entrySet()) {
            object.set((String) field.getKey(), toJson(field.getValue()));
        }
        return object;
    }

    /*
     * An object or an array that is being built. Its children are values (JsonNode) and branches: by name in the
     * order they came for an object, by place in the order of the places for an array.
     */
    private static final class Branch {

        final boolean array;
        final Map<Object, Object> children;

        Branch(boolean array) {
            this.array = array;
            this.children = array ? new TreeMap<>() : new LinkedHashMap<>();
        }

        Branch branchAt(Object at, boolean array) {
            if (children.get(at) instanceof Branch branch && branch.array == array) {
                return branch;
            }
            Branch branch = new Branch(array);
            children.put(at, branch);
            return branch;
        }
    }
}
