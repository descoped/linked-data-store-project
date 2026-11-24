package io.descoped.lds.core.lifecycle;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Subsystems start in the order they were added and stop in the reverse order, once; a subsystem that cannot start
 * stops every subsystem, also those not started, and one that fails to stop does not keep the others running.
 */
class LifecyclesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final Lifecycles lifecycles = new Lifecycles();

    private Lifecycle recorded(String name) {
        return Lifecycle.of(name, () -> events.add("start " + name), _ -> events.add("stop " + name));
    }

    @Test
    void subsystemsStartInTheOrderTheyWereAddedAndStopInTheReverseOrder() {
        lifecycles.add(recorded("store"));
        lifecycles.add(recorded("sagas"));
        lifecycles.add(recorded("http"));

        lifecycles.start(TIMEOUT);
        assertThat(lifecycles.stop(TIMEOUT)).isTrue();

        assertThat(events).containsExactly("start store", "start sagas", "start http",
                "stop http", "stop sagas", "stop store");
        assertThat(lifecycles.names()).containsExactly("store", "sagas", "http");
        assertThat(lifecycles.stopped()).containsExactly("http", "sagas", "store");
    }

    @Test
    void theSubsystemsAreStoppedOnce() {
        lifecycles.add(recorded("store"));
        lifecycles.start(TIMEOUT);

        assertThat(lifecycles.stop(TIMEOUT)).isTrue();
        assertThat(lifecycles.stop(TIMEOUT)).isFalse();

        assertThat(events).containsExactly("start store", "stop store");
    }

    @Test
    void aSubsystemThatCannotStartStopsEverySubsystemAlsoThoseNotStarted() {
        lifecycles.add(recorded("store"));
        lifecycles.add(Lifecycle.of("sagas", () -> {
            throw new IllegalStateException("no saga log");
        }, _ -> events.add("stop sagas")));
        lifecycles.add(recorded("http"));

        assertThatThrownBy(() -> lifecycles.start(TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("sagas could not start")
                .hasRootCauseMessage("no saga log");

        assertThat(events).containsExactly("start store", "stop http", "stop sagas", "stop store");
    }

    @Test
    void aSubsystemThatFailsToStopDoesNotKeepTheOthersRunning() {
        lifecycles.add(recorded("store"));
        lifecycles.add(Lifecycle.of("sagas", _ -> {
            throw new IllegalStateException("the pool would not stop");
        }));
        lifecycles.add(recorded("http"));

        lifecycles.stop(TIMEOUT);

        assertThat(events).containsExactly("stop http", "stop store");
        assertThat(lifecycles.stopped()).containsExactly("http", "sagas", "store");
    }

    @Test
    void aSubsystemIsNotAddedWhenTheSubsystemsAreStopping() {
        lifecycles.stop(TIMEOUT);

        assertThatThrownBy(() -> lifecycles.add(recorded("late")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void eachSubsystemIsGivenTheTimeout() {
        List<Duration> given = new CopyOnWriteArrayList<>();
        lifecycles.add(Lifecycle.of("store", given::add));

        lifecycles.stop(Duration.ofMillis(250));

        assertThat(given).containsExactly(Duration.ofMillis(250));
    }
}
