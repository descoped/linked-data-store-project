package io.descoped.lds.api.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ids are in the order of their code points, the order of their bytes in UTF-8, and the key gives that order to a
 * store that compares by UTF-16.
 */
class IdOrderTest {

    private static final List<String> IN_ORDER = List.of("", "a", "a!", "aa", "ab", "z", "~", "ø", "日", "퟿",
            "", "ﬀ", "Ａ", "�", "￿", "😀", "😀a", "𠮷", "􏿿");

    @Test
    void textsAreInTheOrderOfTheirCodePoints() {
        List<String> shuffled = new ArrayList<>(IN_ORDER);
        Collections.shuffle(shuffled, new Random(7));
        shuffled.sort(IdOrder.CODE_POINTS);
        assertThat(shuffled).containsExactlyElementsOf(IN_ORDER);
    }

    @Test
    void theOrderIsTheOrderOfTheBytesInUtf8() {
        Random random = new Random(11);
        for (int n = 0; n < 20_000; n++) {
            String a = randomText(random);
            String b = randomText(random);
            int bytes = Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
            assertThat(Integer.signum(IdOrder.compare(a, b))).as("%s %s", a, b).isEqualTo(Integer.signum(bytes));
        }
    }

    @Test
    void javaPutsACharacterAboveFfffBeforeOneOfE000AndAboveAndTheseDoNot() {
        assertThat("😀".compareTo("ﬀ")).isNegative();
        assertThat(IdOrder.compare("😀", "ﬀ")).isPositive();
        assertThat(IdOrder.compare("ﬀ", "😀")).isNegative();
        assertThat(IdOrder.compare("same", "same")).isZero();
    }

    @Test
    void theKeyInJavasOrderIsTheTextInTheOrderOfCodePoints() {
        Random random = new Random(13);
        for (int n = 0; n < 20_000; n++) {
            String a = randomText(random);
            String b = randomText(random);
            assertThat(Integer.signum(IdOrder.key(a).compareTo(IdOrder.key(b)))).as("%s %s", a, b)
                    .isEqualTo(Integer.signum(IdOrder.compare(a, b)));
        }
        assertThat(IdOrder.key("abc")).isEqualTo("abc");
        assertThat(IdOrder.key("😀")).hasSize(2);
        assertThat(IdOrder.key("\uE000")).isEqualTo("\uE000\uE000");
    }

    @Test
    void theKeyHoldsNoSurrogateAndIsInTheSameOrderInUtf8() {
        Random random = new Random(17);
        for (int n = 0; n < 20_000; n++) {
            String a = randomText(random);
            String b = randomText(random);
            String keyA = IdOrder.key(a);
            String keyB = IdOrder.key(b);
            assertThat(keyA.chars().noneMatch(c -> Character.isSurrogate((char) c))).as(a).isTrue();
            assertThat(Integer.signum(Arrays.compareUnsigned(keyA.getBytes(StandardCharsets.UTF_8),
                    keyB.getBytes(StandardCharsets.UTF_8)))).as("%s %s", a, b)
                    .isEqualTo(Integer.signum(IdOrder.compare(a, b)));
        }
        // at most twice as long; in UTF-8 at most 6 bytes for each character, 2,048 for an id at its limit
        assertThat(IdOrder.key("\uFFFF".repeat(341)).getBytes(StandardCharsets.UTF_8)).hasSize(341 * 6);
        assertThat(IdOrder.key("😀".repeat(256)).getBytes(StandardCharsets.UTF_8)).hasSize(256 * 6);
    }

    @Test
    void aTextWithAnUnpairedSurrogateIsNotWhole() {
        assertThat(IdOrder.isWellFormed("a😀b")).isTrue();
        assertThat(IdOrder.isWellFormed("")).isTrue();
        assertThat(IdOrder.isWellFormed("a\uD800")).isFalse();
        assertThat(IdOrder.isWellFormed("\uDC00a")).isFalse();
        assertThat(IdOrder.isWellFormed("\uDBFF\uDFFF")).isTrue();
    }

    private static String randomText(Random random) {
        int[] starts = {0x21, 0xF8, 0x4E00, 0xD7F0, 0xE000, 0xFB00, 0xFF00, 0xFFF0, 0x1F600, 0x20000, 0x10FFF0};
        StringBuilder text = new StringBuilder();
        int length = random.nextInt(4);
        for (int i = 0; i < length; i++) {
            int codePoint = starts[random.nextInt(starts.length)] + random.nextInt(15);
            if (codePoint >= 0xD800 && codePoint <= 0xDFFF) {
                codePoint = 0xD7FF;
            }
            text.appendCodePoint(codePoint);
        }
        return text.toString();
    }
}
