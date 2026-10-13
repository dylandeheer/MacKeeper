/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector2f;
import com.simsilica.es.EntityId;
import com.simsilica.es.base.DefaultEntityData;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.CreaturesController;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.controller.ICreaturesController.SpawnType;
import static org.junit.jupiter.api.Assertions.*;

class CreatureProgressionTest {
    @Test void fractionalLevelMultipliersReachWagesCombatAndMovement() {
        Fixture f = new Fixture();
        EntityId id = f.spawn(1);
        f.data.setComponent(id, new Health(40, 100));
        f.controller.levelUpCreature(id, 2, 7);
        CreatureComponent c = f.data.getComponent(id, CreatureComponent.class);
        assertEquals(525, c.pay); // 500 at 105%, not 500 at truncated 100%
        assertEquals(125, f.data.getComponent(id, Health.class).maxHealth);
        assertEquals(50, f.data.getComponent(id, Health.class).health);
        assertEquals(25, f.data.getComponent(id, CreatureMeleeAttack.class).damage);
        assertEquals(.5f, f.data.getComponent(id, CreatureMeleeAttack.class).rechargeTime);
        assertEquals(2.1f, c.speed, .0001f);
        assertEquals(c.speed, f.data.getComponent(id, Mobile.class).maxSpeed);
        assertTrue(f.data.getComponent(id, Mobile.class).canFly);
        assertEquals(15, c.researchPerSecond);
        assertEquals(130, f.data.getComponent(id, CreatureExperience.class).experienceToNextLevel);
        assertEquals(7, f.data.getComponent(id, CreatureExperience.class).experience);
    }

    @Test void levelChangesNeverResetCarriedGoldEvenAboveNewCapacity() {
        Fixture f = new Fixture();
        EntityId id = f.spawn(1);
        f.data.setComponent(id, new Gold(2345, 1000));
        for (int level : new int[]{2, 3, 1, 1}) {
            f.controller.levelUpCreature(id, level, 0);
            assertEquals(2345, f.data.getComponent(id, Gold.class).gold);
            assertEquals(f.data.getComponent(id, Gold.class).maxGold, f.data.getComponent(id, CreatureComponent.class).maxGoldHeld);
        }
    }

    @Test void mapGoldOverridesRemainExactWhileGeneratedCreaturesUseScaledDefaults() {
        Fixture f = new Fixture();
        EntityId generated = f.spawn(2);
        assertEquals(37, f.data.getComponent(generated, Gold.class).gold);
        Thing.KeeperCreature thing = new Thing.KeeperCreature();
        thing.setCreatureId((short)7); thing.setPlayerId((short)3); thing.setLevel((short)2); thing.setInitialHealth(100);
        for (int gold : new int[]{0, 321}) {
            thing.setGoldHeld(gold);
            EntityId placed = f.controller.spawnCreature(thing, new Vector2f());
            assertEquals(gold, f.data.getComponent(placed, Gold.class).gold);
        }
    }

    @Test void spellsUnlockAtThresholdAndExistingSpellCooldownIsPreserved() {
        Fixture f = new Fixture();
        EntityId id = f.spawn(1);
        List<EntityId> first = f.data.getComponent(id, CreatureSpells.class).creatureSpells;
        assertEquals(1, first.size());
        EntityId low = first.getFirst();
        var old = f.data.getComponent(low, toniarts.openkeeper.game.component.CreatureSpell.class);
        f.data.setComponent(low, new toniarts.openkeeper.game.component.CreatureSpell(old, 42));
        f.controller.levelUpCreature(id, 3, 0);
        List<EntityId> unlocked = f.data.getComponent(id, CreatureSpells.class).creatureSpells;
        assertEquals(2, unlocked.size());
        assertTrue(unlocked.contains(low));
        assertEquals(42, f.data.getComponent(low, toniarts.openkeeper.game.component.CreatureSpell.class).attactStartTime);
        EntityId high = unlocked.stream().filter(e -> !e.equals(low)).findFirst().orElseThrow();
        f.controller.levelUpCreature(id, 1, 0);
        assertEquals(List.of(low), f.data.getComponent(id, CreatureSpells.class).creatureSpells);
        assertNull(f.data.getComponent(high, toniarts.openkeeper.game.component.CreatureSpell.class));
    }

