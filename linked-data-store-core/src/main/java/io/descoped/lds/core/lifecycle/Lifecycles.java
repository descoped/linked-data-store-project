package io.descoped.lds.core.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The subsystems of the server, in the order they need each other: a subsystem is added after the subsystems it
 * needs, as it is made. They are started in that order and stopped in the reverse order, so that a subsystem stops
 * before what it needs does.
 * <p>
 * A subsystem is stopped also when it was made and not started, so that the start of LDS that fails part of the way
 * (a store that cannot be reached, a model that is wrong) lets go of what was made before it. A subsystem that fails
 * to start, or to stop, is logged, and the others are stopped all the same. Stopping is done once: a second call does
 * nothing, and does not wait for the first to be done.
 */
public final class Lifecycles {

    private static final Logger LOG = LoggerFactory.getLogger(Lifecycles.class);

    private final List<Lifecycle> subsystems = new ArrayList<>();
    private final List<String> stopped = new CopyOnWriteArrayList<>();
    private final AtomicBoolean stopping = new AtomicBoolean();

    /**
     * Adds a subsystem, after the subsystems it needs.
     *
     * @param subsystem the subsystem
     * @return the subsystem
     * @throws IllegalStateException when the subsystems are stopping
     */
    public synchronized Lifecycle add(Lifecycle subsystem) {
        if (stopping.get()) {
            throw new IllegalStateException("The server is stopping: " + subsystem.name() + " is not added");
        }
        subsystems.add(subsystem);
        return subsystem;
    }

    /**
     * Starts every subsystem, in the order they were added. When one cannot start, every subsystem is stopped,
     * those made after it included, and the start fails.
     *
     * @param timeout how long the subsystems that started may take to stop, when one cannot start
     * @throws IllegalStateException when a subsystem cannot start
     */
    public void start(Duration timeout) {
        for (Lifecycle subsystem : snapshot()) {
            String name = subsystem.name();
            try {
                LOG.info("Starting {} ...", name);
                subsystem.start();
            } catch (RuntimeException e) {
                stop(timeout);
                throw new IllegalStateException(name + " could not start", e);
            }
        }
    }

    /**
     * Stops every subsystem, in the reverse order of the one they were added in, also those that did not start.
     *
     * @param timeout how long the work each subsystem is doing may take to complete, at most
     * @return true when this call stopped them; false when they were stopped, or are being stopped, by another
     */
    public boolean stop(Duration timeout) {
        if (!stopping.compareAndSet(false, true)) {
            return false; // stopped, or being stopped by another caller
        }
        for (Lifecycle subsystem : snapshot().reversed()) {
            String name = subsystem.name();
            try {
                subsystem.stop(timeout);
                LOG.debug("{} stopped", name);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.error("Interrupted while stopping {}", name, e);
            } catch (RuntimeException e) {
                LOG.error("{} did not stop cleanly", name, e);
            }
            stopped.add(name);
        }
        return true;
    }

    private synchronized List<Lifecycle> snapshot() {
        return List.copyOf(subsystems);
    }

    /**
     * The names of the subsystems, in the order they are started.
     *
     * @return the names
     */
    public List<String> names() {
        return snapshot().stream().map(Lifecycle::name).toList();
    }

    /**
     * The names of the subsystems that have been stopped, in the order they were stopped.
     *
     * @return the names
     */
    public List<String> stopped() {
        return List.copyOf(stopped);
    }
}
