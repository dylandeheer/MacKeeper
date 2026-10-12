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
package toniarts.openkeeper.game.data;

import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author archdemon
 */
public final class GameResult {

    public enum ResultType {
        TOTAL_RATING,
        OVERALL_TOTAL_RATING,
        SPECIALS_FOUND,
        SPECIALS_MAX,

        LEVEL_WON,
        TIME_TAKEN,
        ENEMY_CREATURES_KILLED,
        HEROES_DESTROYED,
        ROOMS_CAPTURED,
        LAND_OWNED,
        GOLD_MINED,
        MANA_STORED,
        ITEMS_MANUFACTURED,
        CREATURES_COMMANDED,
        CREATURES_CONVERTED,
        CREATURE_TRAINING,
        ATTEMPTS_AT_LEVEL,
    };

    private Map<ResultType, Object> data = null;

    public GameResult() {

    }

    public static GameResult completed(boolean win, double elapsedSeconds) {
        if (!Double.isFinite(elapsedSeconds) || elapsedSeconds < 0) {
            throw new IllegalArgumentException("Invalid elapsed game time");
        }
        GameResult result = new GameResult();
        result.setData(ResultType.LEVEL_WON, win);
        result.setData(ResultType.TIME_TAKEN, elapsedSeconds);
        return result;
    }

    public boolean isWon() {
        return Boolean.TRUE.equals(getData(ResultType.LEVEL_WON));
    }

    public double getElapsedSeconds() {
        Number time = getData(ResultType.TIME_TAKEN);
        return time == null ? 0 : time.doubleValue();
    }

    public <T> T getData(ResultType key) {
        if (data == null) {
            return null;
        }

        Object s = data.get(key);
        return (T) s;
    }

    public void setData(ResultType key, Object value) {
        if (data == null) {
            data = new HashMap<>();
        }
        if (data.containsKey(key)) {
            return;
        }

        data.put(key, value);
    }
}
