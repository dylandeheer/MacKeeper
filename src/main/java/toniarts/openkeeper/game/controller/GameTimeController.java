/*
 * Copyright (C) 2014-2025 OpenKeeper
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
package toniarts.openkeeper.game.controller;

import toniarts.openkeeper.utils.GameTimeCounter;

/**
 *
 * @author ArchDemon
 */
public final class GameTimeController extends GameTimeCounter implements IGameTimer {

    private long ticks;
    private final State initialState;

    public record State(long ticks, double elapsedSeconds) {
        public State {
            if (ticks < 0 || !Double.isFinite(elapsedSeconds) || elapsedSeconds < 0) {
                throw new IllegalArgumentException("Invalid game clock state");
            }
        }
    }

    public GameTimeController() { this(new State(0, 0)); }

    /** A restored clock starts at its saved value rather than resetting to zero. */
    public GameTimeController(State initialState) {
        this.initialState = java.util.Objects.requireNonNull(initialState);
        ticks = initialState.ticks();timeElapsed = initialState.elapsedSeconds();
    }

    public State captureState() { return new State(ticks, timeElapsed); }

    @Override
    public void start() {
        ticks = initialState.ticks();
        timeElapsed = initialState.elapsedSeconds();
    }

    @Override
    public void stop() {
        // nope
    }

    @Override
    public void processTick(float tpf) {
        super.processTick(tpf);
        ticks++;
    }

    @Override
    public double getGameTime() {
        return timeElapsed;
    }
}
