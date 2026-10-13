/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.controller.entity.EntityController;
import toniarts.openkeeper.game.logic.*;
import toniarts.openkeeper.game.map.*;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.proxy;

class LifetimeContinuationTest {
    @Test void decayDeadlinesProduceTheSameDamageAndRemovalAfterRepeatedLoads() throws Exception {
        try(var a=new World()) {
            a.tick(1.5f);
            try(var b=a.copy()) {
                for(float time:List.of(.25f,.25f,.125f,.875f,2f)) {b.reload();step(a,b,time);}
                assertEquals(200,a.data.getComponent(new EntityId(0),Damage.class).damage);
                assertNull(a.data.getComponent(new EntityId(0),Decay.class));
                assertNull(a.data.getComponent(new EntityId(1),Position.class));
            }
        }
    }

    @Test void deathBindingDoesNotDropLootAgainAndPendingDeathsCleanPossessionsExactlyOnce() throws Exception {
        try(var a=new World()) {
            assertEquals(List.of("clean:2","gold:10"),a.events);
            a.tick(.75f);a.data.setComponent(new EntityId(2),new Gold(99,100));
            var pending=a.corpse(20);var spell=a.data.createEntity();a.data.setComponent(spell,new Owner((short)3,(short)3));
            var spells=new CreatureSpells();spells.creatureSpells=List.of(spell);a.data.setComponent(pending,spells);
            try(var b=a.copy()) {
                b.reload();assertEquals(99,b.data.getComponent(new EntityId(2),Gold.class).gold);
                assertNotNull(b.data.getComponent(pending,Gold.class));assertEquals(a.events,b.events);
                step(a,b,.25f);assertEquals(1,a.events.stream().filter(e->e.equals("clean:"+pending.getId())).count());
                assertNull(a.data.getComponent(pending,Gold.class));assertNull(a.data.getComponent(spell,Owner.class));
                assertEquals(99,a.data.getComponent(new EntityId(2),Gold.class).gold);
                b.reload();step(a,b,.25f);assertEquals(1,a.events.stream().filter(e->e.equals("gold:20")).count());
            }
        }
    }

    @Test void tortureCheckClockAndConversionStayOnTheSameFutureTick() throws Exception {
        try(var a=new World()) {
            a.data.setComponent(new EntityId(3),new CreatureTortured(1,2.5,17));a.tick(1);
            try(var b=a.copy()) {
                b.reload();step(a,b,1.5f);assertFalse(a.events.stream().anyMatch(e->e.startsWith("convert:")));
                assertEquals(1,a.data.getComponent(new EntityId(3),CreatureTortured.class).timeTortured);
                b.reload();step(a,b,1);assertEquals(2,a.data.getComponent(new EntityId(3),CreatureTortured.class).timeTortured);
                b.reload();step(a,b,1);assertTrue(a.events.contains("convert:3:4"));
                assertNull(a.data.getComponent(new EntityId(3),CreatureTortured.class));
                assertNotNull(a.data.getComponent(new EntityId(4),CreatureTortured.class)); // Already owned: continue accumulating without conversion.
            }
        }
    }

    @Test void pendingRemovalsAndPostLoadChangesUseCurrentDecayAndTortureMembership() throws Exception {
        try(var a=new World()) {
            a.tick(.5f);a.data.removeComponent(new EntityId(1),Position.class);a.data.removeEntity(new EntityId(2));
            a.data.removeComponent(new EntityId(3),Position.class);
            try(var b=a.copy()) {
                b.reload();step(a,b,3);assertNotNull(a.data.getComponent(new EntityId(1),Decay.class));
                assertFalse(a.events.stream().anyMatch(e->e.startsWith("convert:")));
                b.reload();
                for(var w:List.of(a,b)) {
                    w.data.setComponent(new EntityId(1),new Position(0,new Vector3f()));
                    w.data.setComponent(new EntityId(3),new Position(0,new Vector3f(5,0,0)));
                }
                step(a,b,3);assertNull(a.data.getComponent(new EntityId(1),Decay.class));assertTrue(a.events.contains("convert:3:5"));
            }
        }
    }

