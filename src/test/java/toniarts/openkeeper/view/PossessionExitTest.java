/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PossessionExitTest {
    @Test void explicitExitRestoresPlayerOnlyOnceAndAfterDisablingControls() {
        class State extends PossessionInteractionState {
            boolean active = true;
            int restored;
            State() { super(false); }
            @Override public boolean isEnabled() { return active; }
            @Override public void setEnabled(boolean value) { active = value; }
            @Override protected void onExit() { assertFalse(active); restored++; }
            @Override protected void onActionChange(Action action) { }
        }
        State state = new State();
        state.exitPossession();
        state.exitPossession();
        assertFalse(state.isEnabled());
        assertEquals(1, state.restored);
    }
}
