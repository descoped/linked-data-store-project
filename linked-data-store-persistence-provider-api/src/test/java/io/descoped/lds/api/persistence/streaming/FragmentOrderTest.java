package io.descoped.lds.api.persistence.streaming;

import io.descoped.lds.api.persistence.DocumentKey;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How fragments are ordered and compared: by namespace, entity, id, version, path, type, offset and value, with the
 * fragments that end a stream last; and what a fragment that ends a stream says.
 */
class FragmentOrderTest {

    private static final ZonedDateTime T1 = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime T2 = T1.plusDays(1);

    private static Fragment fragment(String ns, String entity, String id, ZonedDateTime version, String path,
                                     FragmentType type, long offset, String value) {
        return new Fragment(new DocumentKey(ns, entity, id, version), path, type, offset,
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static Fragment a() {
        return fragment("ns", "E", "1", T1, "$.a", FragmentType.STRING, 0, "x");
    }

    private static Fragment control(short code) {
        return new Fragment(true, code, null, null, null, 0, null);
    }

    @Test
    void fragmentsAreOrderedByEachPartOfTheirKeyInTurnAndTheStreamControlComesLast() {
        Fragment limited = control(Fragment.LIMITED_CODE);
        Fragment notLimited = control(Fragment.NOT_LIMITED_CODE);
        List<Fragment> expected = List.of(
                fragment("a", "E", "1", T1, "$.a", FragmentType.STRING, 0, "x"),
                fragment("ns", "D", "1", T1, "$.a", FragmentType.STRING, 0, "x"),
                fragment("ns", "E", "0", T1, "$.a", FragmentType.STRING, 0, "x"),
                fragment("ns", "E", "1", T1, "$.a", FragmentType.NULL, 0, "x"),
                a(),
                fragment("ns", "E", "1", T1, "$.a", FragmentType.STRING, 0, "y"),
                fragment("ns", "E", "1", T1, "$.a", FragmentType.STRING, 1, "x"),
                fragment("ns", "E", "1", T1, "$.b", FragmentType.STRING, 0, "x"),
                fragment("ns", "E", "1", T2, "$.a", FragmentType.STRING, 0, "x"),
                limited,
                notLimited);
        List<Fragment> reversed = new ArrayList<>(expected);
        Collections.reverse(reversed);

        reversed.sort(Comparator.naturalOrder());

        assertThat(reversed).containsExactlyElementsOf(expected);
        assertThat(limited).isEqualByComparingTo(limited);
        assertThat(a()).isEqualByComparingTo(a());
    }

    @Test
    void equalFragmentsHaveTheSameKeyPathOffsetAndValue() {
        Fragment a = a();

        assertThat(a)
                .isEqualTo(a)
                .isEqualTo(a())
                .hasSameHashCodeAs(a())
                .isNotEqualTo(null)
                .isNotEqualTo("a fragment")
                .isNotEqualTo(fragment("ns", "E", "1", T1, "$.a", FragmentType.STRING, 0, "y"))
                .isNotEqualTo(fragment("ns", "E", "1", T1, "$.a", FragmentType.STRING, 1, "x"))
                .isNotEqualTo(fragment("ns", "E", "1", T1, "$.b", FragmentType.STRING, 0, "x"));
    }

    @Test
    void theTypeOfAFragmentIsNotPartOfItsEquality() {
        Fragment string = a();
        Fragment numeric = fragment("ns", "E", "1", T1, "$.a", FragmentType.NUMERIC, 0, "x");

        assertThat(string).isEqualTo(numeric).isNotEqualByComparingTo(numeric);
    }

    @Test
    void samePathIsTheSameDocumentVersionAndPathWhateverTheValue() {
        Fragment a = a();

        assertThat(a.samePathAs(a)).isTrue();
        assertThat(a.samePathAs(null)).isFalse();
        assertThat(a.samePathAs(fragment("ns", "E", "1", T1, "$.a", FragmentType.NUMERIC, 3, "other"))).isTrue();
        assertThat(a.samePathAs(fragment("ns", "E", "1", T2, "$.a", FragmentType.STRING, 0, "x"))).isFalse();
        assertThat(a.samePathAs(fragment("ns", "E", "1", T1, "$.b", FragmentType.STRING, 0, "x"))).isFalse();
    }

    @Test
    void aFragmentThatEndsAStreamSaysWhetherTheStreamWasLimited() {
        Fragment limited = control(Fragment.LIMITED_CODE);
        Fragment notLimited = control(Fragment.NOT_LIMITED_CODE);

        assertThat(limited.isStreamingControl()).isTrue();
        assertThat(limited.isLimited()).isTrue();
        assertThat(limited.isNotLimited()).isFalse();
        assertThat(notLimited.isLimited()).isFalse();
        assertThat(notLimited.isNotLimited()).isTrue();
    }

    @Test
    void anOrdinaryFragmentCannotBeAskedWhetherAStreamWasLimited() {
        Fragment a = a();

        assertThat(a.isStreamingControl()).isFalse();
        assertThatThrownBy(a::isLimited).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(a::isNotLimited).isInstanceOf(IllegalStateException.class);
    }
}
