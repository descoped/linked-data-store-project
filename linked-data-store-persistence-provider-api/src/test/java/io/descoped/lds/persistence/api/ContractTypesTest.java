package io.descoped.lds.persistence.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.descoped.lds.api.json.JsonNavigationPath;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ContractTypesTest {

    private static final List<Integer> NUMBERS = List.of(1, 2, 3, 4, 5, 6, 7, 8);

    private static List<Integer> select(Range<Integer> range) {
        return range.select(NUMBERS, Function.identity(), Comparator.naturalOrder());
    }

    @Test
    void rangeSelectsBetweenItsBoundsAndCountsFromTheRightEnd() {
        assertThat(select(Range.unbounded())).isEqualTo(NUMBERS);
        assertThat(select(Range.first(3))).containsExactly(1, 2, 3);
        assertThat(select(Range.firstAfter(2, 3))).containsExactly(4, 5);
        assertThat(select(Range.firstBetween(9, 3, 6))).containsExactly(4, 5);
        assertThat(select(Range.between(0, 3))).containsExactly(1, 2);
        assertThat(select(Range.between(8, 99))).isEmpty();
        assertThat(select(Range.last(3))).containsExactly(8, 7, 6);
        assertThat(select(Range.lastBefore(2, 6))).containsExactly(5, 4);
        assertThat(select(Range.lastBetween(9, 3, 6))).containsExactly(5, 4);
        assertThat(select(Range.first(0))).isEmpty();
        assertThat(select(new Range<>(6, 3, Range.UNLIMITED, false))).isEmpty();
        assertThat(Range.first(3).limited()).isTrue();
        assertThat(Range.unbounded().limited()).isFalse();
        assertThat(Range.lastBetween(2, 3, 6).map(String::valueOf)).isEqualTo(new Range<>("3", "6", 2, true));
        assertThat(Range.<Integer>last(2).map(String::valueOf)).isEqualTo(new Range<String>(null, null, 2, true));
        assertThatIllegalArgumentException().isThrownBy(() -> Range.first(-1));
    }

    @Test
    void idsAreOrderedByCodePoint() {
        Comparator<String> order = Identifiers.ID_ORDER;
        assertThat(order.compare("a", "b")).isNegative();
        assertThat(order.compare("a", "a")).isZero();
        assertThat(order.compare("ab", "a")).isPositive();
        assertThat(order.compare("Z", "a")).isNegative();
        assertThat(order.compare("~", "Æ")).isNegative();
        // U+FF5E is below U+1F600, although its UTF-16 code unit is above the surrogate that starts U+1F600
        assertThat(order.compare("\uFF5E", "\uD83D\uDE00")).isNegative();
        assertThat("\uFF5E".compareTo("\uD83D\uDE00")).isPositive();
    }

    @Test
    void namesAreChecked() {
        assertThat(Identifiers.requireNamespace("data")).isEqualTo("data");
        assertThat(Identifiers.requireNamespace("my-ns_1.test")).isEqualTo("my-ns_1.test");
        assertThat(Identifiers.requireEntity("Person_2")).isEqualTo("Person_2");
        assertThat(Identifiers.requireId("o'brien 日本 😀")).isEqualTo("o'brien 日本 😀");
        for (String bad : new String[]{"", "a/b", ".x", "-x", "a b", "ø", "x".repeat(129)}) {
            assertThatIllegalArgumentException().as(bad).isThrownBy(() -> Identifiers.requireNamespace(bad));
        }
        for (String bad : new String[]{"", "1a", "a-b", "a.b", "a b", "å", "x".repeat(129)}) {
            assertThatIllegalArgumentException().as(bad).isThrownBy(() -> Identifiers.requireEntity(bad));
        }
        for (String bad : new String[]{"", "a/b", "a\nb", "\u007f", "\uD83D", "\uDE00x", "x\uD83Dy", "x".repeat(513)}) {
            assertThatIllegalArgumentException().as(bad).isThrownBy(() -> Identifiers.requireId(bad));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> Identifiers.requireNamespace(null));
        assertThatIllegalArgumentException().isThrownBy(() -> Identifiers.requireEntity(null));
        assertThatIllegalArgumentException().isThrownBy(() -> Identifiers.requireId(null));
        assertThatIllegalArgumentException().isThrownBy(() -> Identifiers.requireId("line\nbreak"))
                .withMessageContaining("\\u000a").withMessageNotContaining("\n");
    }

    @Test
    void aKeyCutsItsVersionToMicroseconds() {
        DocumentKey key = new DocumentKey("data", "Person", "john", Instant.parse("2020-01-01T00:00:00.123456789Z"));
        assertThat(key.version()).isEqualTo(Instant.parse("2020-01-01T00:00:00.123456Z"));
        assertThat(key).isEqualTo(new DocumentKey("data", "Person", "john", Instant.parse("2020-01-01T00:00:00.123456001Z")));
        assertThat(key.link()).hasToString("/Person/john");
        assertThat(new DocumentKey("data", "Person", "john", Instant.parse("1600-01-01T00:00:00.999999999Z")).version())
                .isEqualTo(Instant.parse("1600-01-01T00:00:00.999999Z"));
    }

    @Test
    void aLinkIsReadFromItsText() {
        assertThat(Link.parse("/Address/oslo")).contains(new Link("Address", "oslo"));
        assertThat(Link.parse("/Address/o s l o")).contains(new Link("Address", "o s l o"));
        for (String notALink : new String[]{null, "", "/", "//", "/A/", "//x", "Address/oslo", "/Address", "/Address/a/b",
                "/Add ress/oslo", "/1/x", "plain text"}) {
            assertThat(Link.parse(notALink)).as(String.valueOf(notALink)).isEmpty();
        }
    }

    @Test
    void aDocumentGivesTheValuesAtAPath() throws Exception {
        JsonNode json = new ObjectMapper().readTree("""
                {"name":"n","list":["a","b"],"people":[{"first":"x","tags":["t1"]},{"first":"y"},"odd",null],
                 "matrix":[["m1","m2"],["m3"]],"object":{"inner":{"leaf":1}}}""");
        Document document = new Document(new DocumentKey("data", "Person", "john", Instant.EPOCH), json);
        Function<String, List<String>> values = path -> document.values(JsonNavigationPath.from(path)).stream()
                .map(JsonNode::toString).toList();

        assertThat(values.apply("$.name")).containsExactly("\"n\"");
        assertThat(values.apply("$.list[]")).containsExactly("\"a\"", "\"b\"");
        assertThat(values.apply("$.list[1]")).containsExactly("\"b\"");
        assertThat(values.apply("$.list[7]")).isEmpty();
        assertThat(values.apply("$.people[].first")).containsExactly("\"x\"", "\"y\"");
        assertThat(values.apply("$.people[].tags[]")).containsExactly("\"t1\"");
        assertThat(values.apply("$.matrix[][]")).containsExactly("\"m1\"", "\"m2\"", "\"m3\"");
        assertThat(values.apply("$.object.inner.leaf")).containsExactly("1");
        assertThat(values.apply("$.name.deeper")).isEmpty();
        assertThat(values.apply("$.name[]")).isEmpty();
        assertThat(values.apply("$.missing")).isEmpty();
        assertThat(values.apply("$")).hasSize(1);
        assertThat(Document.deleted(document.key()).values(JsonNavigationPath.from("$.name"))).isEmpty();
        assertThat(Document.deleted(document.key()).deleted()).isTrue();
        assertThat(document.deleted()).isFalse();
    }
}
