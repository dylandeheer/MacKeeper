/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.IPlayerController;
import toniarts.openkeeper.game.controller.player.PlayerStatsControl;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.logic.SlapSystem;
import toniarts.openkeeper.game.save.*;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.proxy;

class SlapContinuationTest {
    static final EntityId CREATURE = new EntityId(0), OBJECT = new EntityId(1);

    @Test void restoringActiveSlapsDoesNotRepeatDamageMoodStatisticsOrEfficiency() throws Exception {
        try (var a = new World()) {
            assertEquals(7,a.data.getComponent(CREATURE,Damage.class).damage);
            assertEquals(110,a.data.getComponent(CREATURE,CreatureEfficiency.class).efficiencyPercentage);
            assertEquals(1,a.slaps((short)3));
            assertEquals(2,a.data.getComponent(CREATURE,CreatureMood.class).other);
            a.tick(1.25f);
            try (var b = a.copy()) {
                for (float time : List.of(.25f,.5f,2f,1f,.125f)) {b.reload();step(a,b,time);}
                assertNull(a.data.getComponent(CREATURE,Slapped.class));
                assertEquals(100,a.data.getComponent(CREATURE,CreatureEfficiency.class).efficiencyPercentage);
                assertEquals(7,a.data.getComponent(CREATURE,Damage.class).damage);
                assertEquals(1,a.slaps((short)3));
            }
        }
    }

    @Test void pendingReslapAndOwnerTransferApplyOnceWithoutStackingEfficiency() throws Exception {
        try (var a = new World()) {
            a.tick(1);a.data.setComponents(CREATURE,new Slapped(1),new Owner((short)4,(short)4));
            try (var b = a.copy()) {
                b.reload();step(a,b,.25f);
                assertEquals(14,a.data.getComponent(CREATURE,Damage.class).damage);
                assertEquals(4,a.data.getComponent(CREATURE,CreatureMood.class).other);
                assertEquals(110,a.data.getComponent(CREATURE,CreatureEfficiency.class).efficiencyPercentage);
                assertEquals(1,a.slaps((short)3));assertEquals(1,a.slaps((short)4));
                b.reload();step(a,b,.25f);assertEquals(1,a.slaps((short)4));
                for (var w : List.of(a,b)) w.data.setComponent(CREATURE,new Slapped(1));
                b.reload();step(a,b,.25f);assertEquals(1,a.slaps((short)4));
                b.reload();step(a,b,3.25f);assertNull(a.data.getComponent(CREATURE,Slapped.class));
                b.reload();step(a,b,.25f);assertEquals(100,a.data.getComponent(CREATURE,CreatureEfficiency.class).efficiencyPercentage);
            }
        }
    }

    @Test void pendingRemovalAdditionAndPostLoadChangesPreserveEffects() throws Exception {
        try (var a = new World()) {
            a.tick(.5f);a.data.removeComponent(CREATURE,Slapped.class);a.data.removeEntity(OBJECT);
            var added = a.creature((short)4,.5);
            try (var b = a.copy()) {
                b.reload();assertEquals(0,b.slaps((short)4));step(a,b,.25f);
                assertEquals(100,a.data.getComponent(CREATURE,CreatureEfficiency.class).efficiencyPercentage);
                assertEquals(110,a.data.getComponent(added,CreatureEfficiency.class).efficiencyPercentage);
                assertEquals(1,a.slaps((short)4));
                b.reload();
                for (var w : List.of(a,b)) {w.data.setComponent(CREATURE,new Slapped(.75));w.data.removeEntity(added);}
                step(a,b,.25f);assertEquals(2,a.slaps((short)3));
                assertEquals(110,a.data.getComponent(CREATURE,CreatureEfficiency.class).efficiencyPercentage);
                b.reload();step(a,b,.25f);
            }
        }
    }

