/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.badlogic.gdx.ai.fsm.StateMachine;
import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.controller.entity.IEntityController;
import toniarts.openkeeper.game.controller.player.PlayerCreatureControl;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.logic.*;
import toniarts.openkeeper.game.map.IMapTileInformation;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.proxy;

class HealthContinuationTest {
    @Test void damageHealingAndRecoveryDeadlinesContinueAcrossRepeatedLoads() throws Exception {
        try (var a = new World()) {
            var id=a.add(3,7,false);a.data.setComponent(id,new CreatureRecuperating(0,0));a.tick(.5f);
            a.data.setComponent(id,new Damage(20,(short)4));
            try (var b=a.copy()) {
                b.reload();step(a,b,.5f);assertEquals(84,a.hp(id));
                assertEquals(4,a.data.getComponent(id,LastDamageSource.class).playerId);assertNull(a.data.getComponent(id,Damage.class));
                for (var w:List.of(a,b)) w.data.setComponent(id,new Damage(-10,(short)5));
                b.reload();step(a,b,.25f);assertEquals(94,a.hp(id));
                b.reload();step(a,b,.75f);assertEquals(98,a.hp(id));
                b.reload();step(a,b,2f);assertEquals(100,a.hp(id));
                assertEquals(3,a.data.getComponent(id,CreatureRecuperating.class).healthCheckTime);
            }
        }
    }

    @Test void identicalPendingHealthWriteStillResetsOwnLandRegeneration() throws Exception {
        try (var a=new World()) {
            var id=a.add(3,7,false);a.data.setComponents(id,new Health(50,100),new Regeneration(5,0d));a.tick(.5f);
            a.data.setComponent(id,a.data.getComponent(id,Health.class));
            try (var b=a.copy()) {
                assertEquals(List.of(id),b.health.captureState().dirtyHealth());
                b.reload();step(a,b,.5f);assertEquals(50,a.hp(id));assertEquals(1d,a.data.getComponent(id,Regeneration.class).timeOnOwnLand);
                b.reload();step(a,b,.5f);assertEquals(50,a.hp(id));
                b.reload();step(a,b,.5f);assertEquals(55,a.hp(id));
                b.reload();step(a,b,.25f);assertEquals(2.25,a.data.getComponent(id,Regeneration.class).timeOnOwnLand);
                for(var w:List.of(a,b))w.data.setComponent(id,new Position(0,new Vector3f(1,0,0)));
                b.reload();step(a,b,2);assertEquals(55,a.hp(id));assertNull(a.data.getComponent(id,Regeneration.class).timeOnOwnLand);
            }
        }
    }

    @Test void loadingExistingRecoveryDoesNotWakeAgainButPendingRecoveryDoes() throws Exception {
        try (var a=new World()) {
            EntityId existing=a.add(3,7,false),pending=a.add(3,7,false);
            a.data.setComponent(existing,new CreatureRecuperating(0,0));a.tick(.5f);
            a.data.setComponent(existing,new Unconscious(.5));a.data.setComponent(pending,new Unconscious(.5));
            a.data.setComponent(pending,new CreatureRecuperating(.5,.5));
            try(var b=a.copy()) {
                b.reload();assertNotNull(b.data.getComponent(pending,Unconscious.class));
                step(a,b,.5f);assertNotNull(a.data.getComponent(existing,Unconscious.class));assertNull(a.data.getComponent(pending,Unconscious.class));
                b.reload();step(a,b,1);assertNotNull(a.data.getComponent(existing,Unconscious.class));
                for(var w:List.of(a,b))w.data.removeComponent(existing,CreatureRecuperating.class);
                b.reload();step(a,b,.25f);
                b.reload();for(var w:List.of(a,b))w.data.setComponent(existing,new CreatureRecuperating(2.25,2.25));
                step(a,b,.25f);assertNull(a.data.getComponent(existing,Unconscious.class));
                assertEquals(0,a.keepers.get((short)3).getCreaturesLost());
            }
        }
    }

