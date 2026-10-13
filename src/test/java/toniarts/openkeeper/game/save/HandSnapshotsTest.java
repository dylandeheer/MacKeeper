/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.player.PlayerHandControl;
import toniarts.openkeeper.game.data.Keeper;
import static org.junit.jupiter.api.Assertions.*;

class HandSnapshotsTest {
    @Test void stagedRestorePreservesLifoAndCapacityWithoutReplayingPickups() throws Exception {
        var original = new GameEntityData();
        try {
            var keeper = new Keeper(false, (short) 3);var hand = new PlayerHandControl(keeper, 3, original);
            EntityId creature = original.createEntity(), object = original.createEntity();
            var component = new CreatureComponent();component.creatureId = 12;
            original.setComponent(creature, component);original.setComponent(object, new ObjectComponent((short) 8, null));
            hand.push(creature);hand.push(object);
            var handState = HandSnapshots.decode(HandSnapshots.encode(hand.captureState()));
            assertEquals(List.of(creature, object), handState.bottomToTop());
            var store = GameEntityData.restore(original.capture());
            try {
                byte[] before = EntitySnapshots.encode(store.capture());
                var restored = PlayerHandControl.restoreState(keeper, store, handState);
                assertArrayEquals(before, EntitySnapshots.encode(store.capture()));
                assertFalse(restored.isFull());assertEquals(object, restored.peek());assertEquals(creature, restored.peek(1));
                assertEquals(object, restored.pop());assertNull(store.getComponent(object, InHand.class));
                assertEquals(2, store.getComponent(creature, InHand.class).index);
                restored.push(object);assertEquals(1, store.getComponent(object, InHand.class).index);
                assertEquals(object, restored.pop());assertEquals(creature, restored.pop());assertNull(restored.pop());
                assertTrue(restored.isEmpty());assertEquals(2, hand.getCount());
                assertNotNull(original.getComponent(object, InHand.class));
                assertEquals(List.of(creature, object), handState.bottomToTop());
            } finally {store.close();}
        } finally {original.close();}
    }

    @Test void mismatchedMissingAndExtraComponentsRejectWithoutMutation() throws Exception {
        var store = new GameEntityData();
        try {
            var keeper = new Keeper(false, (short) 3);var hand = new PlayerHandControl(keeper, 3, store);
            EntityId first = store.createEntity(), second = store.createEntity();
            store.setComponent(first, new ObjectComponent((short) 5, null));store.setComponent(second, new ObjectComponent((short) 6, null));
            hand.push(first);hand.push(second);var valid = hand.captureState();
            var reversed = new PlayerHandControl.State((short) 3, 3, List.of(second, first));
            var omitted = new PlayerHandControl.State((short) 3, 3, List.of(first));
            var wrongCapacity = new PlayerHandControl.State((short) 3, 4, List.of(first, second));
            for (var state : List.of(reversed, omitted, wrongCapacity)) assertRejectedUnchanged(keeper, store, state);
            assertRejectedUnchanged(new Keeper(false, (short) 4), store, valid);
            for (var broken : List.of(new InHand(1, (short) 4, ViewType.OBJECT, (short) 6),
                    new InHand(1, (short) 3, ViewType.CREATURE, (short) 6),
                    new InHand(1, (short) 3, ViewType.OBJECT, (short) 7))) {
                store.setComponent(second, broken);assertRejectedUnchanged(keeper, store, valid);
            }
            store.removeComponent(second, InHand.class);assertRejectedUnchanged(keeper, store, valid);
            hand.pop();hand.push(second);
            store.setComponent(second, new CreatureComponent());assertRejectedUnchanged(keeper, store, valid);
        } finally {store.close();}
    }

    @Test void otherKeepersHandsAreIndependentAndEmptyZeroCapacityIsValid() {
        var store = new GameEntityData();
        try {
            var keeper = new Keeper(false, (short) 3);
            EntityId other = store.createEntity();store.setComponent(other, new InHand(0, (short) 4, ViewType.OBJECT, (short) 5));
            var restored = PlayerHandControl.restoreState(keeper, store, new PlayerHandControl.State((short) 3, 0, List.of()));
            assertTrue(restored.isEmpty());assertTrue(restored.isFull());
        } finally {store.close();}
    }

    @Test void immutableOrderAndMalformedWireRecordsAreBounded() throws Exception {
        var ids = new ArrayList<>(List.of(new EntityId(0), new EntityId(999)));
        var state = new PlayerHandControl.State((short) 3, 5, ids);ids.clear();
        assertEquals(2, state.bottomToTop().size());assertThrows(UnsupportedOperationException.class, () -> state.bottomToTop().clear());
        byte[] valid = HandSnapshots.encode(state);assertEquals(state, HandSnapshots.decode(valid));
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);assertThrows(IOException.class, () -> HandSnapshots.decode(truncated));
        }
        for (int offset : new int[]{0, 7, 8, 10, 14}) {
            byte[] bad = valid.clone();bad[offset] = 99;assertThrows(IOException.class, () -> HandSnapshots.decode(bad));
        }
        byte[] duplicate = valid.clone();Arrays.fill(duplicate, 26, 34, (byte) 0);
        assertThrows(IOException.class, () -> HandSnapshots.decode(duplicate));
        assertThrows(IOException.class, () -> HandSnapshots.decode(Arrays.copyOf(valid, valid.length + 1)));
        assertThrows(IOException.class, () -> HandSnapshots.decode(new byte[HandSnapshots.MAX_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> new PlayerHandControl.State((short) 3, 0, List.of(new EntityId(0))));
        assertThrows(IllegalArgumentException.class, () -> new PlayerHandControl.State((short) 3, 65_537, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PlayerHandControl.State((short) 3, 2, List.of(new EntityId(-1))));
    }

    static void assertRejectedUnchanged(Keeper keeper, GameEntityData store, PlayerHandControl.State state) throws Exception {
        byte[] before = EntitySnapshots.encode(store.capture());
        assertThrows(IllegalArgumentException.class, () -> PlayerHandControl.restoreState(keeper, store, state));
        assertArrayEquals(before, EntitySnapshots.encode(store.capture()));
    }
}
