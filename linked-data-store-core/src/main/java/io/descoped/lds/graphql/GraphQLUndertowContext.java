package io.descoped.lds.graphql;

import graphql.ExecutionInput;
import io.descoped.lds.core.utils.RequestTime;
import io.undertow.server.HttpServerExchange;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Implementation of {@link GraphQLContext}
 */
public class GraphQLUndertowContext implements GraphQLContext {

    // Name of the snapshot query variable
    static final String SNAPSHOT_QUERY_NAME = "snapshot";

    // Name of the snapshot graphql variable
    static final String SNAPSHOT_VARIABLE_NAME = "__" + SNAPSHOT_QUERY_NAME;

    private final HttpServerExchange exchange;
    private final ZonedDateTime snapshot;

    GraphQLUndertowContext(HttpServerExchange exchange, ExecutionInput executionInput) {
        this.exchange = Objects.requireNonNull(exchange);
        // Init snapshot.
        this.snapshot = getSnapshot(exchange.getQueryParameters(), executionInput.getVariables(), Clock.systemUTC());
    }

    /**
     * Gets the snapshot from server exchange query parameters or variables, in UTC.
     * <p>
     * If both the queryParameters and variable did not contain snapshot value, the given clock is used.
     * The value found in the queryParameters take precedence over the value found in the variables.
     *
     * @throws IllegalArgumentException if the query parameter SNAPSHOT_QUERY_NAME contains more than one value,
     *                                  if the variable SNAPSHOT_VARIABLE_NAME is not a string, or if either is not a
     *                                  time LDS can read ({@link RequestTime#parse}).
     */
    static ZonedDateTime getSnapshot(Map<String, Deque<String>> queryParameters, Map<String, Object> variables,
                                     Clock clock)
            throws IllegalArgumentException {
        return getSnapshotFromQuery(queryParameters)
                .or(() -> getSnapshotFromVariables(variables))
                .orElseGet(() -> ZonedDateTime.now(clock).withZoneSameInstant(RequestTime.UTC));
    }

    /**
     * The time a snapshot names, or an exception that says why it cannot be read: a time LDS cannot read is not
     * taken as now.
     */
    private static ZonedDateTime parse(String name, CharSequence text) {
        ZonedDateTime time = RequestTime.parse(text.toString());
        if (time == null) {
            throw new IllegalArgumentException("The " + name + " '" + text + "' is not an ISO-8601 time. Example of a "
                    + "valid formatted time is '" + RequestTime.EXAMPLE + "'");
        }
        return time;
    }

    /**
     * /**
     * Parses the one parameter that is mapped with the key SNAPSHOT_QUERY_NAME to a {@link ZonedDateTime}.
     * <p>
     * Is the map is empty or the parameter mapped with the key SNAPSHOT_QUERY_NAME is null then the result is empty.
     *
     * @throws IllegalArgumentException if the value to which the key SNAPSHOT_QUERY_NAME contains more than one value.
     */
    private static Optional<ZonedDateTime> getSnapshotFromQuery(Map<String, Deque<String>> queryParameters)
            throws IllegalArgumentException {
        if (!queryParameters.containsKey(SNAPSHOT_QUERY_NAME)) {
            return Optional.empty();
        }
        Deque<String> snapshotParameter = queryParameters.get(SNAPSHOT_QUERY_NAME);
        if (snapshotParameter.size() != 1) {
            throw new IllegalArgumentException("more than one " + SNAPSHOT_QUERY_NAME + " value in query");
        }
        String snapshotString = snapshotParameter.peekFirst();
        if (snapshotString == null) {
            return Optional.empty();
        }
        return Optional.of(parse("query parameter " + SNAPSHOT_QUERY_NAME, snapshotString));
    }

    /**
     * Parses the value that is mapped with the key SNAPSHOT_VARIABLE_NAME to a {@link ZonedDateTime}.
     * <p>
     * Is the map or the value mapped with the key SNAPSHOT_VARIABLE_NAME is null then the result is empty.
     *
     * @throws IllegalArgumentException if the value to which the key SNAPSHOT_VARIABLE_NAME is mapped is not a string.
     */
    private static Optional<ZonedDateTime> getSnapshotFromVariables(Map<String, Object> variables)
            throws IllegalArgumentException {
        if (!variables.containsKey(SNAPSHOT_VARIABLE_NAME)) {
            return Optional.empty();
        }
        Object snapshotObject = variables.get(SNAPSHOT_VARIABLE_NAME);
        if (snapshotObject == null) {
            return Optional.empty();
        }
        if (snapshotObject instanceof CharSequence snapshotText) {
            return Optional.of(parse("variable " + SNAPSHOT_VARIABLE_NAME, snapshotText));
        } else {
            throw new IllegalArgumentException("the variable " + SNAPSHOT_VARIABLE_NAME + " must be a string");
        }
    }

    @Override
    public HttpServerExchange getExchange() {
        return this.exchange;
    }

    @Override
    public ZonedDateTime getSnapshot() {
        return this.snapshot;
    }

}
