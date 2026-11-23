package io.descoped.lds.core.search;

import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.IndexUpdate;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An index queue in memory, for a transaction log that keeps nothing (discard) or keeps it in memory: what the index
 * has not taken when LDS stops is lost, and is then found again only by a rebuild of the index.
 */
public final class MemoryIndexQueue implements IndexQueue {

    private final Clock clock;
    private final TreeMap<Long, Slot> slots = new TreeMap<>(); // guarded by this
    private long next = 1; // guarded by this

    /**
     * A queue on the clock of the system.
     */
    public MemoryIndexQueue() {
        this(Clock.systemUTC());
    }

    MemoryIndexQueue(Clock clock) {
        this.clock = clock;
    }

    private record Slot(IndexUpdate update, Instant added, Instant availableAt, int attempts) {
    }

    @Override
    public Completable add(IndexUpdate update) {
        return Completable.fromAction(() -> {
            Instant now = clock.instant();
            synchronized (this) {
                slots.put(next++, new Slot(update, now, now, 0));
            }
        });
    }

    @Override
    public Single<List<Entry>> take(int max, Duration lease) {
        return Single.fromCallable(() -> {
            Instant now = clock.instant();
            List<Entry> taken = new ArrayList<>();
            synchronized (this) {
                for (Map.Entry<Long, Slot> slot : slots.entrySet()) {
                    if (taken.size() == max) {
                        break;
                    }
                    Slot value = slot.getValue();
                    if (!value.availableAt().isAfter(now)) {
                        slot.setValue(new Slot(value.update(), value.added(), now.plus(lease), value.attempts()));
                        taken.add(new Entry(slot.getKey(), value.update(), value.attempts()));
                    }
                }
            }
            return taken;
        });
    }

    @Override
    public Completable remove(Collection<Entry> entries) {
        return Completable.fromAction(() -> {
            synchronized (this) {
                entries.forEach(entry -> slots.remove(entry.sequence()));
            }
        });
    }

    @Override
    public Completable postpone(Collection<Entry> entries, Duration delay) {
        return Completable.fromAction(() -> {
            Instant availableAt = clock.instant().plus(delay);
            synchronized (this) {
                for (Entry entry : entries) {
                    slots.computeIfPresent(entry.sequence(), (_, slot) ->
                            new Slot(slot.update(), slot.added(), availableAt, slot.attempts() + 1));
                }
            }
        });
    }

    @Override
    public Single<Boolean> holds(String namespace, String entity, String id) {
        return Single.fromCallable(() -> {
            synchronized (this) {
                return slots.values().stream().map(Slot::update).anyMatch(update -> update.namespace().equals(namespace)
                        && update.entity().equals(entity) && update.id().equals(id));
            }
        });
    }

    @Override
    public Single<Lag> lag() {
        return Single.fromCallable(() -> {
            synchronized (this) {
                // the slots are in the order of their sequence, and an update is added later than those before it
                Instant oldest = slots.isEmpty() ? null : slots.firstEntry().getValue().added();
                return new Lag(slots.size(), oldest);
            }
        });
    }

    @Override
    public boolean durable() {
        return false;
    }

    @Override
    public void close() {
        // nothing is held open
    }
}
