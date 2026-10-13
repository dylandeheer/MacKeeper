/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.controller.player.PlayerStatsControl;
import toniarts.openkeeper.game.save.PlayerStatsSnapshots;
import static org.junit.jupiter.api.Assertions.*;

class PlayerStatsSnapshotsTest {
    @Test void restoredHistoryRetainsSpeciesPredicatesAndCountsWithoutReplayingActions() throws Exception {
        Creature imp = creature(7), troll = creature(12);
        var source = new PlayerStatsControl();source.creatureSlapped(imp);source.creatureSlapped(imp);
        source.creaturePickedUp(troll);source.creatureDropped(imp);
        var state = PlayerStatsSnapshots.decode(PlayerStatsSnapshots.encode(source.captureState()));
        var restoredImp = creature(7);var restoredTroll = creature(12);
        var restored = PlayerStatsControl.restoreState(state, id -> id == 7 ? restoredImp : restoredTroll);
        assertTrue(restored.hasSlapped());assertTrue(restored.hasSlapped(restoredImp));assertFalse(restored.hasSlapped(restoredTroll));
        assertTrue(restored.hasPickedUp(restoredTroll));assertFalse(restored.hasPickedUp(restoredImp));
        assertTrue(restored.hasDropped(restoredImp));assertFalse(restored.hasDropped(restoredTroll));
        assertEquals(state, restored.captureState());
        restored.creatureSlapped(restoredImp);source.creaturePickedUp(imp);
        assertEquals(2, state.slaps().get((short) 7));assertEquals(3, restored.captureState().slaps().get((short) 7));
        assertFalse(state.pickups().containsKey((short) 7));
    }

    @Test void zeroCountPresenceIsNotConfusedWithAbsentHistoryAndMapsAreImmutable() throws Exception {
        var mutable = new HashMap<Short, Integer>();mutable.put((short) 7, 0);
        var state = new PlayerStatsControl.State(mutable, Map.of(), Map.of());mutable.clear();
        var restored = PlayerStatsControl.restoreState(PlayerStatsSnapshots.decode(PlayerStatsSnapshots.encode(state)), id -> creature(id));
        assertTrue(restored.hasSlapped(creature(7)));assertFalse(restored.hasPickedUp());assertFalse(restored.hasDropped());
        assertThrows(UnsupportedOperationException.class, () -> state.slaps().clear());
        var overflow = PlayerStatsControl.restoreState(new PlayerStatsControl.State(Map.of((short) 7, Integer.MAX_VALUE), Map.of(), Map.of()), id -> creature(id));
        assertThrows(ArithmeticException.class, () -> overflow.creatureSlapped(creature(7)));
        assertEquals(Integer.MAX_VALUE, overflow.captureState().slaps().get((short) 7));
    }

    @Test void unknownDefinitionsAndInvalidCountsDoNotProduceAController() {
        var state = new PlayerStatsControl.State(Map.of((short) 7, 1), Map.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> PlayerStatsControl.restoreState(state, id -> null));
        assertThrows(IllegalArgumentException.class, () -> PlayerStatsControl.restoreState(state, id -> creature(9)));
        assertThrows(IllegalArgumentException.class, () -> new PlayerStatsControl.State(Map.of((short) 7, -1), Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new PlayerStatsControl.State(Map.of((short) 256, 1), Map.of(), Map.of()));
    }

    @Test void deterministicEncodingAndMalformedRecords() throws Exception {
        var first = new LinkedHashMap<Short, Integer>();first.put((short) 7, 2);first.put((short) 12, 8);
        var second = new LinkedHashMap<Short, Integer>();second.put((short) 12, 8);second.put((short) 7, 2);
        byte[] valid = PlayerStatsSnapshots.encode(new PlayerStatsControl.State(first, Map.of(), Map.of()));
        assertArrayEquals(valid, PlayerStatsSnapshots.encode(new PlayerStatsControl.State(second, Map.of(), Map.of())));
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);assertThrows(IOException.class, () -> PlayerStatsSnapshots.decode(truncated));
        }
        for (int offset : new int[]{0, 7, 8, 12}) {
            byte[] bad = valid.clone();bad[offset] = 99;assertThrows(IOException.class, () -> PlayerStatsSnapshots.decode(bad));
        }
        byte[] duplicate = valid.clone();duplicate[19] = 7;assertThrows(IOException.class, () -> PlayerStatsSnapshots.decode(duplicate));
        byte[] negative = valid.clone();negative[14] = (byte) 255;assertThrows(IOException.class, () -> PlayerStatsSnapshots.decode(negative));
        assertThrows(IOException.class, () -> PlayerStatsSnapshots.decode(Arrays.copyOf(valid, valid.length + 1)));
        assertThrows(IOException.class, () -> PlayerStatsSnapshots.decode(new byte[4629]));
    }
    static Creature creature(int id) {var value = new Creature();value.setCreatureId((short) id);return value;}
}
