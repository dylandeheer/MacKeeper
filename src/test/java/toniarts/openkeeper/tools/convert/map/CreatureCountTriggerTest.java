/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import com.simsilica.es.base.DefaultEntityData;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.trigger.TriggerGenericData;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerControl;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.proxy;

class CreatureCountTriggerTest {
    @Test void levelCountIncludesHigherLevelsButExcludesWorkersOtherOwnersAndDeadEntities() {
        try (Fixture f = new Fixture()) {
            f.add(3, 7, false, 1, 0);
            f.add(3, 7, false, 2, 0);
            f.add(3, 8, false, 10, 0);
            f.add(3, 7, true, 10, 0);
            f.add(4, 7, false, 10, 0);
            f.data.removeComponent(f.add(3, 7, false, 10, 0), Health.class);
            f.data.removeComponent(f.add(3, 7, false, 10, 0), CreatureExperience.class);
            assertEquals(2, f.creatures.countCreaturesAtLevel((short)3, 2));
            assertEquals(1, f.creatures.countCreaturesAtLevel((short)3, 10));
            assertEquals(0, f.creatures.countCreaturesAtLevel((short)3, 11));
            assertEquals(0, f.creatures.countCreaturesAtLevel((short)5, 1));
        }
    }

    @Test void freshCountsFollowLevelOwnershipAndLifetimeChangesWithoutRosterTicks() {
        try (Fixture f = new Fixture()) {
            EntityId id = f.add(3, 7, false, 1, 0);
            assertEquals(0, f.creatures.countCreaturesAtLevel((short)3, 2));
            f.data.setComponent(id, new CreatureExperience(2, 0, 100, 0, 0));
            assertEquals(1, f.creatures.countCreaturesAtLevel((short)3, 2));
            f.data.setComponent(id, new Owner((short)4, (short)3));
            assertEquals(0, f.creatures.countCreaturesAtLevel((short)3, 2));
            assertEquals(1, f.creatures.countCreaturesAtLevel((short)4, 2));
            f.data.removeComponent(id, Health.class);
            assertEquals(0, f.creatures.countCreaturesAtLevel((short)4, 2));
            f.data.setComponent(id, new Health(100, 100));
            assertEquals(1, f.creatures.countCreaturesAtLevel((short)4, 2));
            f.data.removeEntity(id);
            assertEquals(0, f.creatures.countCreaturesAtLevel((short)4, 2));
        }
    }

    @Test void moodCountsUseNativeStatesSpeciesAndWorkerExclusion() {
        try (Fixture f = new Fixture()) {
            int threshold = CreatureMood.toRuntimeThreshold(50);
            f.add(3, 7, false, 1, threshold - 1);
            f.add(3, 7, false, 1, threshold);
            f.add(3, 7, false, 1, 32767);
            EntityId angry = f.add(3, 7, false, 1, 32768);
            f.add(3, 8, false, 1, 0);
            f.add(3, 8, false, 1, 65533);
            f.add(3, 7, true, 1, 0);
            f.add(3, 7, true, 1, 65533);
            f.add(4, 7, false, 1, 0);
            f.data.removeComponent(f.add(3, 7, false, 1, 0), Health.class);
            assertEquals(2, f.creatures.countCreaturesByMood((short)3, (short)0, 0));
            assertEquals(1, f.creatures.countCreaturesByMood((short)3, (short)7, 0));
            assertEquals(2, f.creatures.countCreaturesByMood((short)3, (short)7, 1));
            assertEquals(2, f.creatures.countCreaturesByMood((short)3, (short)0, 2));
            assertEquals(1, f.creatures.countCreaturesByMood((short)3, (short)7, 2));
            f.data.setComponent(angry, new CreatureMood(0, 0, 0, 0, 0, 0));
            assertEquals(0, f.creatures.countCreaturesByMood((short)3, (short)7, 2));
            assertEquals(2, f.creatures.countCreaturesByMood((short)3, (short)7, 0));
        }
    }

    @Test void loadedCountPredicatesUseLiteralComparisonAndSemanticLevelField() {
        try (Fixture f = new Fixture()) {
            f.add(3, 7, false, 2, 0);
            f.add(3, 7, false, 3, 65533);
            for (var type : TYPES) for (var comparison : TriggerGeneric.ComparisonType.values()) {
                int count = type == TriggerGeneric.TargetType.PLAYER_CREATURES_AT_LEVEL ? 2 : 1;
                for (int value : new int[]{0, 1, 2, 3}) {
                    f.trigger(type, comparison, value);
                    boolean expected = switch (comparison) {
                        case EQUAL_TO -> count == value;
                        case NOT_EQUAL_TO -> count != value;
                        case GREATER_THAN -> count > value;
                        case GREATER_OR_EQUAL_TO -> count >= value;
                        case LESS_THAN -> count < value;
                        case LESS_OR_EQUAL_TO -> count <= value;
                        case NONE -> false;
                    };
                    assertEquals(expected, f.control().active(), type + " " + comparison + " " + value);
                }
            }
        }
    }

