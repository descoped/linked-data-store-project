package io.descoped.lds.api.persistence.flattened;

import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * The order of the paths of the leaves of a document: {@code $.name[2].first} before {@code $.name[10].first}.
 * <p>
 * A path is taken apart at its dots and brackets. A path of fewer parts comes first. Paths of as many parts are
 * compared part by part: a part of digits comes before a part that is not, two parts of digits are compared as
 * the numbers they are, and two other parts as text. Two paths are the same only when they are the same text,
 * so no two leaves of a document take the place of each other.
 */
public class PathComparator implements Comparator<String> {

    private static final Pattern SEPARATOR_PATTERN = Pattern.compile("(\\.|]\\[|\\[|]\\.?)");

    boolean isDigit(CharSequence seq) {
        for (int j = 0; j < seq.length(); j++) {
            char charAt = seq.charAt(j);
            if (!('0' <= charAt && charAt <= '9')) {
                return false;
            }
        }
        return !seq.isEmpty();
    }

    @Override
    public int compare(String path1, String path2) {
        // Kinda optimized. Could be simpler but this code is called often.
        String[] parts1 = SEPARATOR_PATTERN.split(path1);
        String[] parts2 = SEPARATOR_PATTERN.split(path2);
        if (parts1.length != parts2.length) {
            return parts1.length < parts2.length ? -1 : +1;
        }
        for (int i = 0; i < parts1.length; i++) {
            boolean digits1 = isDigit(parts1[i]);
            boolean digits2 = isDigit(parts2[i]);
            int compare;
            if (digits1 != digits2) {
                // digits first: comparing "10" with "1a" as text would put 9 before 10, 10 before 1a and 1a before 9
                compare = digits1 ? -1 : +1;
            } else if (digits1) {
                compare = compareNumbers(parts1[i], parts2[i]);
            } else {
                compare = parts1[i].compareTo(parts2[i]);
            }
            if (compare != 0) {
                return compare;
            }
        }
        // the same parts, cut differently: "$.a[0]" and "$.a.0"
        return path1.compareTo(path2);
    }

    /*
     * Two texts of digits, compared as numbers without making numbers of them: the name of a field may be more
     * digits than any number holds. "01" and "1" are the same number and not the same name; the shorter is first.
     */
    private static int compareNumbers(String digits1, String digits2) {
        int start1 = leadingZeros(digits1);
        int start2 = leadingZeros(digits2);
        int length1 = digits1.length() - start1;
        int length2 = digits2.length() - start2;
        if (length1 != length2) {
            return length1 < length2 ? -1 : +1;
        }
        for (int i = 0; i < length1; i++) {
            int compare = Character.compare(digits1.charAt(start1 + i), digits2.charAt(start2 + i));
            if (compare != 0) {
                return compare;
            }
        }
        return Integer.compare(start1, start2);
    }

    private static int leadingZeros(String digits) {
        int zeros = 0;
        while (zeros < digits.length() - 1 && digits.charAt(zeros) == '0') {
            zeros++;
        }
        return zeros;
    }
}
