/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.task;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Task IDs belong to one world; restoration never consumes an ID or the wall clock. */
public final class TaskIdentityAllocator {
    public record State(long nextId) {
        public State {if (nextId < 0) throw new IllegalArgumentException("Invalid task allocation cursor");}
    }
    public record Identity(long id, Instant created) {
        public Identity {
            if (id < 0) throw new IllegalArgumentException("Invalid task identity");
            Objects.requireNonNull(created);
        }
    }
    private final AtomicLong next;
    private final Supplier<Instant> clock;
    public TaskIdentityAllocator() {this(new State(0), Instant::now);}
    public TaskIdentityAllocator(State saved, Supplier<Instant> clock) {
        next = new AtomicLong(saved.nextId());this.clock = Objects.requireNonNull(clock);
    }
    /** Game tasks use the saved simulation clock; equal-time tasks are ordered by their stable ID. */
    public static TaskIdentityAllocator forSimulation(State saved, toniarts.openkeeper.game.controller.IGameTimer timer) {
        Objects.requireNonNull(timer);
        return new TaskIdentityAllocator(saved, () -> {
            double seconds = timer.getGameTime();
            if (!Double.isFinite(seconds) || seconds < 0 || seconds >= Long.MAX_VALUE / 1_000_000_000d)
                throw new IllegalStateException("Invalid simulation time for task creation");
            return Instant.EPOCH.plusNanos((long)(seconds * 1_000_000_000d));
        });
    }

    public Identity allocate() {
        var created = Objects.requireNonNull(clock.get());
        long id = next.getAndUpdate(value -> {
            if (value == Long.MAX_VALUE) throw new IllegalStateException("Task ID space exhausted");
            return value + 1;
        });
        return new Identity(id, created);
    }
    public State captureState() {return new State(next.get());}
    public void validateRestored(Identity identity) {
        if (identity.id() >= next.get()) throw new IllegalArgumentException("Unallocated task identity");
    }
}
