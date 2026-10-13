/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.controller;

import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.data.ObjectiveType;
import toniarts.openkeeper.game.save.*;
import static org.junit.jupiter.api.Assertions.*;

class LevelObjectivesTest {
    @Test void goneTargetsArePrunedButLivingTargetsRemain() {
        var data = new GameEntityData();
        try {
            var living = data.createEntity(); var gone = data.createEntity();
            data.setComponent(living, new CreatureComponent()); data.setComponent(gone, new CreatureComponent());
            var keeper = new toniarts.openkeeper.game.data.Keeper(false, (short) 4);
            LevelObjectives.addPlayer(data, (short) 4); LevelObjectives.addPlayer(data, (short) 5);
            LevelObjectives.addCreature(data, gone, ObjectiveType.KILL);
            LevelObjectives.addCreature(data, living, ObjectiveType.IMPRISON);
            LevelObjectives.reconcile(data, Map.of((short) 4, keeper));
            assertEquals(3, LevelObjectives.targets(data).size());
            keeper.markDestroyed((short) 3); data.removeComponent(gone, CreatureComponent.class);
            LevelObjectives.reconcile(data, Map.of((short) 4, keeper));
            assertEquals(1, LevelObjectives.targets(data).size());
            assertEquals(living, LevelObjectives.targets(data).getFirst().creatureId());
            assertNull(data.getComponent(gone, PlayerObjective.class));
        } finally { data.close(); }
    }

    @Test void keeperAndCreatureTargetsShareSixSlotsAndOffRemovesOnlyFirstMatchingCreature() {
        var data = new GameEntityData();
        try {
            var creature = data.createEntity(); data.setComponent(creature, new CreatureComponent());
            assertTrue(LevelObjectives.addPlayer(data, (short) 2));
            assertTrue(LevelObjectives.addCreature(data, creature, ObjectiveType.KILL));
            assertTrue(LevelObjectives.addCreature(data, creature, ObjectiveType.CONVERT));
            for (short player = 3; player <= 5; player++) assertTrue(LevelObjectives.addPlayer(data, player));
            assertFalse(LevelObjectives.addPlayer(data, (short) 6));
            assertFalse(LevelObjectives.addCreature(data, creature, ObjectiveType.IMPRISON));
            assertEquals(ObjectiveType.KILL, data.getComponent(creature, PlayerObjective.class).objective);
            LevelObjectives.removeCreature(data, creature);
            assertEquals(List.of(0, 2, 3, 4, 5), LevelObjectives.targets(data).stream().map(LevelObjectives.Target::slot).toList());
            assertEquals(ObjectiveType.CONVERT, data.getComponent(creature, PlayerObjective.class).objective);
            assertTrue(LevelObjectives.addPlayer(data, (short) 6));
            assertEquals((short) 6, LevelObjectives.targets(data).get(1).playerId());
            LevelObjectives.removeCreature(data, creature);
            assertNull(data.getComponent(creature, PlayerObjective.class));
            assertEquals(5, LevelObjectives.targets(data).size());
            LevelObjectives.removeCreature(data, creature);
            assertEquals(5, LevelObjectives.targets(data).size());
        } finally { data.close(); }
    }

    @Test void archiveRetainsHolesTargetsAndAllocatorThenContinuesIdentically() throws Exception {
        var data = new GameEntityData();
        try {
            var creature = data.createEntity(); data.setComponent(creature, new CreatureComponent());
            LevelObjectives.addCreature(data, creature, ObjectiveType.KILL);
            LevelObjectives.addPlayer(data, (short) 2);
            LevelObjectives.addCreature(data, creature, ObjectiveType.CONVERT);
            LevelObjectives.removeCreature(data, creature);
            var restored = GameEntityData.restore(EntitySnapshots.decode(EntitySnapshots.encode(data.capture())));
            try {
                assertEquals(LevelObjectives.targets(data), LevelObjectives.targets(restored));
                LevelObjectives.validate(restored, Set.of((short) 2));
                for (var world : List.of(data, restored)) {
                    LevelObjectives.addCreature(world, creature, ObjectiveType.IMPRISON);
                    LevelObjectives.removeCreature(world, creature);
                    LevelObjectives.addPlayer(world, (short) 3);
                }
                assertArrayEquals(EntitySnapshots.encode(data.capture()), EntitySnapshots.encode(restored.capture()));
            } finally { restored.close(); }
        } finally { data.close(); }
    }

    @Test void malformedRegistriesAreRejectedBeforeSimulation() {
        var data = new GameEntityData();
        try {
            var entry = data.createEntity();
            for (var invalid : List.of(new LevelObjective(-1, (short) 2, null, null),
                    new LevelObjective(6, (short) 2, null, null), new LevelObjective(0, (short) 3, null, null),
                    new LevelObjective(0, (short) 2, null, ObjectiveType.KILL))) {
                data.setComponent(entry, invalid);
                assertThrows(IllegalArgumentException.class, () -> LevelObjectives.validate(data, Set.of((short) 2)));
            }
            data.setComponent(entry, new LevelObjective(0, (short) 2, null, null));
            data.setComponent(data.createEntity(), new LevelObjective(0, (short) 2, null, null));
            assertThrows(IllegalArgumentException.class, () -> LevelObjectives.validate(data, Set.of((short) 2)));
        } finally { data.close(); }
    }
}
