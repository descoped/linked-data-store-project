package io.descoped.lds.api.json;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A name of a field as a step of a path: the syntax of a path escaped, every other name as it is.
 */
class FieldNameTest {

    @Test
    void aNameWithoutTheSyntaxOfAPathIsAsItIs() {
        for (String name : new String[]{"", "name", "my field", "$", "日本", "😀", "a/b", "1a", "{x}"}) {
            assertThat(FieldName.escape(name)).isSameAs(name);
            assertThat(FieldName.unescape(name)).isEqualTo(name);
        }
    }

    @Test
    void theSyntaxOfAPathIsEscaped() {
        assertThat(FieldName.escape("a.b")).isEqualTo("a\\.b");
        assertThat(FieldName.escape("x[3]")).isEqualTo("x\\[3\\]");
        assertThat(FieldName.escape("[]")).isEqualTo("\\[\\]");
        assertThat(FieldName.escape("a\\b")).isEqualTo("a\\\\b");
        assertThat(FieldName.escape("$.a[0]")).isEqualTo("$\\.a\\[0\\]");
    }

    @Test
    void aStepIsReadBackAsTheNameItWasWrittenFrom() {
        for (String name : new String[]{"a.b", "x[3]", "[]", "\\", "\\\\.", "a\\.b", ".", "..", "][", "é.ü"}) {
            assertThat(FieldName.unescape(FieldName.escape(name))).isEqualTo(name);
        }
        // an escape at the end is itself
        assertThat(FieldName.unescape("a\\")).isEqualTo("a\\");
    }
}