    @Test void knockoutDeathAndKillAttributionUseSavedClockAndCachedDelay() throws Exception {
        try(var a=new World()) {
            var id=a.add(3,7,false);a.data.setComponent(id,new Damage(100,(short)4));a.tick(.5f);
            assertNotNull(a.data.getComponent(id,Unconscious.class));assertEquals(0,a.keepers.get((short)3).getCreaturesLost());
            a.settings.values().forEach(v->v.setValue(999));
            try(var b=a.copy()) {
                b.reload();step(a,b,9.5f);assertNull(a.data.getComponent(id,Death.class));
                b.reload();step(a,b,.5f);assertEquals(10.5,a.data.getComponent(id,Death.class).startTime);
                assertEquals(1,a.keepers.get((short)3).getCreaturesLost());assertEquals(1,a.keepers.get((short)4).getCreatureKills((short)3));
                b.reload();step(a,b,30);assertEquals(1,a.keepers.get((short)3).getCreaturesLost());
                assertEquals(1,a.events.stream().filter(e->e.contains("UNCONSCIOUS")).count());
            }
        }
    }

    @Test void prisonAndTortureChecksKeepCachedDeltasAndPauseWithoutPosition() throws Exception {
        try(var a=new World()) {
            EntityId prison=a.add(3,7,false),torture=a.add(3,7,false);
            a.data.setComponents(prison,new Health(50,100),new CreatureImprisoned(0,0));
            a.data.setComponents(torture,new Health(50,100),new CreatureTortured(0,0,0));a.tick(.5f);
            a.settings.values().forEach(v->v.setValue(100));
            try(var b=a.copy()) {
                b.reload();step(a,b,.5f);assertEquals(48,a.hp(prison));assertEquals(47,a.hp(torture));
                b.reload();for(var w:List.of(a,b)){w.data.removeComponent(prison,Position.class);w.data.removeComponent(torture,Position.class);}
                step(a,b,2);assertEquals(48,a.hp(prison));assertEquals(47,a.hp(torture));
                b.reload();for(var w:List.of(a,b)){w.data.setComponent(prison,new Position(0,new Vector3f()));w.data.setComponent(torture,new Position(0,new Vector3f()));}
                step(a,b,.25f);assertEquals(46,a.hp(prison));assertEquals(44,a.hp(torture));
                b.reload();step(a,b,.25f);assertEquals(44,a.hp(prison));assertEquals(41,a.hp(torture));
            }
        }
    }

    @Test void pendingHealthMembershipAndLethalBranchesDoNotReplay() throws Exception {
        try(var a=new World()) {
            var removed=a.add(3,7,false);a.tick(.25f);a.data.removeEntity(removed);
            EntityId instant=a.add(3,8,true),vanish=a.add(3,9,false),skeleton=a.add(3,7,false),torture=a.add(3,7,false);
            a.data.setComponent(instant,new Damage(100,(short)4));a.data.setComponent(vanish,new Damage(100,(short)4));
            a.data.setComponents(skeleton,new Health(1,100),new Owner((short)3,(short)4),new CreatureImprisoned(0,0),new LastDamageSource((short)4));
            a.data.setComponents(torture,new Health(1,100),new CreatureTortured(0,0,0),new LastDamageSource((short)4));
            try(var b=a.copy()) {
                b.reload();step(a,b,.75f);
                assertNotNull(a.data.getComponent(instant,Death.class));assertNull(a.data.getComponent(vanish,Health.class));
                assertTrue(a.events.contains("transform:"+skeleton.getId()+":4:8"));assertNotNull(a.data.getComponent(torture,Death.class));
                assertEquals(4,a.keepers.get((short)3).getCreaturesLost());assertEquals(1,a.keepers.get((short)4).getHeroesKilled());
                b.reload();step(a,b,1);assertEquals(4,a.keepers.get((short)3).getCreaturesLost());
                assertEquals(1,a.events.stream().filter(e->e.startsWith("transform:")).count());
            }
        }
    }

