/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

/** A real local/hosted session whose acknowledgements participate in simulation capture. */
public interface SimulationSession {
    SessionTransitions getTransitions();
    void restoreTransitions(SessionTransitions.State state);
}
