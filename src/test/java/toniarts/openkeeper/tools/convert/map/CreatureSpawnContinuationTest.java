/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector2f;
import com.simsilica.es.EntityId;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.common.RoomInstance;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.player.*;
import toniarts.openkeeper.game.controller.room.*;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.listener.RoomListener;
import toniarts.openkeeper.game.logic.CreatureSpawnSystem;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.proxy;

class CreatureSpawnContinuationTest {
    @Test void portalAndFreeImpCooldownsContinueThroughRepeatedLoadsWithoutEarlySpawns() throws Exception {
        try(var a=new World();var b=new World()) {
            step(a,b,1.75f);reload(b);assertTrue(a.spawned.isEmpty());
            step(a,b,.25f);assertEquals(1,a.spawned.size());assertEquals((short)7,a.spawned.getFirst().creature());
            reload(b);step(a,b,1);assertEquals(3,a.spawned.size());
            assertEquals(List.of(0,5),a.spawned.subList(1,3).stream().map(Spawn::x).toList());
            for(float time:List.of(.25f,.75f,2f,3f,8f,.125f)) {reload(b);step(a,b,time);}
            assertTrue(a.spawned.stream().anyMatch(s->s.type()==ICreaturesController.SpawnType.ENTRANCE));
            assertTrue(a.spawned.stream().anyMatch(s->s.type()==ICreaturesController.SpawnType.PLACE));
        }
    }

    @Test void savedEntranceOrderAndCachedSettingsSurviveAuthoredChanges() throws Exception {
        try(var a=new World();var b=new World()) {
            step(a,b,1);
            // Later authored settings mutations must not replace the values cached at construction.
            for(var world:List.of(a,b)) world.settings.values().forEach(v->v.setValue(999));
            var saved=b.system.captureState();assertEquals(3,saved.settings().entranceCooldown());reload(b);
            step(a,b,2);assertEquals(List.of(0,5,10),a.spawned.stream().map(Spawn::x).toList());
            assertEquals(saved.settings(),b.system.captureState().settings());
        }
    }

    @Test void postLoadRoomEventsUseTheSameSharedControllersAndUnregisterOnStop() throws Exception {
        try(var a=new World();var b=new World()) {
            step(a,b,.5f);reload(b);
            for(var world:List.of(a,b)) {
                var portal=world.addPortal(15,false);world.listeners.get((short)3).forEach(l->l.onBuild(portal));
                var first=world.rooms.get(new EntityId(0));world.listeners.get((short)3).forEach(l->l.onSold(first));
                world.roomControls.get((short)3).onSold(first);world.live.remove(first.getEntityId());
                var captured=world.rooms.get(new EntityId(1));captured.captured((short)4);
                world.listeners.get((short)3).forEach(l->l.onCapturedByEnemy(captured));
                world.listeners.get((short)4).forEach(l->l.onCaptured(captured));
            }
            reload(b);step(a,b,2.5f);
            assertEquals(List.of(5,10,15),a.spawned.stream().map(Spawn::x).toList());
            assertEquals((short)4,a.spawned.getFirst().owner());
            assertEquals(2,b.listenerCount());var remembered=b.system.captureState();
            var late=b.listeners.get((short)3).getFirst();b.system.stop();b.system.stop();assertEquals(0,b.listenerCount());
            late.onBuild(b.rooms.get(new EntityId(0)));assertEquals(remembered,b.system.captureState());
            assertThrows(IllegalStateException.class,b.system::start);assertThrows(IllegalStateException.class,()->b.system.processTick(1));
        }
    }

    @Test void removedEntranceIsSharedAndSkippedWithoutReadingDeletedRoomComponents() throws Exception {
        try(var a=new World();var b=new World()) {
            step(a,b,1);
            for(var world:List.of(a,b)) {
                var removed=world.rooms.get(new EntityId(0));removed.destroy();world.data.removeEntity(removed.getEntityId());world.live.remove(removed.getEntityId());
            }
            var saved=b.system.captureState();assertEquals(1,saved.removedRooms().size());
            var replacement=RoomControllerFactory.restoreRemovedRoom(b.data,b.definition,saved.removedRooms().getFirst(),null,Map.of(),()->0);
            b.rooms.put(replacement.getEntityId(),(ThreeByThreeController)replacement);
            reload(b);step(a,b,2);assertEquals(List.of(5,10),a.spawned.stream().map(Spawn::x).toList());
            assertSame(replacement,b.rooms.get(saved.entrances().getFirst()));
            reload(b);step(a,b,3);
        }
    }

