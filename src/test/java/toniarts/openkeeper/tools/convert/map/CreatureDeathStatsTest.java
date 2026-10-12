/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.badlogic.gdx.ai.fsm.StateMachine;
import com.jme3.export.*;
import com.jme3.export.binary.*;
import com.simsilica.es.EntityId;
import com.simsilica.es.base.DefaultEntityData;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.controller.entity.*;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.logic.*;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.proxy;

class CreatureDeathStatsTest {
    @Test void finalDeathCreditsVictimAndLastAttackerExactlyOnceIncludingWorkers() {
        try (Fixture f = new Fixture()) {
            for (boolean worker : new boolean[]{false, true}) {
                EntityId id = f.add(3, false, true);
                CreatureComponent c = new CreatureComponent(f.data.getComponent(id, CreatureComponent.class)); c.worker = worker; f.data.setComponent(id,c);
                f.hit(id, 30, 4); f.tick(.1f);
                f.hit(id, 100, 5); f.tick(.1f); f.tick(30);
            }
            assertEquals(2, f.player(3).getCreaturesLost());
            assertEquals(0, f.player(4).getCreatureKills((short)3));
            assertEquals(2, f.player(5).getCreatureKills((short)3));
            assertEquals(2, f.player(5).getCreatureKills((short)0));
        }
    }

    @Test void knockoutRetainsAttributionButOnlyDeathCounts() {
        try (Fixture f = new Fixture()) {
            EntityId id = f.add(3, false, false);
            f.hit(id, 100, 4); f.tick(.1f);
            assertNotNull(f.data.getComponent(id, Unconscious.class));
            assertEquals(0, f.player(3).getCreaturesLost());
            assertEquals(0, f.player(4).getCreatureKills((short)3));
            assertNull(f.data.getComponent(id, Damage.class));
            assertEquals(4, f.data.getComponent(id, LastDamageSource.class).playerId);
            f.tick(11); f.tick(11);
            assertNull(f.data.getComponent(id, Health.class));
            assertEquals(1, f.player(3).getCreaturesLost());
            assertEquals(1, f.player(4).getCreatureKills((short)3));
        }
    }

    @Test void rescuedCreatureDoesNotCountAsDeadAndLaterAttackerReceivesKill() {
        try (Fixture f = new Fixture()) {
            EntityId id = f.add(3, false, false);
            f.hit(id, 100, 4); f.tick(.1f);
            f.data.removeComponent(id, Unconscious.class); f.data.setComponent(id, new Health(100,100));
            f.tick(11);
            assertEquals(0, f.player(3).getCreaturesLost());
            f.hit(id, 100, 5); f.tick(.1f); f.tick(11);
            assertEquals(0, f.player(4).getCreatureKills((short)3));
            assertEquals(1, f.player(5).getCreatureKills((short)3));
        }
    }

    @Test void heroAndSelfKillsKeepFilteredCountsSeparateFromEnemyTotal() {
        try (Fixture f = new Fixture()) {
            f.hit(f.add(1, true, true), 100, 3); f.tick(.1f);
            f.hit(f.add(3, false, true), 100, 3); f.tick(.1f);
            assertEquals(1, f.player(3).getHeroesKilled());
            assertEquals(0, f.player(3).getCreatureKills((short)0));
            assertEquals(1, f.player(3).getCreatureKills((short)1));
            assertEquals(1, f.player(3).getCreatureKills((short)3));
            assertEquals(1, f.player(1).getCreaturesLost());
            assertEquals(1, f.player(3).getCreaturesLost());
        }
    }

    @Test void unattributedDamageClearsEarlierCreditAndRecordsOnlyLoss() {
        try (Fixture f = new Fixture()) {
            EntityId id = f.add(3, false, true);
            f.hit(id, 20, 4); f.tick(.1f);
            f.hit(id, 100, 0); f.tick(.1f);
            assertEquals(1, f.player(3).getCreaturesLost());
            assertEquals(0, f.player(4).getCreatureKills((short)3));
        }
    }

    @Test void creaturesWithoutCorpsesCountBeforeRemovalButObjectsDoNot() {
        try (Fixture f = new Fixture()) {
            f.species.get((short)7).setFlags(EnumSet.noneOf(Creature.CreatureFlag.class));
            EntityId creature = f.add(3, false, false);
            f.hit(creature, 100, 4); f.tick(.1f); f.tick(20);
            assertNull(f.data.getComponent(creature, CreatureComponent.class));
            EntityId object = f.data.createEntity();
            f.data.setComponents(object, new Health(10,10), new Owner((short)3,(short)3));
            f.hit(object, 10, 4); f.tick(.1f);
            assertEquals(1, f.player(3).getCreaturesLost());
            assertEquals(1, f.player(4).getCreatureKills((short)3));
        }
    }

