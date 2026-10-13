/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

import java.util.*;
import toniarts.openkeeper.utils.SimulationGate;

/** Server-visible cinematic acknowledgements, sharing the world's capture boundary. */
public final class SessionTransitions {
    public record State(Set<Short> pendingPlayers) {
        public State {
            var copy = new TreeSet<Short>();
            for (Short player : pendingPlayers) {
                if (player == null || player < 0 || player > 255)
                    throw new IllegalArgumentException("Invalid transition player");
                copy.add(player);
            }
            pendingPlayers = Collections.unmodifiableSet(copy);
        }
    }

    private final SimulationGate gate = new SimulationGate();
    private final Set<Short> pending = new TreeSet<>();

    public SimulationGate gate() {return gate;}

    /** Record before delivery, retaining the original one-outstanding-flag-per-player protocol. */
    public void begin(short player, Runnable deliver) {
        new State(Set.of(player));
        gate.mutate(() -> {
            synchronized (pending) {pending.add(player);}
            deliver.run();
        });
    }

    public void complete(short player, Runnable notify) {
        gate.mutate(() -> {
            synchronized (pending) {pending.remove(player);}
            notify.run();
        });
    }

    public void disconnect(short player) {complete(player, () -> {});}

    public boolean isInTransition() {
        synchronized (pending) {return !pending.isEmpty();}
    }

    /** The owner is detached; collect alongside world state inside gate.snapshot for a complete capture. */
    public State capture() {
        synchronized (pending) {return new State(pending);}
    }

    /** Staging only: no callbacks, and no transition for an absent receiving player. */
    public void restore(State state, Set<Short> receivingPlayers) {
        restore(state, () -> receivingPlayers);
    }

    /** Resolve transport membership inside the same exclusive boundary as restoration. */
    public void restore(State state, java.util.function.Supplier<Set<Short>> receivingPlayers) {
        gate.snapshot(() -> {
            if (!receivingPlayers.get().containsAll(state.pendingPlayers()))
                throw new IllegalArgumentException("Saved transition has no receiving player");
            synchronized (pending) {pending.clear();pending.addAll(state.pendingPlayers());}
            return null;
        });
    }
}
