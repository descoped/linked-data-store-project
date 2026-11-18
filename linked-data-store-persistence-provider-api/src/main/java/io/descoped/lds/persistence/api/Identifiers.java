package io.descoped.lds.persistence.api;

import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * The names a caller may use, and the order of document ids. Providers call these checks on every name they are
 * given, so that a name can never change the meaning of a query, a key or a path.
 */
public final class Identifiers {

    /**
     * The longest namespace or entity name, in characters.
     */
    public static final int MAX_NAME_LENGTH = 128;

    /**
     * The longest document id, in characters.
     */
    public static final int MAX_ID_LENGTH = 512;

    /**
     * The order of document ids: by Unicode code point, which is also the order of their UTF-8 bytes.
     */
    public static final Comparator<String> ID_ORDER = Identifiers::compareCodePoints;

    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*");
    private static final Pattern ENTITY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private Identifiers() {
    }

    /**
     * Checks a namespace: ASCII letters, digits, {@code _}, {@code .} and {@code -}, not starting with {@code .}
     * or {@code -}, at most {@value #MAX_NAME_LENGTH} characters.
     *
     * @param namespace the namespace
     * @return the namespace
     * @throws IllegalArgumentException if it is not a valid namespace
     */
    public static String requireNamespace(String namespace) {
        if (namespace == null || namespace.length() > MAX_NAME_LENGTH || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Not a valid namespace: " + printable(namespace));
        }
        return namespace;
    }

    /**
     * Checks an entity name: ASCII letters, digits and {@code _}, not starting with a digit, at most
     * {@value #MAX_NAME_LENGTH} characters.
     *
     * @param entity the entity name
     * @return the entity name
     * @throws IllegalArgumentException if it is not a valid entity name
     */
    public static String requireEntity(String entity) {
        if (entity == null || entity.length() > MAX_NAME_LENGTH || !ENTITY.matcher(entity).matches()) {
            throw new IllegalArgumentException("Not a valid entity name: " + printable(entity));
        }
        return entity;
    }

    /**
     * Checks a document id: 1 to {@value #MAX_ID_LENGTH} characters of any script, without {@code /} (it separates
     * the parts of a link), control characters or unpaired surrogates.
     *
     * @param id the document id
     * @return the id
     * @throws IllegalArgumentException if it is not a valid id
     */
    public static String requireId(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("Not a valid document id: " + printable(id));
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean unpaired = Character.isHighSurrogate(c)
                    ? i + 1 == id.length() || !Character.isLowSurrogate(id.charAt(i + 1))
                    : Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(id.charAt(i - 1)));
            if (c == '/' || Character.isISOControl(c) || unpaired) {
                throw new IllegalArgumentException("Not a valid document id: " + printable(id));
            }
        }
        return id;
    }

    private static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i);
            int y = b.codePointAt(j);
            if (x != y) {
                return Integer.compare(x, y);
            }
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static String printable(String name) {
        if (name == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder("\"");
        name.chars().limit(64).forEach(c -> {
            if (c < 0x20 || c == 0x7f || Character.isSurrogate((char) c)) {
                text.append(String.format("\\u%04x", c));
            } else {
                text.append((char) c);
            }
        });
        return text.append(name.length() > 64 ? "…\"" : "\"").toString();
    }
}
