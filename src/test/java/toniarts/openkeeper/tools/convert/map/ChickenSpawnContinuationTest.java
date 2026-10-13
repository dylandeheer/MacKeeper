/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.common.RoomInstance;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.player.PlayerRoomControl;
import toniarts.openkeeper.game.controller.room.*;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.listener.RoomListener;
import toniarts.openkeeper.game.logic.ChickenSpawnSystem;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.utils.Utils;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.proxy;

class ChickenSpawnContinuationTest {
    @Test void storedAndFreeRangeSpawnsKeepCooldownsAndPendingPopulationAddsThroughRepeatedLoads() throws Exception {
        try(var a=new World();var b=new World()) {
            step(a,b,1.5f);reload(b);assertTrue(a.spawned.isEmpty());
            step(a,b,.5f);assertEquals(2,a.spawned.size());assertTrue(a.spawned.get(1).freeRange());
            assertEquals(2,a.system.captureState().owners().size()); // The new egg is pending the next query update.
            reload(b);step(a,b,.25f);assertEquals(3,a.system.captureState().owners().size());
            reload(b);step(a,b,1.75f);assertEquals(3,a.spawned.size()); // Free-range cap blocks another egg.
            for(float time:List.of(.125f,.875f,2f,4f)){reload(b);step(a,b,time);}
        }
    }

    @Test void pendingCoopAndOwnerChangesReconcileOnceWithRetainedNullAndEmptyIndexes() throws Exception {
        try(var a=new World();var b=new World()) {
            step(a,b,1.5f);
            for(var w:List.of(a,b)) {
                w.data.removeEntity(new EntityId(2));w.coop(5);
                w.data.setComponent(new EntityId(3),new Position(0,new Vector3f(0,0,0))); // Initially unbound coop; changed positions remain ignored.
                w.data.removeEntity(new EntityId(4));
                w.data.setComponent(new EntityId(5),new Owner((short)2,(short)2)); // A population key absent at capture.
            }
            var pending=b.system.captureState();assertTrue(pending.coops().containsKey(new EntityId(2)));
            assertNull(pending.coops().get(new EntityId(3)));reload(b);reload(b);step(a,b,.5f);
            var state=a.system.captureState();assertFalse(state.coops().containsKey(new EntityId(2)));assertNull(state.coops().get(new EntityId(3)));
            assertEquals(List.of(new EntityId(5)),state.populations().get((short)2));assertTrue(state.populations().get((short)4).isEmpty());
            assertEquals(2,a.spawned.size());reload(b);step(a,b,.25f);
            for(var w:List.of(a,b))w.data.removeEntity(new EntityId(6));
            reload(b);step(a,b,.25f);assertTrue(a.system.captureState().roomCoops().get(new EntityId(1)).isEmpty());
            reload(b);step(a,b,3); // Empty coop group and no capacity: no random selection from an empty set.
        }
    }

    @Test void orderedCoopChoicesSurviveDeletionReadditionAndRestoration() throws Exception {
        try(var a=new World();var b=new World()) {
            for(var w:List.of(a,b)){w.coop(5);w.coop(5);w.data.removeEntity(new EntityId(2));}
            step(a,b,.5f);var order=a.system.captureState().roomCoops().get(new EntityId(1));
            assertEquals(List.of(new EntityId(6),new EntityId(7)),order);
            for(var w:List.of(a,b))w.data.setComponents(new EntityId(2),new ChickenGenerator(),new Position(0,new Vector3f(5,0,0)));
            step(a,b,.5f);assertEquals(List.of(new EntityId(6),new EntityId(7),new EntityId(2)),a.system.captureState().roomCoops().get(new EntityId(1)));
            reload(b);step(a,b,1);assertEquals(2,a.spawned.size());
        }
    }

