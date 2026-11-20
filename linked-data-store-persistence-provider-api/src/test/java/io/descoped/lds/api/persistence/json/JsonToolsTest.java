package io.descoped.lds.api.persistence.json;

import org.json.JSONException;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static io.descoped.lds.api.persistence.json.JsonTools.mapper;
import static io.descoped.lds.api.persistence.json.JsonTools.toJson;
import static io.descoped.lds.api.persistence.json.JsonTools.toJsonNode;
import static io.descoped.lds.api.persistence.json.JsonTools.toList;
import static io.descoped.lds.api.persistence.json.JsonTools.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.skyscreamer.jsonassert.JSONAssert.assertEquals;

public class JsonToolsTest {

    @Test
    public void JsonConversionRoundTrip() throws JSONException {
        ObjectNode root = mapper.createObjectNode();
        populateWithPrimitives(root);
        ObjectNode objectFoo = root.putObject("objectFoo");
        populateWithPrimitives(objectFoo);
        ArrayNode arrayFoo = root.putArray("arrayFoo");
        arrayFoo.add("bar1");
        arrayFoo.add("bar2");
        ArrayNode arrayParent = root.putArray("arrayParent");
        ArrayNode arrayChild1 = arrayParent.addArray();
        populateWithPrimitives(arrayChild1.addObject());
        populateWithPrimitives(arrayChild1.addObject());
        ArrayNode arrayChild2 = arrayParent.addArray();
        populateWithPrimitives(arrayChild2.addObject());
        populateWithPrimitives(arrayChild2.addObject());

        assertEquals(toJson(toJsonNode(toMap(root))), toJson(root), true);
        assertEquals(toJson(toJsonNode(toList(arrayParent))), toJson(arrayParent), true);
    }

    private void populateWithPrimitives(ObjectNode node) {
        node.put("fooStr", "bar");
        node.put("fooInt", 1);
        node.put("fooLong", 2L);
        node.put("fooFloat", 2.3f);
        node.put("fooDouble", 2.3);
        node.put("fooBoolean", true);
    }

    @Test
    public void thatTextIsTheTextOfATextAndNullForAnythingElse() {
        JsonNode document = toJsonNode("{\"text\":\"x\",\"empty\":\"\",\"number\":7,\"fraction\":1.5,\"yes\":true,\"nothing\":null,\"object\":{},\"array\":[\"x\"]}");

        assertThat(JsonTools.text(document.get("text"))).isEqualTo("x");
        assertThat(JsonTools.text(document.get("empty"))).isEmpty();
        for (String other : List.of("number", "fraction", "yes", "nothing", "object", "array")) {
            assertThat(JsonTools.text(document.get(other))).as(other).isNull();
        }
    }
}
