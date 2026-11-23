package io.descoped.lds.graphql;

import io.descoped.lds.core.utils.RequestTime;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static io.descoped.lds.graphql.GraphQLUndertowContext.SNAPSHOT_QUERY_NAME;
import static io.descoped.lds.graphql.GraphQLUndertowContext.SNAPSHOT_VARIABLE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The snapshot of a GraphQL request: from its query parameter, its variable or the clock, always in UTC, and refused
 * when it is not a time.
 */
class GraphQLUndertowContextTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2000-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), NOW.getZone());

    private static ZonedDateTime utc(String text) {
        return ZonedDateTime.parse(text).withZoneSameInstant(RequestTime.UTC);
    }

    private static Map<String, Deque<String>> query(String... values) {
        return Map.of(SNAPSHOT_QUERY_NAME, new LinkedList<>(List.of(values)));
    }

    private static ZonedDateTime snapshot(Map<String, Deque<String>> query, Map<String, Object> variables) {
        return GraphQLUndertowContext.getSnapshot(query, variables, CLOCK);
    }

    @Test
    void theVariableGivesTheSnapshotInUtc() {
        assertThat(snapshot(Map.of(), Map.of(SNAPSHOT_VARIABLE_NAME, "1999-01-01T02:00:00+02:00")))
                .isEqualTo(utc("1999-01-01T00:00:00Z"));
    }

    @Test
    void theQueryParameterGivesTheSnapshotInUtc() {
        assertThat(snapshot(query("2000-01-02T00:00:00+01:00"), Map.of())).isEqualTo(utc("2000-01-01T23:00:00Z"));
    }

    @Test
    void theQueryParameterGoesBeforeTheVariable() {
        assertThat(snapshot(query("1999-01-01T00:00:00Z"), Map.of(SNAPSHOT_VARIABLE_NAME, "2001-01-01T00:00:00Z")))
                .isEqualTo(utc("1999-01-01T00:00:00Z"));
    }

    @Test
    void withoutASnapshotItIsNowInUtc() {
        assertThat(snapshot(Collections.emptyMap(), Collections.emptyMap())).isEqualTo(utc("2000-01-01T00:00:00Z"));
        LinkedHashMap<String, Object> nullVariable = new LinkedHashMap<>();
        nullVariable.put(SNAPSHOT_VARIABLE_NAME, null);
        assertThat(snapshot(Map.of(), nullVariable)).isEqualTo(utc("2000-01-01T00:00:00Z"));
        LinkedList<String> nullParameter = new LinkedList<>();
        nullParameter.add(null);
        assertThat(snapshot(Map.of(SNAPSHOT_QUERY_NAME, nullParameter), Map.of())).isEqualTo(utc("2000-01-01T00:00:00Z"));
    }

    @Test
    void aSnapshotThatIsNotATimeIsRefused() {
        assertThatThrownBy(() -> snapshot(query("not-a-date-time"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The query parameter snapshot 'not-a-date-time' is not an ISO-8601 time. Example of a valid "
                        + "formatted time is '2018-12-06T11:05:31.000+01:00'");
        // a + that is not encoded arrives as a space
        assertThatThrownBy(() -> snapshot(query("2000-01-02T00:00:00 01:00"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(Map.of(), Map.of(SNAPSHOT_VARIABLE_NAME, "not-a-date-time")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("The variable __snapshot 'not-a-date-time' is not an ISO-8601 time.");
    }

    @Test
    void aVariableThatIsNotATextIsRefused() {
        assertThatThrownBy(() -> snapshot(Map.of(), Map.of(SNAPSHOT_VARIABLE_NAME, false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moreThanOneQueryParameterIsRefused() {
        assertThatThrownBy(() -> snapshot(query("2000-01-03T00:00:00Z", "2000-01-04T00:00:00Z"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
