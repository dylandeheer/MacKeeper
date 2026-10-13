/*
 * Copyright (C) 2014-2016 OpenKeeper
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
package toniarts.openkeeper.game.controller.player;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;
import toniarts.openkeeper.tools.convert.map.Creature;

/**
 * A class to hold player's miscellaneous statistics
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class PlayerStatsControl {

    private final Map<Creature, Integer> slapsMap = new HashMap<>();
    private final Map<Creature, Integer> pickUpsMap = new HashMap<>();
    private final Map<Creature, Integer> dropsMap = new HashMap<>();

    /** Creature definitions are rebound by ID; zero-count key presence is retained. */
    public record State(Map<Short, Integer> slaps, Map<Short, Integer> pickups, Map<Short, Integer> drops) {
        public State {
            slaps = checked(slaps);pickups = checked(pickups);drops = checked(drops);
        }
        private static Map<Short, Integer> checked(Map<Short, Integer> values) {
            values = Map.copyOf(values);
            if (values.size() > 256) throw new IllegalArgumentException("Too many creature statistics");
            values.forEach((id, count) -> {
                if (id < 0 || id > 255 || count < 0) throw new IllegalArgumentException("Invalid creature statistic");
            });
            return values;
        }
    }

    public State captureState() {
        return new State(capture(slapsMap), capture(pickUpsMap), capture(dropsMap));
    }

    private static Map<Short, Integer> capture(Map<Creature, Integer> source) {
        Map<Short, Integer> values = new HashMap<>();
        source.forEach((creature, count) -> {
            if (values.put(creature.getCreatureId(), count) != null) throw new IllegalStateException("Duplicate creature statistic ID");
        });
        return values;
    }

    public static PlayerStatsControl restoreState(State state, IntFunction<Creature> definitions) {
        Objects.requireNonNull(state);Objects.requireNonNull(definitions);
        PlayerStatsControl result = new PlayerStatsControl();
        restore(state.slaps(), result.slapsMap, definitions);
        restore(state.pickups(), result.pickUpsMap, definitions);
        restore(state.drops(), result.dropsMap, definitions);
        return result;
    }

    private static void restore(Map<Short, Integer> source, Map<Creature, Integer> target, IntFunction<Creature> definitions) {
        source.forEach((id, count) -> {
            Creature definition = definitions.apply(id);
            if (definition == null || definition.getCreatureId() != id) throw new IllegalArgumentException("Unknown saved creature ID: " + id);
            target.put(definition, count);
        });
    }

    public boolean hasSlapped() {
        return !slapsMap.isEmpty();
    }

    public boolean hasSlapped(Creature creature) {
        return slapsMap.containsKey(creature);
    }

    public void creatureSlapped(Creature creature) {
        increaseMapCount(creature, slapsMap);
    }

    private void increaseMapCount(Creature creature, Map<Creature, Integer> map) {
        Integer count = map.get(creature);
        if (count == null) {
            count = 0;
        }
        count = Math.incrementExact(count);
        map.put(creature, count);
    }

    public boolean hasPickedUp() {
        return !pickUpsMap.isEmpty();
    }

    public boolean hasPickedUp(Creature creature) {
        return pickUpsMap.containsKey(creature);
    }

    public void creaturePickedUp(Creature creature) {
        increaseMapCount(creature, pickUpsMap);
    }

    public boolean hasDropped() {
        return !dropsMap.isEmpty();
    }

    public boolean hasDropped(Creature creature) {
        return dropsMap.containsKey(creature);
    }

    public void creatureDropped(Creature creature) {
        increaseMapCount(creature, dropsMap);
    }

}
