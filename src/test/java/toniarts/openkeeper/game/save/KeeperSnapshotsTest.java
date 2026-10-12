/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.lang.reflect.Modifier;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.tools.convert.map.Player;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class KeeperSnapshotsTest {
    @Test void everyAuthoritativeFieldSurvivesAndMapBindingDoesNotOverrideRuntimeIdentity() throws Exception {
        Keeper source = populated();
        var decoded = KeeperSnapshots.decode(KeeperSnapshots.encode(source.captureState()));
        var definition = definition((short) 3);
        Keeper restored = Keeper.restoreState(decoded, definition);
        assertSame(definition, restored.getPlayer());
        Set<String> covered = new HashSet<>();
        for (var component : KeeperState.class.getRecordComponents()) covered.add(component.getName());
        covered.remove("currentResearchShared");
        for (var field : Keeper.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
            assertTrue(covered.remove(field.getName()), "Uncovered keeper field: " + field.getName());
            field.setAccessible(true);
            Object before = field.get(source), after = field.get(restored);
            if (before instanceof long[] values) assertArrayEquals(values, (long[]) after, field.getName());
            else assertEquals(before, after, field.getName());
        }
        assertTrue(covered.isEmpty(), "Snapshot fields no longer in keeper: " + covered);
        assertEquals(source.captureState(), restored.captureState());
        assertTrue(restored.isAi()); // The map definition itself is not AI.
        assertEquals(9_007_199_254_740_999L, restored.getGold());
        assertThrows(IllegalArgumentException.class, () -> Keeper.restoreState(decoded, definition((short) 4)));
    }

    @Test void sharedResearchKeepsIdentityWhileDetachedEqualResearchStaysDetached() throws Exception {
        for (boolean shared : List.of(false, true)) {
            var keeper = populated();
            var catalog = keeper.getAvailableSpells().getFirst();
            keeper.setCurrentResearch(shared ? catalog : KeeperState.Research.capture(catalog).restore());
            byte[] bytes = KeeperSnapshots.encode(keeper.captureState());
            var state = KeeperSnapshots.decode(bytes);
            assertEquals(shared, state.currentResearchShared());
            Keeper one = Keeper.restoreState(state, null), two = Keeper.restoreState(state, null);
            assertEquals(shared, one.getCurrentResearch() == one.getAvailableSpells().getFirst());
            assertInstanceOf(PlayerSpell.class, one.getCurrentResearch());
            one.getCurrentResearch().setResearch(888);
            assertEquals(shared ? 888 : 123, one.getAvailableSpells().getFirst().getResearch());
            assertEquals(123, two.getCurrentResearch().getResearch());
            assertEquals(123, keeper.getCurrentResearch().getResearch());
            assertArrayEquals(bytes, KeeperSnapshots.encode(state));
        }
    }

    @Test void removedResearchNullsAndMutableCollectionsArePreservedIndependently() throws Exception {
        var source = populated();
        source.getAvailableSpells().clear(); // Availability changes can leave the current target detached.
        source.setAiType(null);source.setDungeonHeartLocation(null);
        var snapshot = source.captureState();
        assertFalse(snapshot.currentResearchShared());
        var restored = Keeper.restoreState(KeeperSnapshots.decode(KeeperSnapshots.encode(snapshot)), null);
        assertEquals(snapshot, restored.captureState());
        source.getAvailableRooms().clear();source.getAvailableCreatures().clear();source.breakAlliance((short) 4);
        restored.getAvailableDoors().clear();restored.breakAlliance((short) 4);restored.recordCreatureKill((short) 4, false);
        assertEquals(1, snapshot.availableRooms().size());assertEquals(1, snapshot.availableDoors().size());
        assertEquals(2, snapshot.availableCreatures().size());assertTrue(snapshot.allies().contains((short) 4));
        assertEquals(3, snapshot.creatureKillsByPlayer().get(4));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.availableRooms().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.allies().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.creatureKillsByPlayer().clear());
        var blank = new Keeper(false, (short) 0);
        assertEquals(blank.captureState(), Keeper.restoreState(KeeperSnapshots.decode(KeeperSnapshots.encode(blank.captureState())), null).captureState());
    }

    @Test void malformedAndTruncatedInputsCannotPartiallyChangeTheSource() throws Exception {
        Keeper source = populated();byte[] valid = KeeperSnapshots.encode(source.captureState());
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertThrows(IOException.class, () -> KeeperSnapshots.decode(truncated), "length=" + length);
        }
        for (int offset : new int[]{0, 7, 10}) {
            byte[] bad = valid.clone();bad[offset] = 9;
            assertThrows(IOException.class, () -> KeeperSnapshots.decode(bad));
        }
        assertThrows(IOException.class, () -> KeeperSnapshots.decode(Arrays.copyOf(valid, valid.length + 1)));
        assertThrows(IOException.class, () -> KeeperSnapshots.decode(new byte[KeeperSnapshots.MAX_BYTES + 1]));
        // The last field is the sorted alliance list; introduce a repeated ID.
        byte[] duplicate = valid.clone();duplicate[duplicate.length - 1] = duplicate[duplicate.length - 3];
        assertThrows(IOException.class, () -> KeeperSnapshots.decode(duplicate));
        assertArrayEquals(valid, KeeperSnapshots.encode(source.captureState()));
    }

    @Test void invalidLiveValuesAndUnknownResearchSubclassesFailExplicitly() {
        var keeper = populated();keeper.setGold(-1);
        assertThrows(IllegalArgumentException.class, keeper::captureState);
        keeper = populated();keeper.setMana(2000);
        assertThrows(IllegalArgumentException.class, keeper::captureState);
        keeper = populated();keeper.getAvailableRooms().add(keeper.getAvailableRooms().getFirst());
        assertThrows(IllegalArgumentException.class, keeper::captureState);
        keeper = populated();keeper.getAvailableCreatures().add((short) -1);
        assertThrows(IllegalArgumentException.class, keeper::captureState);
        var unknown = new ResearchableEntity((short) 5, false, ResearchableType.ROOM) { };
        assertThrows(IllegalArgumentException.class, () -> KeeperState.Research.capture(unknown));
        assertThrows(IllegalArgumentException.class, () -> new KeeperState.Research((short) 1, ResearchableType.ROOM, true, false, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new KeeperState.Research((short) 1, ResearchableType.SPELL, true, false, -1, false));
    }

    @Test void eachResearchCategoryAndBaseSpellSubtypePreserveSharing() throws Exception {
        for (ResearchableType type : ResearchableType.values()) {
            var keeper = new Keeper(false, (short) 3);
            var research = new ResearchableEntity((short) 7, true, type);
            research.setResearch(41);research.setUpgraded(true);
            switch (type) {
                case ROOM -> keeper.getAvailableRooms().add(research);
                case SPELL -> keeper.getAvailableSpells().add(research);
                case DOOR -> keeper.getAvailableDoors().add(research);
                case TRAP -> keeper.getAvailableTraps().add(research);
            }
            keeper.setCurrentResearch(research);
            var restored = Keeper.restoreState(KeeperSnapshots.decode(KeeperSnapshots.encode(keeper.captureState())), null);
            assertEquals(ResearchableEntity.class, restored.getCurrentResearch().getClass());
            assertEquals(keeper.captureState(), restored.captureState());assertTrue(restored.captureState().currentResearchShared());
        }
        for (var aiType : toniarts.openkeeper.tools.convert.map.AI.AIType.values()) {
            var keeper = new Keeper(true, (short) 3);keeper.setAiType(aiType);
            assertEquals(aiType, KeeperSnapshots.decode(KeeperSnapshots.encode(keeper.captureState())).aiType());
        }
    }

    @Test void crossFieldValidationRejectsMissingAliasNegativeCountersAndWrongCatalogs() throws Exception {
        var state = populated().captureState();
        for (var change : List.of(Map.entry("goldReserve", -1),
                Map.entry("goldMined", -1), Map.entry("creaturesLost", -1L), Map.entry("destroyed", false),
                Map.entry("id", (short) 256), Map.entry("availableRooms", state.availableDoors()),
                Map.entry("creatureKillsByPlayer", List.of(1L)), Map.entry("availableSpells", List.of()))) {
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> replace(state, change.getKey(), change.getValue()));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
        var zero = new Keeper(false, (short) 3).captureState();
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> replace(zero, "goldReserve", 1));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> replace(zero, "currentResearchShared", true));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    static KeeperState replace(KeeperState state, String name, Object replacement) throws Exception {
        var fields = KeeperState.class.getRecordComponents();
        Object[] values = new Object[fields.length];Class<?>[] types = new Class<?>[fields.length];
        for (int i = 0; i < fields.length; i++) {
            types[i] = fields[i].getType();values[i] = fields[i].getName().equals(name) ? replacement : fields[i].getAccessor().invoke(state);
        }
        return KeeperState.class.getDeclaredConstructor(types).newInstance(values);
    }

    static Keeper populated() {
        var keeper = new Keeper(true, (short) 3);
        keeper.setGold(9_007_199_254_740_999L);keeper.setGoldReserve(555);keeper.setGoldMined(876);
        keeper.setMaxMana(1000);keeper.setMana(700);keeper.setManaGain(83);keeper.setManaLoose(51);
        keeper.setDungeonHeartLocation(new Point(54, 76));keeper.setPossession(true);
        var room = new ResearchableEntity((short) 21, true, ResearchableType.ROOM);room.setResearch(65);
        var spell = new PlayerSpell((short) 18, true);spell.setResearch(123);spell.setUpgraded(true);
        keeper.getAvailableRooms().add(room);keeper.getAvailableSpells().add(spell);keeper.setCurrentResearch(spell);
        keeper.getAvailableDoors().add(new ResearchableEntity((short) 3, false, ResearchableType.DOOR));
        keeper.getAvailableTraps().add(new ResearchableEntity((short) 4, true, ResearchableType.TRAP));
        keeper.getAvailableCreatures().addAll(List.of((short) 10, (short) 255));
        keeper.recordCreatureLoss();keeper.recordCreatureLoss();
        keeper.recordCreatureKill((short) 4, false);keeper.recordCreatureKill((short) 4, true);keeper.recordCreatureKill((short) 4, false);
        keeper.recordCreatureKill((short) 3, false);keeper.recordCreatureKill((short) 255, true);
        keeper.createAlliance((short) 4);keeper.createAlliance((short) 255);keeper.markDestroyed((short) 5);
        return keeper;
    }

    static Player definition(short id) throws Exception {
        var result = new Player();
        var setter = Player.class.getDeclaredMethod("setPlayerId", short.class);
        setter.setAccessible(true);setter.invoke(result, id);return result;
    }
}
