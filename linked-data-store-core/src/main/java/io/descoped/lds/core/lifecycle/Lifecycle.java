package io.descoped.lds.core.lifecycle;

import java.time.Duration;

/**
 * A subsystem of LDS that is started and stopped with the server: a store, a pool of threads, a client, a worker, the
 * HTTP server. Subsystems are kept in {@link Lifecycles}, which starts them in the order they need each other and
 * stops them in the reverse order.
 */
public interface Lifecycle {

    /**
     * The name of the subsystem, as the log and the stop order show it.
     *
     * @return the name
     */
    String name();

    /**
     * Starts the subsystem. Called once, after the subsystems it needs have started. A subsystem that is live once it
     * is made (a store, a client) does nothing here.
     *
     * @throws Exception when it cannot start; the subsystems started before it are then stopped
     */
    default void start() throws Exception {
        // live once it is made
    }

    /**
     * Stops the subsystem and lets go of what it holds: threads, connections, clients. Called once, after the
     * subsystems that need it have stopped, also when it was made and not started.
     *
     * @param timeout how long the work it is doing may take to complete, at most
     * @throws Exception when it cannot stop cleanly; the subsystems after it are stopped all the same
     */
    void stop(Duration timeout) throws Exception;

    /**
     * A subsystem that is live once it is made, and is stopped by the action given.
     *
     * @param name the name of the subsystem
     * @param stop how it is stopped
     * @return the subsystem
     */
    static Lifecycle of(String name, Stop stop) {
        return new Lifecycle() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void stop(Duration timeout) throws Exception {
                stop.stop(timeout);
            }
        };
    }

    /**
     * A subsystem that is started and stopped by the actions given.
     *
     * @param name  the name of the subsystem
     * @param start how it is started
     * @param stop  how it is stopped
     * @return the subsystem
     */
    static Lifecycle of(String name, Start start, Stop stop) {
        return new Lifecycle() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void start() throws Exception {
                start.start();
            }

            @Override
            public void stop(Duration timeout) throws Exception {
                stop.stop(timeout);
            }
        };
    }

    /**
     * How a subsystem is started.
     */
    @FunctionalInterface
    interface Start {
        /**
         * Starts it.
         *
         * @throws Exception when it cannot start
         */
        void start() throws Exception;
    }

    /**
     * How a subsystem is stopped.
     */
    @FunctionalInterface
    interface Stop {
        /**
         * Stops it.
         *
         * @param timeout how long the work it is doing may take to complete, at most
         * @throws Exception when it cannot stop cleanly
         */
        void stop(Duration timeout) throws Exception;
    }
}
