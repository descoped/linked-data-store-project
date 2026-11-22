package io.descoped.lds.api.persistence;

import java.util.Comparator;

/**
 * The order of ids, and of the other texts of a key: the order of their Unicode code points, which is the order of
 * their bytes in UTF-8. Every provider gives a list in it and bounds a range by it: the memory store and PostgreSQL
 * ({@code COLLATE "POSIX"}) compare the bytes, the bridge and core compare with {@link #CODE_POINTS}, and Neo4j,
 * which compares texts by UTF-16, compares {@link #key(String)}. An id is whole text ({@link #isWellFormed(String)}).
 * <p>
 * Java's own order of strings is by UTF-16 code units, and differs from it in one place: a character above U+FFFF
 * (two code units from U+D800, such as 😀) comes before one of U+E000 to U+FFFF (such as {@code ﬀ} or a full-width
 * {@code Ａ}) in Java, and after it here.
 */
public final class IdOrder {

    /**
     * Compares texts by their code points.
     */
    public static final Comparator<String> CODE_POINTS = IdOrder::compare;

    private IdOrder() {
    }

    /**
     * Compares two texts by their code points: negative when the first comes first, zero when they are equal.
     *
     * @param a a text
     * @param b another text
     * @return the order of the two
     */
    public static int compare(String a, String b) {
        int length = Math.min(a.length(), b.length());
        for (int i = 0; i < length; i++) {
            char x = a.charAt(i);
            char y = b.charAt(i);
            if (x != y) {
                return inCodePointOrder(x) - inCodePointOrder(y);
            }
        }
        return a.length() - b.length();
    }

    /**
     * A text whose order by UTF-16 code units, by code points and by UTF-8 bytes is the order of the code points of the
     * given text, for a store that compares texts by UTF-16. A character below U+D800 stands for itself; each character
     * from U+E000 is two characters of U+E000 to U+EFFF, the high and the low part of its distance from U+E000, so
     * that it comes after every character below U+D800 and in its own order among the others. The key holds no
     * surrogate, so every store keeps it as it is. A text that holds half of a character above U+FFFF (an unpaired
     * surrogate, which is not text) has it as U+FFFD. The key is at most twice as long as the text.
     *
     * @param text the text
     * @return the key
     */
    public static String key(String text) {
        StringBuilder key = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint < 0xD800) {
                key.append((char) codePoint);
            } else {
                int distance = (codePoint < 0xE000 ? 0xFFFD : codePoint) - 0xE000;
                key.append((char) (0xE000 + (distance >> 12))).append((char) (0xE000 + (distance & 0xFFF)));
            }
        }
        return key.toString();
    }

    /**
     * Whether a text is whole: no half of a character above U+FFFF without its other half.
     *
     * @param text the text
     * @return false when it holds an unpaired surrogate
     */
    public static boolean isWellFormed(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    /*
     * The place of a UTF-16 code unit in the order of code points: below U+D800 as it is; U+E000 to U+FFFF to U+D800
     * to U+F7FF; U+D800 to U+DFFF, the two code units of a character above U+FFFF, to U+F800 to U+FFFF.
     */
    private static int inCodePointOrder(char c) {
        if (c < 0xD800) {
            return c;
        }
        return c >= 0xE000 ? c - 0x800 : c + 0x2000;
    }
}
