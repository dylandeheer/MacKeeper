/*
 * Copyright (C) 2014-2017 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenKeeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenKeeper.  If not, see <http://www.gnu.org/licenses/>.
 */
package toniarts.openkeeper.game.state;

import com.jme3.app.Application;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.input.KeyInput;
import com.jme3.input.RawInputListener;
import com.jme3.input.event.JoyAxisEvent;
import com.jme3.input.event.JoyButtonEvent;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.input.event.MouseMotionEvent;
import com.jme3.input.event.TouchEvent;
import toniarts.openkeeper.Main;

/**
 * A state that handles ended game actions, such as cinematics and stuff
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class EndGameState extends AbstractAppState implements RawInputListener {

    private Main app;
    private AppStateManager stateManager;
    private final PlayerState playerState;
    private boolean dismissed;
    private final boolean restoring;
    private Thread renderThread;
    private boolean freshInputRequired;
    private final java.util.Set<Integer> pressedKeys = new java.util.HashSet<>();

    public EndGameState(PlayerState playerState) {
        this(playerState, false);
    }

    private EndGameState(PlayerState playerState, boolean restoring) {
        this.playerState = java.util.Objects.requireNonNull(playerState);
        this.restoring = restoring;
        freshInputRequired = restoring;
    }

    /** Prepare the listener without replaying result text, widescreen changes or profile updates. */
    public static EndGameState restored(PlayerState playerState) {
        if (!playerState.hasGameResult() || playerState.isEnabled())
            throw new IllegalArgumentException("Restored result input requires a completed disabled player");
        return new EndGameState(playerState, true);
    }

    boolean isAwaitingInputFor(PlayerState owner) {
        return owner == playerState && isInitialized() && !dismissed;
    }

    void restoreAwaitingInput() {
        if (Thread.currentThread() != renderThread || !isInitialized() || dismissed
                || stateManager.getState(EndGameState.class) != this)
            throw new IllegalStateException("Result input belongs to the active render-thread owner");
        freshInputRequired = true;pressedKeys.clear();
    }

    @Override
    public void initialize(AppStateManager stateManager, Application app) {
        super.initialize(stateManager, app);

        renderThread = Thread.currentThread();
        this.app = (Main) app;
        this.stateManager = stateManager;

        // Add the listener
        app.getInputManager().addRawInputListener(this);

        // Add the texts
        if (!restoring) {
            playerState.setWideScreen(true);
            playerState.setGeneralText(2901);
        }
    }

    @Override
    public void cleanup() {
        app.getInputManager().removeRawInputListener(this);
        super.cleanup();
    }

    @Override
    public void beginInput() {

    }

    @Override
    public void endInput() {

    }

    @Override
    public void onJoyAxisEvent(JoyAxisEvent evt) {

    }

    @Override
    public void onJoyButtonEvent(JoyButtonEvent evt) {

    }

    @Override
    public void onMouseMotionEvent(MouseMotionEvent evt) {

    }

    @Override
    public void onMouseButtonEvent(MouseButtonEvent evt) {

    }

    @Override
    public void onKeyEvent(KeyInputEvent evt) {
        if (dismissed || !isInitialized() || stateManager.getState(EndGameState.class) != this) return;
        int key = evt.getKeyCode();
        if (key != KeyInput.KEY_SPACE && key != KeyInput.KEY_ESCAPE && key != KeyInput.KEY_LCONTROL) return;
        // Even rejected stale releases must not reach the global Escape pause binding.
        evt.setConsumed();
        if (freshInputRequired) {
            if (evt.isPressed()) {
                if (!evt.isRepeating()) pressedKeys.add(key);
                return;
            }
            if (!pressedKeys.remove(key)) return;
        }
        // Consume the release before restoring controls, including the global Escape pause binding.
        if (!evt.isPressed()) {
            if (evt.getKeyCode() == KeyInput.KEY_SPACE) {
                dismissed = true;
                evt.setConsumed();
                stateManager.detach(this);
                playerState.quitToDebriefing();
            } else if (evt.getKeyCode() == KeyInput.KEY_ESCAPE || evt.getKeyCode() == KeyInput.KEY_LCONTROL) {
                dismissed = true;
                evt.setConsumed();
                stateManager.detach(this);
                playerState.setEnabled(true);
                playerState.setWideScreen(false);
            }
        }
    }

    @Override
    public void onTouchEvent(TouchEvent evt) {

    }

}