    @Test void healthSectionSupportsLargeWorldsAndRejectsMalformedOrUnallocatedObservations() throws Exception {
        try(var w=new World()) {
            var id=w.add(3,7,false);w.tick(.25f);var state=w.health.captureState();var bytes=HealthSnapshots.encode(state);
            assertEquals(state,HealthSnapshots.decode(bytes));
            for(int i=0;i<bytes.length;i++){var truncated=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->HealthSnapshots.decode(truncated));}
            var header=bytes.clone();header[0]=0;assertThrows(IOException.class,()->HealthSnapshots.decode(header));
            var version=bytes.clone();version[7]=99;assertThrows(IOException.class,()->HealthSnapshots.decode(version));
            assertThrows(IOException.class,()->HealthSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
            assertThrows(IllegalArgumentException.class,()->new HealthSnapshots.State(Double.NaN,state.settings(),state.players(),List.of(id),List.of(),List.of()));
            assertThrows(IllegalArgumentException.class,()->new HealthSnapshots.State(0,state.settings(),state.players(),List.of(id,id),List.of(),List.of()));
            assertThrows(IllegalArgumentException.class,()->new HealthSnapshots.State(0,state.settings(),state.players(),List.of(),List.of(id),List.of()));
            assertThrows(UnsupportedOperationException.class,()->state.observedHealth().clear());
            var large=java.util.stream.LongStream.range(0,65537).mapToObj(EntityId::new).toList();
            var largeState=new HealthSnapshots.State(0,state.settings(),state.players(),large,large,large);
            assertEquals(largeState,HealthSnapshots.decode(HealthSnapshots.encode(largeState)));
            var before=EntitySnapshots.encode(w.data.capture());var keepers=w.keeperStates();var events=List.copyOf(w.events);
            for(boolean health:List.of(true,false)) {
                var bad=new HealthSnapshots.State(0,state.settings(),state.players(),health?List.of(new EntityId(999)):List.of(),List.of(),health?List.of():List.of(new EntityId(999)));
                assertThrows(IllegalArgumentException.class,()->w.restore(bad));
            }
            assertThrows(IllegalArgumentException.class,()->HealthSystem.restoreState(w.data,w.lookup,w.creatures,w.level,List.of(),w.map,w.world,state));
            assertArrayEquals(before,EntitySnapshots.encode(w.data.capture()));assertEquals(keepers,w.keeperStates());assertEquals(events,w.events);
        }
    }

