package io.descoped.lds.core.domain;

import io.descoped.lds.api.persistence.json.JsonTools;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class NullFieldsTest {

    @Test
    void aFieldThatIsNullIsRemovedAtAnyDepthAndANullInAnArrayIsKept() {
        JsonNode document = JsonTools.toJsonNode("""
                {"name": "n", "gone": null, "part": {"label": null, "size": 1},
                 "list": [null, {"a": null, "b": 2}, [{"c": null}]], "empty": {"only": null}}""");

        NullFields.removeFrom(document);

        assertThat(document).isEqualTo(JsonTools.toJsonNode("""
                {"name": "n", "part": {"size": 1}, "list": [null, {"b": 2}, [{}]], "empty": {}}"""));
    }

    @Test
    void aValueThatIsNotAnObjectIsLeftAsItIs() {
        assertThat(NullFields.removeFrom(JsonTools.toJsonNode("7"))).isEqualTo(JsonTools.toJsonNode("7"));
        assertThat(NullFields.removeFrom(null)).isNull();
    }
}
