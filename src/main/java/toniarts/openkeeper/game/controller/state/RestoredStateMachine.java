/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.controller.state;

import com.badlogic.gdx.ai.fsm.DefaultStateMachine;
import com.badlogic.gdx.ai.fsm.State;

/** A normal state machine initialized from saved values without enter/exit callbacks. */
public final class RestoredStateMachine<E, S extends State<E>> extends DefaultStateMachine<E, S> {
    public RestoredStateMachine(E owner, S current, S previous, S global) {
        super(owner, current, global);
        previousState = previous;
    }
}