    @Test void portalsClosedAndPopulationCapStillApplyAfterLoad() throws Exception {
        try(var a=new World();var b=new World()) {
            for(var world:List.of(a,b))world.roomControls.get((short)3).setPortalsOpen(false);
            step(a,b,2);reload(b);step(a,b,2);assertTrue(a.spawned.stream().allMatch(s->s.creature()==7));
            for(var world:List.of(a,b)) {
                world.roomControls.get((short)3).setPortalsOpen(true);
                for(int i=0;i<10;i++){var id=world.data.createEntity();world.creatureControls.get((short)3).onCreatureAdded(id,world.recruit);}
            }
            reload(b);step(a,b,100);assertTrue(a.spawned.stream().allMatch(s->s.creature()==7));
        }
    }

    @Test void stagedBindingHasNoListenersAndActivationFailureRollsBackAllRegistrations() throws Exception {
        try(var w=new World()) {
            var saved=w.system.captureState();w.system.stop();assertEquals(0,w.listenerCount());
            var staged=w.restore(saved);assertEquals(0,w.listenerCount());assertTrue(w.spawned.isEmpty());
            assertThrows(IllegalStateException.class,()->staged.processTick(1));
            w.failSecondListener=true;assertThrows(IllegalStateException.class,staged::start);assertEquals(0,w.listenerCount());
            assertThrows(IllegalStateException.class,staged::start);staged.stop();
            w.failSecondListener=false;w.system=w.restore(saved);w.system.start();w.system.start();assertEquals(2,w.listenerCount());
        }
    }

    @Test void codecAndBindingRejectInvalidReferencesWithoutSpawnsOrRegistration() throws Exception {
        try(var w=new World()) {
            var saved=w.system.captureState();var bytes=CreatureSpawnSnapshots.encode(saved);
            assertEquals(saved,CreatureSpawnSnapshots.decode(bytes));
            for(int i=0;i<bytes.length;i++){var truncated=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->CreatureSpawnSnapshots.decode(truncated));}
            var badHeader=bytes.clone();badHeader[0]=0;assertThrows(IOException.class,()->CreatureSpawnSnapshots.decode(badHeader));
            assertThrows(IOException.class,()->CreatureSpawnSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
            assertThrows(IllegalArgumentException.class,()->new CreatureSpawnSnapshots.State(Double.NaN,saved.settings(),saved.players(),saved.entrances(),List.of()));
            assertThrows(IllegalArgumentException.class,()->new CreatureSpawnSnapshots.State(0,saved.settings(),List.of((short)3,(short)3),List.of(),List.of()));
            assertThrows(UnsupportedOperationException.class,()->saved.entrances().clear());
            for(var invalid:List.of(new CreatureSpawnSnapshots.State(0,saved.settings(),List.of((short)3),saved.entrances(),List.of()),
                    new CreatureSpawnSnapshots.State(0,saved.settings(),saved.players(),List.of(new EntityId(999)),List.of()))) {
                assertThrows(IllegalArgumentException.class,()->w.restore(invalid));assertEquals(2,w.listenerCount());assertTrue(w.spawned.isEmpty());
            }
            var first=w.rooms.get(new EntityId(0));w.live.remove(first.getEntityId());
            assertThrows(IllegalArgumentException.class,()->w.restore(saved));assertEquals(2,w.listenerCount());
        }
    }

