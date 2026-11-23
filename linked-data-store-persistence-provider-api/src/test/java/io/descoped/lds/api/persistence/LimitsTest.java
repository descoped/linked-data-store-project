package io.descoped.lds.api.persistence;

import io.descoped.lds.api.persistence.json.JsonTools;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limits are counted in bytes of UTF-8, on the path as the providers store it.
 */
class LimitsTest {

    @Test
    void theLengthIsTheNumberOfBytesInUtf8() {
        for (String text : new String[]{"", "a", "ø", "日", "😀", "a😀ø日", "~\u007f\u0080\u07ff\u0800\uffff"}) {
            assertThat(Limits.utf8Length(text)).as(text).isEqualTo(text.getBytes(StandardCharsets.UTF_8).length);
        }
        assertThat(Limits.utf8Length("😀".repeat(256))).isEqualTo(1024);
        // a half of a pair stands alone; it is counted as the three bytes it would take
        assertThat(Limits.utf8Length("\uD800")).isEqualTo(3);
        assertThat(Limits.utf8Length("a\uDC00b")).isEqualTo(5);
    }

    @Test
    void aPathOfTheLongestLengthIsTaken() {
        // $ and .name: 1 + 1 + 1022 bytes
        assertThat(Limits.overLimit(object("a".repeat(1022), "v"))).isNull();
        assertThat(Limits.overLimit(object("日".repeat(340) + "ab", "v"))).isNull();
    }

    @Test
    void aPathThatIsOneByteLongerIsNamed() {
        assertThat(Limits.overLimit(object("a".repeat(1023), "v")))
                .startsWith("The path of a value is at most 1024 bytes in UTF-8: $.aaaa").endsWith("...");
        assertThat(Limits.overLimit(object("日".repeat(341), "v"))).isNotNull();
        assertThat(Limits.overLimit(object("ok", object("b".repeat(1020), "v")))).isNotNull();
    }

    @Test
    void anArrayAddsTwoBytesAndAnEmptyOneNone() {
        // $.x[] is 5 bytes, $.x 3
        ObjectNode inArray = JsonTools.mapper.createObjectNode();
        inArray.putArray("a".repeat(1020)).add("v");
        assertThat(Limits.overLimit(inArray)).isNull();
        ObjectNode longer = JsonTools.mapper.createObjectNode();
        longer.putArray("a".repeat(1021)).add("v");
        assertThat(Limits.overLimit(longer)).isNotNull();
        ObjectNode empty = JsonTools.mapper.createObjectNode();
        empty.putArray("a".repeat(1022));
        assertThat(Limits.overLimit(empty)).isNull();
    }

    @Test
    void aValueInThirtyTwoArraysIsTakenAndInThirtyThreeNot() {
        assertThat(Limits.overLimit(nested(32))).isNull();
        assertThat(Limits.overLimit(nested(33))).startsWith("A value is in at most 32 arrays: $.a[][][]");
        // arrays of objects count as well
        JsonNode node = JsonTools.mapper.createObjectNode().put("v", 1);
        for (int i = 0; i < 33; i++) {
            ObjectNode parent = JsonTools.mapper.createObjectNode();
            parent.putArray("x").add(node);
            node = parent;
        }
        assertThat(Limits.overLimit(node)).startsWith("A value is in at most 32 arrays");
    }

    @Test
    void theFirstPathThatGoesPastIsNamedAndTheOthersAreNotLookedAt() {
        ObjectNode document = JsonTools.mapper.createObjectNode();
        document.put("short", 1);
        document.put("b".repeat(1100), 2);
        document.put("c".repeat(1100), 3);
        assertThat(Limits.overLimit(document)).contains("$.bbbb").doesNotContain("cccc");
        assertThat(Limits.overLimit(JsonTools.mapper.createObjectNode())).isNull();
        assertThat(Limits.overLimit(JsonTools.toJsonNode("\"a text\""))).isNull();
    }

    private static ObjectNode object(String field, Object value) {
        ObjectNode node = JsonTools.mapper.createObjectNode();
        if (value instanceof JsonNode json) {
            node.set(field, json);
        } else {
            node.put(field, value.toString());
        }
        return node;
    }

    private static JsonNode nested(int arrays) {
        ArrayNode inner = JsonTools.mapper.createArrayNode().add("v");
        for (int i = 1; i < arrays; i++) {
            inner = JsonTools.mapper.createArrayNode().add(inner);
        }
        ObjectNode document = JsonTools.mapper.createObjectNode();
        document.set("a", inner);
        return document;
    }
}