    @Test void removalAndAuthoredCorpsesDoNotInventDeaths() {
        try (Fixture f = new Fixture()) {
            EntityId removed = f.add(3, false, true), corpse = f.add(3, false, true);
            f.data.removeEntity(removed); f.data.removeComponent(corpse, Health.class);
            f.tick(20);
            assertEquals(0, f.player(3).getCreaturesLost());
        }
    }

    @Test void prisonDeathsAndSkeletonReplacementCountTheOriginalOwnerOnce() {
        for (boolean skeleton : new boolean[]{false,true}) try (Fixture f = new Fixture(skeleton)) {
            EntityId id=f.add(3,false,false);
            f.hit(id,10,4); f.tick(.1f);
            f.data.setComponents(id,new Owner((short)3,(short)4),new Health(1,100),
                    new Position(0,new com.jme3.math.Vector3f()),new CreatureImprisoned(0,0));
            f.tick(1); f.tick(1);
            assertEquals(1,f.player(3).getCreaturesLost());
            assertEquals(0,f.player(4).getCreaturesLost());
            assertEquals(1,f.player(4).getCreatureKills((short)3));
            assertEquals(skeleton?1:0,f.transformations);
            assertNull(f.data.getComponent(id,Health.class));
        }
    }

    @Test void tortureDeathRetainsCreditWithoutAnotherDamageComponent() {
        try (Fixture f = new Fixture()) {
            EntityId id=f.add(3,false,false);
            f.hit(id,10,4); f.tick(.1f);
            f.species.get((short)7).getAttributes().setTortureHpChange((short)-100);
            f.data.setComponents(id,new Position(0,new com.jme3.math.Vector3f()),new CreatureTortured(0,0,0));
            f.tick(1); f.tick(1);
            assertEquals(1,f.player(3).getCreaturesLost());
            assertEquals(1,f.player(4).getCreatureKills((short)3));
        }
    }