    @Test void objectReslapDoesNotRepeatLethalDamageAndDurationRemainsCached() throws Exception {
        try (var a = new World()) {
            assertEquals(Integer.MAX_VALUE,a.data.getComponent(OBJECT,Damage.class).damage);
            a.tick(1);a.data.removeComponent(OBJECT,Damage.class);a.data.setComponent(OBJECT,new Slapped(1));
            a.settings.values().forEach(v -> v.setValue(500));
            try (var b = a.copy()) {
                b.reload();step(a,b,1);assertNull(a.data.getComponent(OBJECT,Damage.class));
                b.reload();step(a,b,3);assertNull(a.data.getComponent(OBJECT,Slapped.class));
                assertEquals(4,b.system.captureState().duration());
                b.reload();step(a,b,.125f);
                for (var w : List.of(a,b)) w.data.setComponent(OBJECT,new Slapped(5.125));
                step(a,b,.125f);assertEquals(Integer.MAX_VALUE,a.data.getComponent(OBJECT,Damage.class).damage);
            }
        }
    }

    @Test void separateQueryDirtySetsPreserveSharedEntityPhaseOrder() throws Exception {
        try (var a = new World()) {
            // Hybrid membership is unusual but legal ECS state. The two queries
            // share the deadline cache; do not synthesize changed events on load.
            a.data.setComponents(CREATURE,new ObjectComponent(),new Interaction(false,true,false,false,false));
            a.tick(.25f);a.data.setComponent(CREATURE,new Slapped(.25));
            try (var b = a.copy()) {
                assertTrue(b.system.captureState().dirtyCreatures().contains(CREATURE));
                assertTrue(b.system.captureState().dirtyObjects().contains(CREATURE));
                b.reload();step(a,b,.25f);assertEquals(2,a.slaps((short)3));
                b.reload();step(a,b,.25f);assertEquals(2,a.slaps((short)3));
                for (var w : List.of(a,b)) w.data.setComponent(CREATURE,new Interaction(false,true,false,false,false));
                b.reload();step(a,b,.25f);assertEquals(2,a.slaps((short)3));
            }
        }
    }

    @Test void malformedSectionsAndBindingsFailWithoutChangingGameplay() throws Exception {
        try (var w = new World()) {
            var state = w.system.captureState();var bytes = SlapSnapshots.encode(state);
            assertEquals(state,SlapSnapshots.decode(bytes));
            for (int i=0;i<bytes.length;i++) {var truncated=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->SlapSnapshots.decode(truncated));}
            var header=bytes.clone();header[0]=0;assertThrows(IOException.class,()->SlapSnapshots.decode(header));
            var version=bytes.clone();version[7]=99;assertThrows(IOException.class,()->SlapSnapshots.decode(version));
            assertThrows(IOException.class,()->SlapSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
            assertThrows(IllegalArgumentException.class,()->new SlapSnapshots.State(-1,4,state.players(),state.startTimes(),state.creatures(),state.objects(),List.of(),List.of()));
            assertThrows(IllegalArgumentException.class,()->new SlapSnapshots.State(0,4,List.of((short)3,(short)3),state.startTimes(),state.creatures(),state.objects(),List.of(),List.of()));
            assertThrows(IllegalArgumentException.class,()->new SlapSnapshots.State(0,4,state.players(),Map.of(CREATURE,Double.NaN),state.creatures(),state.objects(),List.of(),List.of()));
            assertThrows(IllegalArgumentException.class,()->new SlapSnapshots.State(0,4,state.players(),state.startTimes(),state.creatures(),state.objects(),List.of(OBJECT),List.of()));
            assertThrows(UnsupportedOperationException.class,()->state.startTimes().clear());
            var before=EntitySnapshots.encode(w.data.capture());var stats=w.statistics();
            var unknown=new SlapSnapshots.State(0,4,state.players(),Map.of(new EntityId(999),0d),List.of(new EntityId(999)),List.of(),List.of(),List.of());
            assertThrows(IllegalArgumentException.class,()->SlapSystem.restoreState(w.data,w.kwd,w.players(),unknown));
            assertThrows(IllegalArgumentException.class,()->SlapSystem.restoreState(w.data,w.kwd,List.of(),state));
            assertArrayEquals(before,EntitySnapshots.encode(w.data.capture()));assertEquals(stats,w.statistics());
        }
    }

