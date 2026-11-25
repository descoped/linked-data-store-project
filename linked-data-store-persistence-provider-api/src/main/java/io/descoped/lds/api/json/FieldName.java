package io.descoped.lds.api.json;

/**
 * The name of a field as a step of a path ({@code $.address.street}, {@code $.name[1].first}): the one form every
 * provider stores and reads a path in.
 * <p>
 * In a path, {@code .} goes before the name of a field and {@code [n]} is a place in an array. A name that holds one
 * of these characters is written with {@code \} before each {@code .}, {@code [} and {@code ]} in it, and before each
 * {@code \}, so that it is read back as the name it is: the field {@code a.b} is the step {@code a\.b}, and
 * {@code x[3]} is {@code x\[3\]}. Any other name is written as it is, so the path of a document whose names hold none
 * of them is the same text as it always was. An escape is a character of the path like any other: it is counted in
 * the length of the path ({@link io.descoped.lds.api.persistence.Limits#PATH_BYTES}).
 */
public final class FieldName {

    /**
     * The character written before a character of a name that is part of the syntax of a path.
     */
    public static final char ESCAPE = '\\';

    private FieldName() {
    }

    /**
     * The name as a step of a path.
     *
     * @param name the name of a field
     * @return the name, with {@code \} before each {@code .}, {@code [}, {@code ]} and {@code \} in it; the name
     * itself when it holds none of them
     */
    public static String escape(String name) {
        int first = firstToEscape(name);
        if (first < 0) {
            return name;
        }
        StringBuilder escaped = new StringBuilder(name.length() + 8).append(name, 0, first);
        for (int i = first; i < name.length(); i++) {
            char c = name.charAt(i);
            if (isSyntax(c)) {
                escaped.append(ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /**
     * The name a step of a path is: each character that follows {@code \} is taken as it is, and a {@code \} at the
     * end is itself.
     *
     * @param step a step of a path, as {@link #escape} writes it
     * @return the name of the field
     */
    public static String unescape(String step) {
        int first = step.indexOf(ESCAPE);
        if (first < 0) {
            return step;
        }
        StringBuilder name = new StringBuilder(step.length()).append(step, 0, first);
        for (int i = first; i < step.length(); i++) {
            char c = step.charAt(i);
            if (c == ESCAPE && i + 1 < step.length()) {
                c = step.charAt(++i);
            }
            name.append(c);
        }
        return name.toString();
    }

    /**
     * Whether a character is part of the syntax of a path, and is escaped in a name.
     *
     * @param c the character
     * @return true for {@code .}, {@code [}, {@code ]} and {@code \}
     */
    public static boolean isSyntax(char c) {
        return c == '.' || c == '[' || c == ']' || c == ESCAPE;
    }

    private static int firstToEscape(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (isSyntax(name.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