    @Test void destroyedAndRemovedHatcheriesAreSkippedBeforeStorageAccess() throws Exception {
        try(var a=new World();var b=new World()) {
            step(a,b,1);
            for(var w:List.of(a,b)) {
                w.rooms.get(new EntityId(0)).destroy();
                var removed=w.rooms.get(new EntityId(1));removed.destroy();w.data.removeEntity(removed.getEntityId());w.live.remove(removed.getEntityId());
            }
            var saved=b.system.captureState();assertEquals(1,saved.removedRooms().size());
            var roomState=saved.removedRooms().getFirst();
            var instance=RoomInstance.restoreDefinition(b.roomType,roomState.direction());instance.addCoordinates(roomState.coordinates());
            instance.setOwnerId(roomState.ownerId());instance.setDestroyed(true);
            var replacement=new Generator(roomState.entityId(),b,instance,false,true);replacement.bindRestoredRemovedState(roomState);
            b.rooms.put(replacement.getEntityId(),replacement);
            reload(b);step(a,b,10);assertTrue(a.spawned.isEmpty());reload(b);step(a,b,2);
        }
    }

    @Test void listenersHandleAddedSoldAndCapturedRoomsOnlyAfterActivationAndStopOnce() throws Exception {
        try(var a=new World();var b=new World()) {
            reload(b);
            for(var w:List.of(a,b)) {
                var next=w.addRoom(10,false);w.listeners.get((short)3).forEach(l->l.onBuild(next));
                var first=w.rooms.get(new EntityId(0));w.listeners.get((short)3).forEach(l->l.onSold(first));w.roomControls.get((short)3).onSold(first);w.live.remove(first.getEntityId());
                w.rooms.get(new EntityId(1)).captured((short)4);
            }
            reload(b);step(a,b,2);assertEquals(List.of((short)4,(short)3),a.spawned.stream().map(Spawn::owner).toList());
            b.system.stop();b.system.stop();assertEquals(0,b.listenerCount());
            assertThrows(IllegalStateException.class,b.system::start);assertThrows(IllegalStateException.class,()->b.system.processTick(1));
        }
    }

    @Test void failedActivationRollsBackListenersAndReleasesQueries() throws Exception {
        try(var w=new World()) {
            var saved=w.system.captureState();w.system.stop();var staged=w.restore(saved);assertEquals(0,w.listenerCount());
            assertThrows(IllegalStateException.class,()->staged.processTick(1));w.failSecondListener=true;
            assertThrows(IllegalStateException.class,staged::start);assertEquals(0,w.listenerCount());
            staged.stop();assertThrows(IllegalStateException.class,staged::start);
            w.failSecondListener=false;w.system=w.restore(saved);w.system.start();w.system.start();assertEquals(2,w.listenerCount());
        }
    }

    @Test void strictCodecRetainsNullCoopsAndRejectsInvalidInverseIndexesAndReferences() throws Exception {
        try(var w=new World()) {
            var saved=w.system.captureState();var bytes=ChickenSpawnSnapshots.encode(saved);assertEquals(saved,ChickenSpawnSnapshots.decode(bytes));
            for(int i=0;i<bytes.length;i++){var truncated=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->ChickenSpawnSnapshots.decode(truncated));}
            var bad=bytes.clone();bad[0]=0;assertThrows(IOException.class,()->ChickenSpawnSnapshots.decode(bad));
            assertThrows(IOException.class,()->ChickenSpawnSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
            assertThrows(UnsupportedOperationException.class,()->saved.coops().clear());assertThrows(UnsupportedOperationException.class,()->saved.roomCoops().get(new EntityId(1)).clear());
            assertThrows(IllegalArgumentException.class,()->new ChickenSpawnSnapshots.State(0,saved.settings(),saved.players(),saved.entrances(),saved.coops(),Map.of(),saved.owners(),saved.populations(),List.of()));
            assertThrows(IllegalArgumentException.class,()->new ChickenSpawnSnapshots.State(0,saved.settings(),saved.players(),saved.entrances(),saved.coops(),saved.roomCoops(),Map.of(),saved.populations(),List.of()));
            var invalid=new ChickenSpawnSnapshots.State(0,saved.settings(),saved.players(),List.of(new EntityId(999)),saved.coops(),saved.roomCoops(),saved.owners(),saved.populations(),List.of());
            var before=EntitySnapshots.encode(w.data.capture());assertThrows(IllegalArgumentException.class,()->w.restore(invalid));
            assertArrayEquals(before,EntitySnapshots.encode(w.data.capture()));assertEquals(2,w.listenerCount());assertTrue(w.spawned.isEmpty());
            // Two null-valued entries must not evade duplicate map-key validation.
            try(var buffer=new ByteArrayOutputStream();var out=new DataOutputStream(buffer)) {
                out.writeInt(0x4d4b4847);out.writeInt(1);out.writeDouble(0);out.writeInt(2);out.writeInt(2);out.writeInt(0);out.writeInt(0);out.writeInt(2);
                out.writeLong(0);out.writeBoolean(false);out.writeLong(0);out.writeBoolean(false);
                assertThrows(IOException.class,()->ChickenSpawnSnapshots.decode(buffer.toByteArray()));
            }
        }
    }

