/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

import java.util.*;
import java.util.function.*;

/** Single render consumer, concurrent callback producers; no engine objects or executable closures in state. */
public final class PresentationQueue {
    public static final int MAX_EVENTS = 65_536;
    public record State(List<PresentationEvent> events) {
        public State {
            if (events.size() > MAX_EVENTS) throw new IllegalArgumentException("Too many saved presentation events");
            events = List.copyOf(events);
        }
    }
    private final Deque<PresentationEvent> events = new ArrayDeque<>();
    private boolean draining;
    private boolean closed;

    public synchronized void add(PresentationEvent event) {
        if (!closed) events.addLast(Objects.requireNonNull(event));
    }
    public synchronized State capture() {
        requireIdle();
        return new State(new ArrayList<>(events));
    }
    public synchronized void restore(State state) {
        requireIdle();
        Objects.requireNonNull(state);
        events.clear();
        events.addAll(state.events());
    }
    public synchronized void close() {
        closed = true;
        events.clear();
    }

    /**
     * Drain at most the events present at entry, so reentrant producers cannot
     * monopolize a frame. Delivery is attempted once: a failed receiver may have
     * applied partial side effects and cannot safely be retried automatically.
     */
    public void drain(BooleanSupplier ready, Consumer<PresentationEvent> deliver) {
        final int limit;
        synchronized (this) {
            if (closed) return;
            requireIdle();
            draining = true;
            limit = events.size();
        }
        try {
            for (int i = 0; i < limit; i++) {
                if (!ready.getAsBoolean()) return;
                final PresentationEvent event;
                synchronized (this) {
                    if (closed || events.isEmpty()) return;
                    event = events.removeFirst();
                }
                // Never hold this monitor during callbacks: they can acquire the simulation gate.
                deliver.accept(event);
            }
        } finally {
            synchronized (this) {draining = false;}
        }
    }
    private void requireIdle() {
        if (closed || draining) throw new IllegalStateException("Presentation state is closed or dispatching; capture on the render thread between updates");
    }
}
