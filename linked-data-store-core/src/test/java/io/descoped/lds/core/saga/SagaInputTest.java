package io.descoped.lds.core.saga;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.json.JsonTools;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The input of a saga as the saga log keeps it: what it is made from is what it gives back, and a source, a source id
 * and data that are not given are left out.
 */
class SagaInputTest {

    private static final UUID TX_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ZonedDateTime VERSION = ZonedDateTime.of(2026, 11, 21, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final DocumentKey KEY = new DocumentKey("ns", "Person", "p1", VERSION);

    @Test
    void aWriteGivesBackWhatItIsMadeOf() {
        SagaInput input = new SagaInput(TX_ID, "PUT", "schema-1", KEY, "import", "row-7",
                JsonTools.toJsonNode("{\"name\":\"Ann\"}"));

        assertThat(input.txId()).isEqualTo(TX_ID.toString());
        assertThat(input.method()).isEqualTo("PUT");
        assertThat(input.schema()).isEqualTo("schema-1");
        assertThat(input.namespace()).isEqualTo("ns");
        assertThat(input.entity()).isEqualTo("Person");
        assertThat(input.resourceId()).isEqualTo("p1");
        assertThat(input.version()).isEqualTo(VERSION);
        assertThat(input.versionAsString()).isEqualTo("2026-11-21T12:00:00Z");
        assertThat(input.source()).isEqualTo("import");
        assertThat(input.sourceId()).isEqualTo("row-7");
        assertThat(input.data().get("name").stringValue()).isEqualTo("Ann");
        assertThat(input).hasToString("SagaInput{txId='00000000-0000-0000-0000-000000000001',"
                + " method='PUT', schema='schema-1', namespace='ns', entity='Person', id='p1',"
                + " version='2026-11-21T12:00:00Z', source='import', sourceId='row-7', data={\"name\":\"Ann\"}}");
    }

    @Test
    void aDeleteWithoutSourceOrDataLeavesThemOut() {
        SagaInput input = new SagaInput(TX_ID, "DELETE", "schema-1", KEY, null, null, null);

        assertThat(input.asJsonNode().has("source")).isFalse();
        assertThat(input.asJsonNode().has("sourceId")).isFalse();
        assertThat(input.asJsonNode().has("data")).isFalse();
        assertThat(input.source()).isNull();
        assertThat(input.sourceId()).isNull();
        assertThat(input.data()).isNull();
        assertThat(input.toString()).endsWith("source='null', sourceId='null', data=null}");
        assertThat(input.toPrettyString()).contains("\"method\" : \"DELETE\"");
    }

    @Test
    void anInputReadBackFromItsJsonIsTheSame() {
        SagaInput input = new SagaInput(TX_ID, "PUT", "schema-1", KEY, null, null, JsonTools.toJsonNode("{}"));

        SagaInput readBack = new SagaInput(input.asJsonNode());

        assertThat(readBack).hasToString(input.toString());
    }
}