    @Test void serializedKeeperRetainsTotalsAndEveryVictimCounter() throws Exception {
        Keeper player = new Keeper(false,(short)3);
        player.recordCreatureLoss(); player.recordCreatureKill((short)1,true);
        player.recordCreatureKill((short)4,false); player.recordCreatureKill((short)4,false);
        player.recordCreatureKill((short)3,false);
        var bytes = new ByteArrayOutputStream(); BinaryExporter.getInstance().save(player,bytes);
        Keeper restored = (Keeper)BinaryImporter.getInstance().load(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(1,restored.getCreaturesLost()); assertEquals(1,restored.getHeroesKilled());
        assertEquals(2,restored.getCreatureKills((short)0)); assertEquals(2,restored.getCreatureKills((short)4));
        assertEquals(1,restored.getCreatureKills((short)1)); assertEquals(1,restored.getCreatureKills((short)3));
    }

    @Test void legacySerializationDefaultsToZeroAndInvalidStatsAreRejected() throws Exception {
        InputCapsule defaults = proxy(InputCapsule.class,(name,args)->args[args.length-1]);
        Keeper legacy = new Keeper(); legacy.read(proxy(JmeImporter.class,(name,args)->defaults));
        assertEquals(0,legacy.getCreaturesLost()); assertEquals(0,legacy.getCreatureKills((short)3));
        for (String field : List.of("creaturesLost","enemyCreaturesKilled","heroesKilled","creatureKillsByPlayer")) {
            InputCapsule invalid = proxy(InputCapsule.class,(name,args)-> {
                if (args[0].equals(field)) return field.equals("creatureKillsByPlayer") ? new long[]{1} : -1L;
                return args[args.length-1];
            });
            assertThrows(IOException.class,()->new Keeper().read(proxy(JmeImporter.class,(name,args)->invalid)));
        }
    }

    static class Fixture implements AutoCloseable {
        final DefaultEntityData data = new DefaultEntityData();
        final Map<Short,Creature> species = new HashMap<>();
        final Map<Short,Keeper> keepers = new HashMap<>();
        final HealthSystem health;
        int transformations;
        Fixture() { this(false); }
        Fixture(boolean skeleton) {
            for(short id:new short[]{7,8}) {
                Creature c = new Creature(); c.setCreatureId(id);
                c.setFlags(id==8 ? EnumSet.of(Creature.CreatureFlag.GENERATE_DEAD_BODY,Creature.CreatureFlag.IS_GOOD)
                        : EnumSet.of(Creature.CreatureFlag.GENERATE_DEAD_BODY)); species.put(id,c);
            }
            var players = new ArrayList<IPlayerController>();
            for(short id:new short[]{1,2,3,4,5}) {
                Keeper k = new Keeper(false,id); keepers.put(id,k);
                var roster = new toniarts.openkeeper.game.controller.player.PlayerCreatureControl(k,species.get((short)7),species.values());
                players.add(proxy(IPlayerController.class,(name,args)->switch(name) {
                    case "getKeeper"->k;case "getCreatureControl"->roster;default->throw new AssertionError(name);
                }));
            }
            IKwdFile kwd = proxy(IKwdFile.class,(name,args)-> { assertEquals("getCreature",name); return species.get(args[0]); });
            ILevelInfo info = proxy(ILevelInfo.class,(name,args)-> { assertEquals("getLevelData",name); return kwd; });
            ICreaturesController creatures = proxy(ICreaturesController.class,(name,args)-> {
                if (name.equals("turnCreatureIntoAnother")) {
                    assertEquals((short)4,args[1]);assertEquals((short)8,args[2]);
                    data.removeEntity((EntityId)args[0]);transformations++;return null;
                }
                assertEquals("createController",name);
                return proxy(ICreatureController.class,(method,values)-> {
                    assertEquals("getStateMachine",method);
                    return proxy(StateMachine.class,(operation,parameters)-> { assertEquals("changeState",operation); return null; });
                });
            });
            IEntityPositionLookup lookup = proxy(IEntityPositionLookup.class,(name,args)-> {
                if(name.equals("getEntityLocation")) return proxy(toniarts.openkeeper.game.map.IMapTileInformation.class,
                        (method,values)->{assertEquals("getLocation",method);return new toniarts.openkeeper.utils.Point(0,0);});
                assertEquals("getEntityController",name); EntityId id=(EntityId)args[0];
                return proxy(IEntityController.class,(method,values)-> { assertEquals("remove",method); data.removeEntity(id); return null; });
            });
            Map<Variable.MiscVariable.MiscType,Variable.MiscVariable> settings = new EnumMap<>(Variable.MiscVariable.MiscType.class);
            for(var type:List.of(Variable.MiscVariable.MiscType.CREATURE_DYING_STATE_DURATION_SECONDS,
                    Variable.MiscVariable.MiscType.PRISON_MODIFY_CREATURE_HEALTH_PER_SECOND,
                    Variable.MiscVariable.MiscType.MODIFY_HEALTH_OF_CREATURE_IN_LAIR_PER_SECOND)) {
                var value = new Variable.MiscVariable(); value.setValue(type==Variable.MiscVariable.MiscType.CREATURE_DYING_STATE_DURATION_SECONDS ? 10
                        : type==Variable.MiscVariable.MiscType.PRISON_MODIFY_CREATURE_HEALTH_PER_SECOND ? -1 : 0); settings.put(type,value);
            }
            Room prison=new Room();prison.setCreatedCreatureId((short)8);
            IMapController map=proxy(IMapController.class,(name,args)->switch(name) {
                case "getPlayerSkeletonCapacity"->1;
                case "getRoomControllerByCoordinates"->skeleton ? proxy(toniarts.openkeeper.game.controller.room.IRoomController.class,
                        (method,values)->{assertEquals("getRoom",method);return prison;}) : null;
                default->throw new AssertionError(name);
            });
            health = new HealthSystem(data,lookup,settings,creatures,info,players,map,null);
        }
        EntityId add(int owner,boolean hero,boolean instant) {
            EntityId id=data.createEntity(); CreatureComponent c=new CreatureComponent(); c.creatureId=(short)(hero?8:7); c.diesInstantly=instant;
            data.setComponents(id,c,new Owner((short)owner,(short)owner),new Health(100,100)); return id;
        }
        Keeper player(int id) { return keepers.get((short)id); }
        void hit(EntityId id,int damage,int owner) { EntityController.setDamage(data,id,damage,(short)owner); }
        void tick(float seconds) { health.processTick(seconds); }
        @Override public void close() { health.stop(); data.close(); }
    }
}