    record Spawn(EntityId id,short creature,short owner,int x,ICreaturesController.SpawnType type) { }
    static void reload(World w)throws Exception {
        var saved=CreatureSpawnSnapshots.decode(CreatureSpawnSnapshots.encode(w.system.captureState()));
        var before=EntitySnapshots.encode(w.data.capture());var events=List.copyOf(w.spawned);w.system.stop();
        w.system=w.restore(saved);assertEquals(0,w.listenerCount());assertEquals(saved,w.system.captureState());
        assertArrayEquals(before,EntitySnapshots.encode(w.data.capture()));assertEquals(events,w.spawned);
        w.system.start();assertEquals(2,w.listenerCount());
    }
    static void step(World a,World b,float time)throws Exception {
        a.system.processTick(time);b.system.processTick(time);
        assertEquals(a.system.captureState(),b.system.captureState());assertEquals(a.spawned,b.spawned);
        assertArrayEquals(EntitySnapshots.encode(a.data.capture()),EntitySnapshots.encode(b.data.capture()));
    }
    static final class World implements AutoCloseable {
        final GameEntityData data=new GameEntityData();final Creature imp=new Creature(),recruit=new Creature();
        final Room portal=new Room();final Map<EntityId,ThreeByThreeController> rooms=new LinkedHashMap<>();
        final Map<EntityId,IRoomController> live=new HashMap<>();final Map<Short,List<RoomListener>> listeners=new TreeMap<>();
        final Map<Short,PlayerRoomControl> roomControls=new TreeMap<>();final Map<Short,PlayerCreatureControl> creatureControls=new TreeMap<>();
        final List<IPlayerController> players=new ArrayList<>();final List<Spawn> spawned=new ArrayList<>();
        final Map<Variable.MiscVariable.MiscType,Variable.MiscVariable> settings=new EnumMap<>(Variable.MiscVariable.MiscType.class);
        final IKwdFile definition;final IMapController map;final ICreaturesController creatures;
        CreatureSpawnSystem system;boolean failSecondListener;
        World() {
            imp.setCreatureId((short)7);imp.setOrderInEditor((short)7);imp.setFlags(EnumSet.of(Creature.CreatureFlag.IS_WORKER));imp.setAttractions(new Creature.Attraction[0]);
            recruit.setCreatureId((short)8);recruit.setOrderInEditor((short)8);recruit.setFlags(EnumSet.noneOf(Creature.CreatureFlag.class));recruit.setAttractions(new Creature.Attraction[0]);
            portal.setRoomId((short)3);portal.setOrderInEditor((short)3);portal.setFlags(EnumSet.noneOf(Room.RoomFlag.class));portal.setTileConstruction(Room.TileConstruction._3_BY_3);
            var pool=new Variable.CreaturePool();pool.setCreatureId(8);pool.setValue(100);
            definition=proxy(IKwdFile.class,(name,args)->switch(name){case "getImp"->imp;case "getPortal","getRoomById"->portal;case "getCreaturePool"->Map.of(8,pool);default->throw new AssertionError(name);});
            for(short owner:new short[]{3,4}) {
                var keeper=new Keeper(false,owner);keeper.getAvailableCreatures().add((short)8);
                var rc=new PlayerRoomControl(keeper,List.of(portal));roomControls.put(owner,rc);
                var cc=new PlayerCreatureControl(keeper,imp,List.of(imp,recruit));creatureControls.put(owner,cc);
                players.add(proxy(IPlayerController.class,(name,args)->switch(name){case "getKeeper"->keeper;case "getRoomControl"->rc;case "getCreatureControl"->cc;default->throw new AssertionError(name);}));
                listeners.put(owner,new ArrayList<>());
            }
            addPortal(0,false);addPortal(5,false);addPortal(10,true);
            map=proxy(IMapController.class,(name,args)->switch(name){
                case "getRoomController"->live.get(args[0]);
                case "addListener"->{listeners.get((short)args[0]).add((RoomListener)args[1]);if(failSecondListener && (short)args[0]==4)throw new IllegalStateException("registration failed");yield null;}
                case "removeListener"->{listeners.get((short)args[0]).remove(args[1]);yield null;}
                default->throw new AssertionError(name);
            });
            creatures=proxy(ICreaturesController.class,(name,args)->switch(name){
                case "spawnCreature"->{var id=data.createEntity();short type=(short)args[0],owner=(short)args[1];var p=(Vector2f)args[3];
                    spawned.add(new Spawn(id,type,owner,(int)p.x,(ICreaturesController.SpawnType)args[4]));
                    data.setComponent(id,new Owner(owner,owner));yield id;}
                default->throw new AssertionError(name);
            });
            set(Variable.MiscVariable.MiscType.MINIMUM_IMP_THRESHOLD,2);set(Variable.MiscVariable.MiscType.ENTRANCE_GENERATION_SPEED_SECONDS,3);
            set(Variable.MiscVariable.MiscType.CREATURES_SUPPORTED_BY_FIRST_PORTAL,4);set(Variable.MiscVariable.MiscType.CREATURES_SUPPORTED_PER_ADDITIONAL_PORTAL,2);
            set(Variable.MiscVariable.MiscType.TIME_BEFORE_FREE_IMP_GENERATED_SECONDS,2);
            ILevelInfo level=proxy(ILevelInfo.class,(name,args)->{if(name.equals("getLevelData"))return definition;throw new AssertionError(name);});
            system=new CreatureSpawnSystem(creatures,players,settings,level,map);
        }
        void set(Variable.MiscVariable.MiscType type,int value){var v=new Variable.MiscVariable();v.setVariableId(type);v.setValue(value);settings.put(type,v);}
        ThreeByThreeController addPortal(int x,boolean heart) {
            var instance=new RoomInstance(portal);instance.setOwnerId((short)3);instance.addCoordinate(new Point(x,0));
            var id=data.createEntity();var room=new ThreeByThreeController(id,data,definition,instance,null);
            data.setComponent(id,new CreatureGenerator(instance.getCenter(),0));if(heart)data.setComponent(id,new DungeonHeart());
            rooms.put(id,room);live.put(id,room);roomControls.get((short)3).onBuild(room);return room;
        }
        CreatureSpawnSystem restore(CreatureSpawnSnapshots.State saved){return CreatureSpawnSystem.restoreState(creatures,players,definition,map,data,rooms::get,saved);}
        int listenerCount(){return listeners.values().stream().mapToInt(List::size).sum();}
        public void close(){system.stop();data.close();}
    }
}