    @Test void healthRescalingUsesWideArithmeticAndDoesNotReviveDeadCreatures() {
        Fixture f = new Fixture(); f.creature.getAttributes().setHp(200000);
        EntityId id = f.spawn(1);
        f.data.setComponent(id, new Health(100000, 200000));
        f.controller.levelUpCreature(id, 2, 0);
        assertEquals(125000, f.data.getComponent(id, Health.class).health);
        f.data.setComponent(id, new Health(0, 250000));
        f.controller.levelUpCreature(id, 1, 0);
        assertEquals(0, f.data.getComponent(id, Health.class).health);
    }

    @Test void missingIndividualModifiersDefaultToBaseValues() {
        Fixture f = new Fixture();
        f.stats.put(3, Map.of());
        EntityId id = f.spawn(3);
        assertEquals(500, f.data.getComponent(id, CreatureComponent.class).pay);
        assertEquals(2, f.data.getComponent(id, Mobile.class).maxSpeed);
        assertEquals(25, f.data.getComponent(id, Gold.class).gold);
    }

    static class Fixture {
        final DefaultEntityData data = new DefaultEntityData();
        final Creature creature = new Creature();
        final Map<Integer, Map<Variable.CreatureStats.StatType, Variable.CreatureStats>> stats = new HashMap<>();
        final CreaturesController controller;
        Fixture() {
            creature.setCreatureId((short)7); creature.setName("Test creature");
            creature.setFlags(EnumSet.of(Creature.CreatureFlag.CAN_FLY));
            creature.setMeleeAttackType(Creature.AttackType.MELEE_BLADE);
            creature.setMeleeDamage(20); creature.setMeleeRecharge(1);
            var a = creature.getAttributes();
            a.setHp(100); a.setHeight(1); a.setPay(500); a.setMaxGoldHeld(1000); a.setInitialGoldHeld((short)25);
            a.setSpeed(2); a.setRunSpeed(4); a.setResearchPerSecond(12); a.setExpForNextLevel(100);
            Creature.Spell low = new Creature.Spell(), high = new Creature.Spell();
            low.setCreatureSpellId((short)1); low.setLevelAvailable((short)1);
            high.setCreatureSpellId((short)2); high.setLevelAvailable((short)3);
            creature.setSpells(List.of(low, high));
            Map<Short, CreatureSpell> spells = new HashMap<>();
            for (short id = 1; id <= 2; id++) {
                CreatureSpell spell = new CreatureSpell(); spell.setCreatureSpellId(id); spell.setRange(5); spell.setRechargeTime(2); spells.put(id,spell);
            }
            var level2 = new EnumMap<Variable.CreatureStats.StatType, Variable.CreatureStats>(Variable.CreatureStats.StatType.class);
            put(level2, Variable.CreatureStats.StatType.PAY,105);
            put(level2, Variable.CreatureStats.StatType.HEALTH,125);
            put(level2, Variable.CreatureStats.StatType.MELEE_DAMAGE,125);
            put(level2, Variable.CreatureStats.StatType.MELEE_RECHARGE_TIME_SECONDS,50);
            put(level2, Variable.CreatureStats.StatType.SPEED_TILES_PER_SECOND,105);
            put(level2, Variable.CreatureStats.StatType.MAX_GOLD_HELD,105);
            put(level2, Variable.CreatureStats.StatType.INITIAL_GOLD_HELD,150);
            put(level2, Variable.CreatureStats.StatType.RESEARCH_POINTS_PER_SECOND,125);
            put(level2, Variable.CreatureStats.StatType.EXPERIENCE_POINTS_FOR_NEXT_LEVEL,130);
            stats.put(2, level2);
            IKwdFile kwd = proxy(IKwdFile.class, (name,args) -> switch(name) {
                case "getThings" -> List.of();
                case "getCreature", "getImp" -> creature;
                case "getCreatureStats" -> stats.get((int)args[0]);
                case "getCreatureSpellById" -> spells.get((short)args[0]);
                default -> throw new AssertionError(name);
            });
            IMapController map = proxy(IMapController.class, (name,args) -> {
                if (name.equals("getRoomControllerByCoordinates")) return null;
                throw new AssertionError(name);
            });
            controller = new CreaturesController(kwd, data, Map.of(), () -> 0, null, map, null);
        }
        EntityId spawn(int level) { return controller.spawnCreature((short)7,(short)3,level,new Vector2f(),SpawnType.CONJURE); }
        static void put(Map<Variable.CreatureStats.StatType,Variable.CreatureStats> stats, Variable.CreatureStats.StatType type, int value) {
            Variable.CreatureStats stat = new Variable.CreatureStats(); stat.setStatId(type);stat.setValue(value);stats.put(type,stat);
        }
    }
    interface Call { Object invoke(String name, Object[] args); }
    static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m.getName(),a)));
    }
}