    @Test void deathDelayAndOrderedPendingCleanupRemainStableAfterAuthoredSettingsChange() throws Exception {
        try(var a=new World()) {
            a.tick(1.5f);a.settings.values().forEach(v->v.setValue(500));
            var first=a.corpse(21);var second=a.corpse(22);
            try(var b=a.copy()) {
                b.reload();step(a,b,.5f);
                int at=a.events.indexOf("clean:"+first.getId());assertEquals(List.of("clean:"+first.getId(),"gold:21","clean:"+second.getId(),"gold:22"),a.events.subList(at,at+4));
                b.reload();step(a,b,3);assertNull(a.data.getComponent(new EntityId(2),Death.class));
                assertEquals(5,a.death.captureState().decayDelay());
            }
        }
    }

    @Test void malformedLifetimeSectionsAndUnallocatedDeathsFailBeforeCleanup() throws Exception {
        try(var w=new World()) {
            var saved=w.state();var bytes=LifetimeSnapshots.encode(saved);assertEquals(saved,LifetimeSnapshots.decode(bytes));
            for(int i=0;i<bytes.length;i++){var truncated=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->LifetimeSnapshots.decode(truncated));}
            var header=bytes.clone();header[0]=0;assertThrows(IOException.class,()->LifetimeSnapshots.decode(header));
            assertThrows(IOException.class,()->LifetimeSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
            assertThrows(IllegalArgumentException.class,()->new LifetimeSnapshots.Clock(Double.POSITIVE_INFINITY));
            assertThrows(IllegalArgumentException.class,()->new LifetimeSnapshots.Clock(-.1));
            assertThrows(IllegalArgumentException.class,()->new LifetimeSnapshots.DeathState(saved.death().clock(),5,List.of(new EntityId(2),new EntityId(1))));
            assertThrows(IllegalArgumentException.class,()->new LifetimeSnapshots.DeathState(saved.death().clock(),5,List.of(new EntityId(2),new EntityId(2))));
            assertThrows(UnsupportedOperationException.class,()->saved.death().observed().clear());
            var events=List.copyOf(w.events);var data=EntitySnapshots.encode(w.data.capture());
            assertThrows(IllegalArgumentException.class,()->DeathSystem.restoreState(w.data,w.lookup,new LifetimeSnapshots.DeathState(saved.death().clock(),5,List.of(new EntityId(999)))));
            assertEquals(events,w.events);assertArrayEquals(data,EntitySnapshots.encode(w.data.capture()));
        }
    }

