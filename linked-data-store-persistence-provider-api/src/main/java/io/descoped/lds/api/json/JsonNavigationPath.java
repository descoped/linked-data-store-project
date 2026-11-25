package io.descoped.lds.api.json;

import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.api.specification.SpecificationElement;
import io.descoped.lds.api.specification.SpecificationElementType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonNavigationPath {

    private static final Pattern arrayElementPattern = Pattern.compile("\\[\\d*]");

    public static JsonNavigationPath from(String serializedJsonNavigationPath) {
        return new JsonNavigationPath(deserialize(serializedJsonNavigationPath));
    }

    public static JsonNavigationPath from(String... jsonPath) {
        return new JsonNavigationPath(jsonPath);
    }

    public static JsonNavigationPath from(Collection<String> jsonPath) {
        return new JsonNavigationPath(jsonPath.toArray(new String[jsonPath.size()]));
    }

    public static JsonNavigationPath from(SpecificationElement specificationElement) {
        Deque<String> parts = new LinkedList<>();
        SpecificationElement e = specificationElement;
        while (!SpecificationElementType.ROOT.equals(e.getSpecificationElementType())
                && !SpecificationElementType.MANAGED.equals(e.getSpecificationElementType())) {
            if (e.getParent().getJsonTypes().contains("array")) {
                parts.addFirst("[]");
            } else {
                parts.addFirst(e.getName());
            }
            e = e.getParent();
        }
        parts.addFirst("$");
        return from(parts);
    }

    private final String[] path;

    JsonNavigationPath(String[] path) {
        this.path = path;
        validate();
    }

    private void validate() {
        if (path.length == 0) {
            throw new IllegalArgumentException("json-path must contain at least one element");
        }
        if (!path[0].startsWith("$")) {
            throw new IllegalArgumentException("json-path must start with '$' in its first element");
        }
        for (int i = 0; i < path.length; i++) {
            String element = path[i];
            if (arrayElementPattern.matcher(element).matches()) {
                continue;
            }
            if (element.contains("[") || element.contains("]")) {
                throw new IllegalArgumentException(String.format("json-path element at index %d contain square bracket symbol without being formatted as a proper array element", i));
            }
        }
    }

    public String[] getPath() {
        return path;
    }

    /*
     * The parts of a path as serialize writes it. It is cut at each dot that does not follow an escape (FieldName),
     * and a name is read without its escapes.
     */
    private static String[] deserialize(String relationName) {
        List<String> parts = new LinkedList<>();
        for (String dotPart : splitAtDots(relationName)) {
            int openingBracket = indexOfUnescaped(dotPart, '[');
            if (openingBracket >= 0 && dotPart.endsWith("]")) {
                addArrayParts(dotPart, openingBracket, parts);
            } else {
                if (openingBracket >= 0 || indexOfUnescaped(dotPart, ']') >= 0) {
                    throw new IllegalArgumentException("Malformed json-path, path-element contain square-bracket symbol in a bad way");
                }
                parts.add(FieldName.unescape(dotPart));
            }
        }
        return parts.toArray(new String[parts.size()]);
    }

    /*
     * The text between the dots that do not follow an escape, as String.split gives it: without the empty parts at
     * the end.
     */
    private static List<String> splitAtDots(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == FieldName.ESCAPE) {
                i++;
            } else if (c == '.') {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        while (!parts.isEmpty() && parts.getLast().isEmpty()) {
            parts.removeLast();
        }
        return parts;
    }

    /*
     * Where the character is in the text, not after an escape, or -1.
     */
    private static int indexOfUnescaped(String text, char wanted) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == FieldName.ESCAPE) {
                i++;
            } else if (c == wanted) {
                return i;
            }
        }
        return -1;
    }

    /*
     * The parts of a path-element that ends with an array index: the array-field, then one part for each index.
     */
    private static void addArrayParts(String dotPart, int openingBracket, List<String> parts) {
        if (openingBracket == 0) {
            throw new IllegalArgumentException("Malformed json-path, array-index path-element must be prepended by field");
        }
        parts.add(FieldName.unescape(dotPart.substring(0, openingBracket))); // array-field
        Matcher m = arrayElementPattern.matcher(dotPart.substring(openingBracket));
        while (m.find()) {
            parts.add(m.group()); // array-items field
        }
    }

    /**
     * The path as text: {@code $}, then {@code .} and each name, written with an escape before each character that is
     * the syntax of a path ({@link FieldName}), and {@code []} for the items of an array: {@code $.a[].b}. It is the
     * path of a value as the providers store it, without the places in arrays.
     *
     * @return the path as text
     */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.length; i++) {
            if (arrayElementPattern.matcher(path[i]).matches()) {
                sb.append(path[i]);
            } else {
                if (i > 0) {
                    sb.append(".");
                }
                sb.append(i == 0 ? path[i] : FieldName.escape(path[i]));
            }
        }
        return sb.toString();
    }

    public SpecificationElement toSpecificationElement(Specification specification, String entity) {
        SpecificationElement se = specification.getRootElement().getProperties().get(entity);
        if (path == null) {
            return se;
        }
        for (int i = 1; i < path.length; i++) {
            String pathElement = path[i];
            if (se.getJsonTypes().contains("array")) {
                se = se.getItems();
                continue; // skip array index navigation
            }
            SpecificationElement next = se.getProperties().get(pathElement);
            if (next == null) {
                return se;
            }
            se = next;
        }
        return se;
    }

    public JsonNavigationPath popBack() {
        return JsonNavigationPath.from(Arrays.copyOf(path, path.length - 1));
    }

    public String back() {
        return path[path.length - 1];
    }
}