    static void step(World a, World b, float time) throws Exception {
        a.tick(time);b.tick(time);assertEquals(a.system.captureState(),b.system.captureState());
        assertEquals(a.statistics(),b.statistics());assertArrayEquals(EntitySnapshots.encode(a.data.capture()),EntitySnapshots.encode(b.data.capture()));
    }
    static final class World implements AutoCloseable {
        final GameEntityData data;
        final Creature definition=new Creature();final IKwdFile kwd;
        final Map<Short,PlayerStatsControl> stats=new TreeMap<>();
        final Map<Variable.MiscVariable.MiscType,Variable.MiscVariable> settings=new EnumMap<>(Variable.MiscVariable.MiscType.class);
        SlapSystem system;
        World() {this(new GameEntityData());creature((short)3,0);var id=data.createEntity();data.setComponents(id,new Slapped(0),new ObjectComponent(),new Interaction(false,true,false,false,true));system=new SlapSystem(data,kwd,players(),settings);}
        World(GameEntityData data) {
            this.data=data;definition.setCreatureId((short)1);definition.getAttributes().setSlapDamage(7);definition.getAttributes().setAngerSlap((short)8);
            kwd=proxy(IKwdFile.class,(name,args)->{if(name.equals("getCreature"))return definition;throw new AssertionError(name);});
            stats.put((short)3,new PlayerStatsControl());stats.put((short)4,new PlayerStatsControl());
            var value=new Variable.MiscVariable();value.setVariableId(Variable.MiscVariable.MiscType.INCREASED_WORK_RATE_DURATION_FROM_SLAPPING_SECONDS);value.setValue(4);settings.put(value.getVariableId(),value);
        }
        List<IPlayerController> players() {return stats.entrySet().stream().map(e->{var keeper=new Keeper(false,e.getKey());return proxy(IPlayerController.class,(name,args)->{if(name.equals("getKeeper"))return keeper;if(name.equals("getStatsControl"))return e.getValue();throw new AssertionError(name);});}).toList();}
        Map<Short,PlayerStatsControl.State> statistics() {var result=new TreeMap<Short,PlayerStatsControl.State>();stats.forEach((id,s)->result.put(id,s.captureState()));return result;}
        int slaps(short owner) {return stats.get(owner).captureState().slaps().getOrDefault((short)1,0);}
        EntityId creature(short owner,double time) {var id=data.createEntity();var creature=new CreatureComponent();creature.creatureId=1;data.setComponents(id,creature,new Owner(owner,owner),new Slapped(time),new CreatureEfficiency(100),new CreatureMood(),new Health(100,100));return id;}
        void tick(float time) {system.processTick(time);}
        World copy() throws Exception {
            var result=new World(GameEntityData.restore(EntitySnapshots.decode(EntitySnapshots.encode(data.capture()))));
            statistics().forEach((id,state)->result.stats.put(id,PlayerStatsControl.restoreState(state,i->result.definition)));
            var before=EntitySnapshots.encode(result.data.capture());var previous=result.statistics();
            result.system=SlapSystem.restoreState(result.data,result.kwd,result.players(),SlapSnapshots.decode(SlapSnapshots.encode(system.captureState())));
            assertArrayEquals(before,EntitySnapshots.encode(result.data.capture()));assertEquals(previous,result.statistics());return result;
        }
        void reload() throws Exception {
            var state=SlapSnapshots.decode(SlapSnapshots.encode(system.captureState()));var before=EntitySnapshots.encode(data.capture());var previous=statistics();
            system.stop();system=SlapSystem.restoreState(data,kwd,players(),state);
            assertEquals(state,system.captureState());assertArrayEquals(before,EntitySnapshots.encode(data.capture()));assertEquals(previous,statistics());
        }
        public void close() {system.stop();data.close();}
    }
}