    static void step(World a,World b,float time)throws Exception {
        a.tick(time);b.tick(time);assertEquals(a.health.captureState(),b.health.captureState());
        assertEquals(a.keeperStates(),b.keeperStates());assertEquals(a.events,b.events);
        assertArrayEquals(EntitySnapshots.encode(a.data.capture()),EntitySnapshots.encode(b.data.capture()));
    }
    static final class World implements AutoCloseable {
        final GameEntityData data;final List<String> events=new ArrayList<>();final Map<Short,Keeper> keepers=new TreeMap<>();
        final Map<Short,Creature> species=new TreeMap<>();final Map<Variable.MiscVariable.MiscType,Variable.MiscVariable> settings=new EnumMap<>(Variable.MiscVariable.MiscType.class);
        final IEntityPositionLookup lookup;final ICreaturesController creatures;final IMapController map;final ILevelInfo level;final IGameWorldController world;
        HealthSystem health;
        World(){this(new GameEntityData());health=new HealthSystem(data,lookup,settings,creatures,level,players(),map,world);}
        World(GameEntityData data){this.data=data;
            for(short id:new short[]{7,8,9}){var c=new Creature();c.setCreatureId(id);c.setFlags(id==9?EnumSet.noneOf(Creature.CreatureFlag.class):id==8?EnumSet.of(Creature.CreatureFlag.GENERATE_DEAD_BODY,Creature.CreatureFlag.IS_GOOD):EnumSet.of(Creature.CreatureFlag.GENERATE_DEAD_BODY));c.getAttributes().setTortureHpChange((short)-3);species.put(id,c);}
            keepers.put((short)3,new Keeper(false,(short)3));keepers.put((short)4,new Keeper(false,(short)4));
            for(var type:List.of(Variable.MiscVariable.MiscType.CREATURE_DYING_STATE_DURATION_SECONDS,Variable.MiscVariable.MiscType.MODIFY_HEALTH_OF_CREATURE_IN_LAIR_PER_SECOND,Variable.MiscVariable.MiscType.PRISON_MODIFY_CREATURE_HEALTH_PER_SECOND)){
                var v=new Variable.MiscVariable();v.setValue(type==Variable.MiscVariable.MiscType.CREATURE_DYING_STATE_DURATION_SECONDS?10:type==Variable.MiscVariable.MiscType.MODIFY_HEALTH_OF_CREATURE_IN_LAIR_PER_SECOND?4:-2);settings.put(type,v);
            }
            var kwd=proxy(IKwdFile.class,(name,args)->{if(name.equals("getCreature"))return species.get(args[0]);throw new AssertionError(name);});
            level=proxy(ILevelInfo.class,(name,args)->{if(name.equals("getLevelData"))return kwd;throw new AssertionError(name);});
            lookup=proxy(IEntityPositionLookup.class,(name,args)->{
                var id=(EntityId)args[0];
                if(name.equals("getEntityController"))return proxy(IEntityController.class,(method,a)->{if(method.equals("remove")){events.add("remove:"+id.getId());data.removeEntity(id);return null;}throw new AssertionError(method);});
                if(name.equals("getEntityLocation"))return proxy(IMapTileInformation.class,(method,a)->{
                    if(method.equals("getOwnerId"))return (short)(data.getComponent(id,Position.class).position.x==0?3:4);
                    if(method.equals("getLocation"))return new Point(0,0);throw new AssertionError(method);});
                throw new AssertionError(name);
            });
            creatures=proxy(ICreaturesController.class,(name,args)->{
                var id=(EntityId)args[0];
                if(name.equals("turnCreatureIntoAnother")){events.add("transform:"+id.getId()+":"+args[1]+":"+args[2]);data.removeEntity(id);return null;}
                if(name.equals("createController"))return proxy(ICreatureController.class,(method,a)->{
                    if(method.equals("getStateMachine"))return proxy(StateMachine.class,(operation,parameters)->{if(operation.equals("changeState")){events.add("state:"+id.getId()+":"+parameters[0]);return null;}throw new AssertionError(operation);});throw new AssertionError(method);});
                throw new AssertionError(name);
            });
            var prison=new Room();prison.setCreatedCreatureId((short)8);
            map=proxy(IMapController.class,(name,args)->{if(name.equals("getPlayerSkeletonCapacity"))return 1;if(name.equals("getRoomControllerByCoordinates"))return proxy(IRoomController.class,(method,a)->{if(method.equals("getRoom"))return prison;throw new AssertionError(method);});throw new AssertionError(name);});
            world=proxy(IGameWorldController.class,(name,args)->{throw new AssertionError(name);});
        }
        List<IPlayerController> players(){return keepers.values().stream().map(k->{var roster=new PlayerCreatureControl(k,species.get((short)7),species.values());return proxy(IPlayerController.class,(name,args)->{if(name.equals("getKeeper"))return k;if(name.equals("getCreatureControl"))return roster;throw new AssertionError(name);});}).toList();}
        Map<Short,KeeperState> keeperStates(){var result=new TreeMap<Short,KeeperState>();keepers.forEach((id,k)->result.put(id,k.captureState()));return result;}
        EntityId add(int owner,int speciesId,boolean instant){var id=data.createEntity();var c=new CreatureComponent();c.creatureId=(short)speciesId;c.diesInstantly=instant;data.setComponents(id,c,new Health(100,100),new Owner((short)owner,(short)owner),new Position(0,new Vector3f()));return id;}
        int hp(EntityId id){return data.getComponent(id,Health.class).health;}
        void tick(float time){health.processTick(time);}
        HealthSystem restore(HealthSnapshots.State state){return HealthSystem.restoreState(data,lookup,creatures,level,players(),map,world,state);}
        World copy()throws Exception {var result=new World(GameEntityData.restore(EntitySnapshots.decode(EntitySnapshots.encode(data.capture()))));
            for(var entry:keeperStates().entrySet())result.keepers.put(entry.getKey(),Keeper.restoreState(KeeperSnapshots.decode(KeeperSnapshots.encode(entry.getValue())),null));
            result.events.addAll(events);var before=EntitySnapshots.encode(result.data.capture());result.health=result.restore(HealthSnapshots.decode(HealthSnapshots.encode(health.captureState())));
            assertArrayEquals(before,EntitySnapshots.encode(result.data.capture()));assertEquals(events,result.events);assertEquals(keeperStates(),result.keeperStates());return result;
        }
        void reload()throws Exception {var state=HealthSnapshots.decode(HealthSnapshots.encode(health.captureState()));var before=EntitySnapshots.encode(data.capture());var old=keeperStates();var previous=List.copyOf(events);health.stop();health=restore(state);assertEquals(state,health.captureState());assertArrayEquals(before,EntitySnapshots.encode(data.capture()));assertEquals(old,keeperStates());assertEquals(previous,events);}
        public void close(){health.stop();data.close();}
    }
}