    record Spawn(EntityId id,short owner,boolean freeRange,float x,double created) { }
    static void reload(World w)throws Exception {
        var random=RandomSnapshots.decode(RandomSnapshots.encode(w.random.captureState()));
        w.random=toniarts.openkeeper.game.random.SimulationRandom.restore(random);
        var saved=ChickenSpawnSnapshots.decode(ChickenSpawnSnapshots.encode(w.system.captureState()));
        var before=EntitySnapshots.encode(w.data.capture());var spawned=List.copyOf(w.spawned);w.system.stop();w.system=w.restore(saved);
        assertEquals(0,w.listenerCount());assertEquals(saved,w.system.captureState());assertEquals(spawned,w.spawned);
        assertArrayEquals(before,EntitySnapshots.encode(w.data.capture()));w.system.start();assertEquals(2,w.listenerCount());
    }
    static void step(World a,World b,float tpf)throws Exception {
        a.system.processTick(tpf);b.system.processTick(tpf);
        assertEquals(a.random.captureState(),b.random.captureState());
        assertEquals(a.system.captureState(),b.system.captureState());assertEquals(a.spawned,b.spawned);
        assertArrayEquals(EntitySnapshots.encode(a.data.capture()),EntitySnapshots.encode(b.data.capture()));
    }
    static final class Generator extends NormalRoomController implements IChickenGenerator {
        final boolean full;
        Generator(EntityId id,World w,RoomInstance instance,boolean initialize,boolean full){super(id,w.data,w.definition,instance,null,initialize);this.full=full;}
        @Override public boolean isFullCapacity(){return full;}
        public Point getEntranceCoordinate(){return roomInstance.getCenter();}
        public double getLastSpawnTime(){return entityData.getComponent(entityId,ChickenGenerator.class).lastSpawnTime;}
        public void onSpawn(double time,EntityId spawned){entityData.setComponent(entityId,new ChickenGenerator(time));}
    }
    static final class World implements AutoCloseable {
        toniarts.openkeeper.game.random.SimulationRandom random=new toniarts.openkeeper.game.random.SimulationRandom(731);final GameEntityData data=new GameEntityData();final Room roomType=new Room();final IKwdFile definition;
        final Map<EntityId,Generator> rooms=new LinkedHashMap<>();final Map<EntityId,IRoomController> live=new HashMap<>();
        final Map<Short,List<RoomListener>> listeners=new TreeMap<>();final Map<Short,PlayerRoomControl> roomControls=new TreeMap<>();
        final List<IPlayerController> players=new ArrayList<>();final List<Spawn> spawned=new ArrayList<>();
        final IMapController map;final IObjectsController objects;ChickenSpawnSystem system;boolean failSecondListener;
        World() {
            roomType.setRoomId((short)3);roomType.setOrderInEditor((short)3);roomType.setFlags(EnumSet.noneOf(Room.RoomFlag.class));
            var gameLevel=new GameLevel();gameLevel.setTicksPerSec(4);
            definition=proxy(IKwdFile.class,(name,args)->switch(name){case "getGameLevel"->gameLevel;default->throw new AssertionError(name);});
            for(short owner:new short[]{3,4}) {
                var keeper=new Keeper(false,owner);var rc=new PlayerRoomControl(keeper,List.of(roomType));roomControls.put(owner,rc);listeners.put(owner,new ArrayList<>());
                players.add(proxy(IPlayerController.class,(name,args)->switch(name){case "getKeeper"->keeper;case "getRoomControl"->rc;default->throw new AssertionError(name);}));
            }
            addRoom(0,false);addRoom(5,true);coop(5);coop(20);food((short)3);food((short)4);
            map=proxy(IMapController.class,(name,args)->switch(name){
                case "getRoomController"->live.get(args[0]);
                case "getRoomControllerByCoordinates"->live.values().stream().filter(r->r.getRoomInstance().getCoordinates().contains(args[0])).findFirst().orElse(null);
                case "addListener"->{listeners.get((short)args[0]).add((RoomListener)args[1]);if(failSecondListener && (short)args[0]==4)throw new IllegalStateException("registration failed");yield null;}
                case "removeListener"->{listeners.get((short)args[0]).remove(args[1]);yield null;}
                default->throw new AssertionError(name);
            });
            objects=proxy(IObjectsController.class,(name,args)->switch(name){case "getRandom"->random;
                case "spawnChicken","spawnFreerangeChicken"->{short owner=(short)args[0];var p=(Vector3f)args[1];boolean free=name.equals("spawnFreerangeChicken");
                    double created=free?(double)args[2]:0;var id=data.createEntity();data.setComponents(id,new Owner(owner,owner),new Food(),new Position(0,p.clone()));
                    if(free)data.setComponent(id,new Decay(created,30));spawned.add(new Spawn(id,owner,free,p.x,created));yield id;}
                default->throw new AssertionError(name);
            });
            var settings=new EnumMap<Variable.MiscVariable.MiscType,Variable.MiscVariable>(Variable.MiscVariable.MiscType.class);
            for(var entry:Map.of(Variable.MiscVariable.MiscType.CHICKEN_GENERATION_TIME_PER_HATCHERY,8,Variable.MiscVariable.MiscType.MAX_FREE_RANGE_CHICKENS_PER_PLAYER,2).entrySet()) {
                var variable=new Variable.MiscVariable();variable.setVariableId(entry.getKey());variable.setValue(entry.getValue());settings.put(entry.getKey(),variable);
            }
            ILevelInfo level=proxy(ILevelInfo.class,(name,args)->{if(name.equals("getLevelData"))return definition;throw new AssertionError(name);});
            system=new ChickenSpawnSystem(data,objects,players,settings,level,map);
        }
        Generator addRoom(int x,boolean full){var instance=new RoomInstance(roomType);instance.setOwnerId((short)3);instance.addCoordinate(new Point(x,0));var id=data.createEntity();
            var room=new Generator(id,this,instance,true,full);data.setComponent(id,new ChickenGenerator(0));rooms.put(id,room);live.put(id,room);roomControls.get((short)3).onBuild(room);return room;}
        EntityId coop(int x){var id=data.createEntity();data.setComponents(id,new ChickenGenerator(),new Position(0,new Vector3f(x,0,0)));return id;}
        EntityId food(short owner){var id=data.createEntity();data.setComponents(id,new Food(),new Decay(0,30),new Owner(owner,owner));return id;}
        ChickenSpawnSystem restore(ChickenSpawnSnapshots.State saved){return ChickenSpawnSystem.restoreState(data,objects,players,map,rooms::get,saved);}
        int listenerCount(){return listeners.values().stream().mapToInt(List::size).sum();}
        public void close(){system.stop();data.close();}
    }
}