    static void step(World a,World b,float time)throws Exception {a.tick(time);b.tick(time);assertEquals(a.state(),b.state());assertEquals(a.events,b.events);assertArrayEquals(EntitySnapshots.encode(a.data.capture()),EntitySnapshots.encode(b.data.capture()));}
    static final class World implements AutoCloseable {
        final GameEntityData data;final List<String> events=new ArrayList<>();final Map<Variable.MiscVariable.MiscType,Variable.MiscVariable> settings=new EnumMap<>(Variable.MiscVariable.MiscType.class);
        final IEntityPositionLookup lookup;final ICreaturesController creatures;final IMapController map;final IObjectsController objects;
        DecaySystem decay;DeathSystem death;CreatureTorturingSystem torture;
        World(){this(new GameEntityData());
            var healthy=data.createEntity();data.setComponents(healthy,new Decay(0,2),new Position(0,new Vector3f()),new Health(100,100));
            var fragile=data.createEntity();data.setComponents(fragile,new Decay(0,2),new Position(0,new Vector3f()));
            corpse(10);tortured((short)3);tortured((short)4);
            decay=new DecaySystem(data);torture=new CreatureTorturingSystem(data,creatures,map);death=new DeathSystem(data,settings,lookup);
        }
        World(GameEntityData data){this.data=data;
            var delay=new Variable.MiscVariable();delay.setVariableId(Variable.MiscVariable.MiscType.DEAD_BODY_DIES_AFTER_SECONDS);delay.setValue(5);settings.put(delay.getVariableId(),delay);
            IMapDataInformation<IMapTileInformation> grid=proxy(IMapDataInformation.class,(name,args)->{
                if(name.equals("getTile")){var point=(Point)args[0];return proxy(IMapTileInformation.class,(method,a)->{if(method.equals("getOwnerId"))return (short)(point.x==5?5:4);throw new AssertionError(method);});}
                throw new AssertionError(name);
            });
            map=proxy(IMapController.class,(name,args)->{if(name.equals("getMapData"))return grid;throw new AssertionError(name);});
            objects=proxy(IObjectsController.class,(name,args)->{
                if(name.equals("addLooseGold")){events.add("gold:"+args[3]);var id=data.createEntity();data.setComponents(id,new Gold((int)args[3],(int)args[4]),new Owner((short)args[0],(short)args[0]),new Position(0,new Vector3f((int)args[1],0,(int)args[2])));return id;}
                throw new AssertionError(name);
            });
            lookup=proxy(IEntityPositionLookup.class,(name,args)->{
                if(name.equals("getEntityController")){var id=(EntityId)args[0];return new EntityController(id,data,objects,map){@Override public void removePossession(){events.add("clean:"+id.getId());super.removePossession();}};}
                throw new AssertionError(name);
            });
            creatures=proxy(ICreaturesController.class,(name,args)->{
                if(name.equals("createController")){var id=(EntityId)args[0];return proxy(ICreatureController.class,(method,a)->{
                    if(method.equals("convertCreature")){short owner=(short)a[0];events.add("convert:"+id.getId()+":"+owner);data.setComponent(id,new Owner(owner,owner));return null;}throw new AssertionError(method);});}
                throw new AssertionError(name);
            });
        }
        EntityId corpse(int gold){var id=data.createEntity();data.setComponents(id,new Death(0),new Gold(gold,100),new Position(0,new Vector3f()),new Owner((short)3,(short)3));return id;}
        EntityId tortured(short owner){var id=data.createEntity();var creature=new CreatureComponent();creature.tortureTimeToConvert=3;data.setComponents(id,creature,new CreatureTortured(0,0,0),new Owner(owner,owner),new Position(0,new Vector3f()));return id;}
        LifetimeSnapshots.State state(){return new LifetimeSnapshots.State(decay.captureTime(),torture.captureTime(),death.captureState());}
        void bind(LifetimeSnapshots.State saved){decay=DecaySystem.restoreState(data,saved.decay());torture=CreatureTorturingSystem.restoreState(data,creatures,map,saved.torture());death=DeathSystem.restoreState(data,lookup,saved.death());}
        World copy()throws Exception {var result=new World(GameEntityData.restore(EntitySnapshots.decode(EntitySnapshots.encode(data.capture()))));result.events.addAll(events);var before=EntitySnapshots.encode(result.data.capture());result.bind(LifetimeSnapshots.decode(LifetimeSnapshots.encode(state())));assertEquals(events,result.events);assertArrayEquals(before,EntitySnapshots.encode(result.data.capture()));return result;}
        void reload()throws Exception {var saved=LifetimeSnapshots.decode(LifetimeSnapshots.encode(state()));var before=EntitySnapshots.encode(data.capture());var previous=List.copyOf(events);decay.stop();death.stop();torture.stop();bind(saved);assertEquals(previous,events);assertArrayEquals(before,EntitySnapshots.encode(data.capture()));}
        void tick(float time){decay.processTick(time);torture.processTick(time);death.processTick(time);}
        public void close(){decay.stop();death.stop();torture.stop();data.close();}
    }
}
