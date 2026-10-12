/*
 * Copyright (C) 2014-2015 OpenKeeper
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
package toniarts.openkeeper.game.data;

/**
 *
 * @author ArchDemon
 */
public final class GameTimer {

    private float time = 0;
    private boolean active = false;

    public GameTimer() {
    }

    public record State(float time, boolean active) {
        public State {
            if (!Float.isFinite(time) || time < 0) throw new IllegalArgumentException("Invalid level timer state");
        }
    }

    public State captureState() { return new State(time, active); }

    public static GameTimer restoreState(State state) {
        GameTimer timer = new GameTimer();timer.time = state.time();timer.active = state.active();return timer;
    }

    /**
     * Activates and resets the timer
     */
    public void initialize() {
        active = true;
        time = 0;
    }

    /**
     * Is the timer active or not
     *
     * @return {@code true} if active
     */
    public boolean isActive() {
        return active;
    }

    public void update(float tpf) {
        if (active) {
            time += tpf;

            // Overflow protection
            if (time < 0) {
                time = Float.MAX_VALUE;
                active = false;
            }
        }
    }

    public float getTime() {
        return time;
    }
}