    @Test void reachingThresholdRunsChildActionOnceThroughTriggerTraversal() {
        try (Fixture f = new Fixture()) {
            EntityId id = f.add(3, 7, false, 1, 0);
            f.trigger(TriggerGeneric.TargetType.PLAYER_CREATURES_AT_LEVEL,
                    TriggerGeneric.ComparisonType.GREATER_OR_EQUAL_TO, 1);
            var control = f.control();
            tick(control);
            assertEquals(0, f.actions);
            f.data.setComponent(id, new CreatureExperience(3, 0, 100, 0, 0));
            tick(control);
            assertEquals(1, f.actions);
            tick(control);
            assertEquals(1, f.actions);
        }
    }

    static final List<TriggerGeneric.TargetType> TYPES = List.of(
            TriggerGeneric.TargetType.PLAYER_CREATURES_AT_LEVEL,
            TriggerGeneric.TargetType.PLAYER_HAPPY_CREATURES,
            TriggerGeneric.TargetType.PLAYER_ANGRY_CREATURES);
    static void tick(PlayerTriggerControl control) { for (int i=0; i<12; i++) control.update(.1f); }

    static class Fixture implements AutoCloseable {
        final DefaultEntityData data = new DefaultEntityData();
        final Map<Integer, Trigger> triggers = new HashMap<>();
        final Map<Short, Creature> species = new HashMap<>();
        final IKwdFile kwd;
        final CreaturesController creatures;
        int actions;
        Fixture() {
            for (short id : new short[]{7, 8}) {
                Creature c = new Creature(); c.setCreatureId(id);
                c.getAttributes().setUnhappyThreshold((short)50); species.put(id, c);
            }
            kwd = proxy(IKwdFile.class, (name, args) -> switch (name) {
                case "getThings" -> List.of();
                case "getCreature" -> species.get(args[0]);
                case "getTriggers" -> triggers;
                default -> throw new AssertionError(name);
            });
            creatures = new CreaturesController(kwd, data, Map.of(), () -> 0, null, null, null);
        }
        EntityId add(int player, int speciesId, boolean worker, int level, int anger) {
            EntityId id = data.createEntity();
            CreatureComponent c = new CreatureComponent(); c.creatureId = (short)speciesId; c.worker = worker;
            data.setComponents(id, c, new Owner((short)player, (short)player), new Health(100, 100),
                    new CreatureExperience(level, 0, 100, 0, 0), new CreatureMood(anger, 0, 0, 0, 0, 0));
            return id;
        }
        void trigger(TriggerGeneric.TargetType type, TriggerGeneric.ComparisonType comparison, int value) {
            TriggerGeneric t = new TriggerGeneric(null);
            t.setId(1); t.setType(type); t.setTargetValueComparison(comparison); t.setRepeatTimes((short)0); t.setIdChild(2);
            t.setUserData("level", (short)2); t.setUserData("creatureId", (short)0); t.setUserData("value", value);
            // The original predicates ignore these bytes, even when they resemble a player comparison.
            t.setUserData("flag", (short)0); t.setUserData("playerId", (short)99); t.setUserData("targetId", (short)10);
            TriggerAction a = new TriggerAction(null); a.setId(2); a.setType(TriggerAction.ActionType.WIN_GAME);
            triggers.put(1, t); triggers.put(2, a);
        }
        Control control() {
            ILevelInfo info = proxy(ILevelInfo.class, (name, args) -> switch (name) {
                case "getLevelData" -> kwd;
                case "getPaydayCycle" -> 0L;
                default -> throw new AssertionError(name);
            });
            IGameController game = proxy(IGameController.class, (name, args) -> {
                assertEquals("endGame", name); assertEquals((short)3, args[0]); assertEquals(true, args[1]); actions++; return null;
            });
            return new Control(game, info, creatures);
        }
        @Override public void close() { data.close(); }
    }
    static class Control extends PlayerTriggerControl {
        Control(IGameController game, ILevelInfo info, ICreaturesController creatures) {
            super(game, info, () -> 0, null, creatures, 1, (short)3, null);
        }
        boolean active() { return isActive((TriggerGenericData)root.getChild(0)); }
    }
}
