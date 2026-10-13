/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.badlogic.gdx.ai.pfa.DefaultGraphPath;
import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import java.io.IOException;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.common.RoomInstance;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.*;
import toniarts.openkeeper.game.controller.object.*;
import toniarts.openkeeper.game.controller.room.*;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.game.controller.room.storage.IRoomObjectControl;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.logic.IEntityPositionLookup;
import toniarts.openkeeper.game.map.*;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.task.*;
import toniarts.openkeeper.game.task.creature.*;
import toniarts.openkeeper.game.task.worker.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class EntityTaskContinuationTest {
    static final Instant TIME = Instant.parse("2026-01-02T03:04:05Z");
    static final Point POINT = new Point(2,3);
    @Test void eightTaskTypesKeepFutureEffectsTargetsAssignmentsAndFollowupIdsAfterRepeatedLoads() throws Exception {
        for (var kind : EntityTaskSnapshots.Kind.values()) try (var source = new World();var copy = new World()) {
            var original = source.create(kind);original.assign(source.worker, false);
            if (kind == EntityTaskSnapshots.Kind.CARRY_OBJECT) {
                source.data.setComponent(source.objectId,new HauledBy(source.workerId));
                copy.data.setComponent(copy.objectId,new HauledBy(copy.workerId));
            }
            copy.dragged = source.dragged;
            source.targetPosition = copy.targetPosition = new Vector3f(8,0,9);
            copy.ids = new TaskIdentityAllocator(source.ids.captureState(), () -> TIME);copy.resetManager();
            for (int round = 0; round < 3; round++) {
                var saved = EntityTaskSnapshots.decode(EntityTaskSnapshots.encode(EntityTaskSnapshots.capture(original)));
                byte[] before = EntitySnapshots.encode(copy.data.capture());int callbacks = copy.events.size();
                var restored = copy.restore(saved);
                assertEquals(original.getId(), restored.getId());assertEquals(TIME, restored.getTaskCreated());
                if (restored instanceof CreatureTargetTask target) {
                    assertThrows(IllegalStateException.class, () -> restored.executeTask(copy.worker, 1));
                    target.creatureTarget().bind(id -> copy.target);
                }
                restored.bindRestoredAssignees(id -> copy.worker);
                assertEquals(callbacks, copy.events.size());assertArrayEquals(before, EntitySnapshots.encode(copy.data.capture()));
                assertEquals(saved, EntityTaskSnapshots.capture(restored));
                assertEquals(original.getTaskLocation(), restored.getTaskLocation());
                assertEquals(original.isValid(source.worker), restored.isValid(copy.worker));
                if (original.isValid(source.worker)) original.executeTask(source.worker, round + 1);
                if (restored.isValid(copy.worker)) restored.executeTask(copy.worker, round + 1);
                assertEquals(source.events, copy.events, kind.name());
                assertArrayEquals(EntitySnapshots.encode(source.data.capture()), EntitySnapshots.encode(copy.data.capture()));
                assertEquals(EntityTaskSnapshots.capture(original), EntityTaskSnapshots.capture(restored));
                assertEquals(source.ids.captureState(), copy.ids.captureState());
                assertEquals(source.manager.captureEntityTaskStates(List.of()).tasks(), copy.manager.captureEntityTaskStates(List.of()).tasks());
                if (round == 2) {
                    original.unassign(source.worker);restored.unassign(copy.worker);
                    assertEquals(source.events, copy.events);assertEquals(0, restored.getAssigneeCount());
                }
            }
            assertFalse(source.events.isEmpty(), kind.name());
            if (kind == EntityTaskSnapshots.Kind.CARRY_OBJECT) assertNull(copy.data.getComponent(copy.objectId,HauledBy.class));
            if (kind == EntityTaskSnapshots.Kind.SLEEP) assertTrue(source.events.contains("worker:sleep"));
            if (kind == EntityTaskSnapshots.Kind.CARRY_TO_LAIR) assertTrue(source.events.contains("target:sleep"));
        }
    }
    @Test void creatureFailedTaskAndTargetCycleBindsRealControllersWithoutReplayThenRetainsRetryDeadline() throws Exception {
        try (var f = new CreatureControlSnapshotsTest.Fixture()) {
            var lair = f.data.createEntity();f.data.setComponent(lair, new Position(0,new Vector3f(9,0,9)));
            f.data.setComponent(f.id,new CreatureSleep(lair,0,0));
            var ids = new TaskIdentityAllocator(new TaskIdentityAllocator.State(0), () -> TIME);
            var task = new GoToSleep(ids, f.navigation, null, f.controller);task.assign(f.controller,false);f.task=task;
            f.time=50;f.controller.navigateToAssignedTask();var creatureState=f.capture();var taskState=EntityTaskSnapshots.capture(task);
            assertNotNull(creatureState.failedTask());assertSame(task,f.nodes.nodes().getFirst());
            try (var copy = new CreatureControlSnapshotsTest.Fixture(GameEntityData.restore(f.data.capture()),true)) {
                copy.time=f.time;byte[] before=EntitySnapshots.encode(copy.data.capture());
                var shell=(GoToSleep)EntityTaskSnapshots.restore(taskState,new TaskIdentityAllocator(ids.captureState(),()->{throw new AssertionError("clock");}),
                        copy.navigation,null,null,null,null,copy.data,null,null);
                copy.task=shell;copy.bindings=Map.of(creatureState.failedTask().nodeId(),shell);copy.nodes.reference(shell);
                copy.controller=copy.restore(creatureState);
                assertThrows(IllegalStateException.class,()->shell.creatureTarget().get());
                shell.creatureTarget().bind(id->copy.controller);shell.bindRestoredAssignees(id->copy.controller);
                assertSame(copy.controller,shell.creatureTarget().get());assertEquals(creatureState,copy.capture());
                assertEquals(taskState,EntityTaskSnapshots.capture(shell));assertArrayEquals(before,EntitySnapshots.encode(copy.data.capture()));
                for(double time:new double[]{50.1,50.9,51}) {
                    f.time=copy.time=time;f.controller.navigateToAssignedTask();copy.controller.navigateToAssignedTask();
                    assertEquals(f.searches-1,copy.searches);assertEquals(f.capture(),copy.capture());
                }
            }
        }
    }
    @Test void targetBindingIsAtomicRetryableAndHashBasedQueuesRequireBoundTargets() throws Exception {
        try (var w = new World()) {
            var original=w.create(EntityTaskSnapshots.Kind.RESCUE);original.assign(w.worker,false);
            var state=EntityTaskSnapshots.capture(original);var shell=(RescueCreatureTask)w.restore(state);
            assertThrows(IllegalStateException.class,shell::hashCode);
            assertThrows(IllegalArgumentException.class,()->shell.creatureTarget().bind(id->null));
            assertThrows(IllegalArgumentException.class,()->shell.creatureTarget().bind(id->w.worker));
            assertThrows(IllegalStateException.class,()->shell.creatureTarget().get());
            shell.creatureTarget().bind(id->w.target);shell.bindRestoredAssignees(id->w.worker);
            assertEquals(original,shell);assertEquals(original.hashCode(),shell.hashCode());
            assertThrows(IllegalStateException.class,()->shell.creatureTarget().bind(id->w.target));
            assertEquals(state,EntityTaskSnapshots.capture(shell));
        }
    }
    @Test void cachedObjectDefinitionsSurviveChangedComponentsAndRemovedTargetsWithoutResurrection() throws Exception {
        try (var w = new World()) {
            var fetch=w.create(EntityTaskSnapshots.Kind.FETCH_OBJECT);fetch.assign(w.worker,false);
            w.data.setComponent(w.objectId,new ObjectComponent((short)99,ObjectType.GOLD));
            var changed=EntityTaskSnapshots.capture(fetch);assertEquals(7,changed.object().definition());
            var restored=(FetchObjectTask)w.restore(changed);restored.bindRestoredAssignees(id->w.worker);
            assertEquals(12,restored.getPriority());assertEquals(ObjectType.GOLD,restored.getObjectController().getType());
            w.data.removeEntity(w.objectId);byte[] before=EntitySnapshots.encode(w.data.capture());
            var dead=EntityTaskSnapshots.decode(EntityTaskSnapshots.encode(EntityTaskSnapshots.capture(fetch)));
            var pending=(FetchObjectTask)w.restore(dead);pending.bindRestoredAssignees(id->w.worker);
            assertTrue(pending.isRemovable());assertFalse(pending.isValid(w.worker));assertEquals(12,pending.getPriority());
            assertEquals(fetch,pending);assertEquals(fetch.hashCode(),pending.hashCode());assertEquals(dead,EntityTaskSnapshots.capture(pending));
            assertArrayEquals(before,EntitySnapshots.encode(w.data.capture()));
            assertThrows(IllegalArgumentException.class,()->ObjectController.restoreState(new ObjectControlState(new EntityId(999),(short)7),w.data,w.definition,w.objects,w.map));
            assertThrows(IllegalArgumentException.class,()->ObjectController.restoreState(new ObjectControlState(w.objectId,(short)8),w.data,w.definition,w.objects,w.map));
        }
    }
    @Test void codecRejectsMalformedTypesFieldsReferencesAndEveryTruncation() throws Exception {
        try (var w = new World()) {
            for(var kind:EntityTaskSnapshots.Kind.values()) {
                var task=w.create(kind);task.assign(w.worker,false);var state=EntityTaskSnapshots.capture(task);byte[] bytes=EntityTaskSnapshots.encode(state);
                assertEquals(state,EntityTaskSnapshots.decode(bytes));
                for(int i=0;i<bytes.length;i++){var cut=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->EntityTaskSnapshots.decode(cut));}
                assertThrows(IOException.class,()->EntityTaskSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
                for(int offset:new int[]{0,4,8}){var bad=bytes.clone();java.nio.ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class,()->EntityTaskSnapshots.decode(bad));}
            }
            assertThrows(IOException.class,()->EntityTaskSnapshots.decode(new byte[1024*1024+1]));
            var s=EntityTaskSnapshots.capture(w.create(EntityTaskSnapshots.Kind.RESCUE));
            assertThrows(IllegalArgumentException.class,()->new EntityTaskSnapshots.State(s.kind(),s.base(),s.location(),s.player(),s.target(),null,null,null,true));
            assertThrows(IllegalArgumentException.class,()->new EntityTaskSnapshots.State(s.kind(),s.base(),s.location(),s.player(),s.target(),new ObjectControlState(s.target(),(short)7),null,null,false));
            assertThrows(IllegalArgumentException.class,()->new EntityTaskSnapshots.State(s.kind(),s.base(),s.location(),s.player(),s.target(),null,w.roomId,null,false));
            var invalid=new EntityTaskSnapshots.State(s.kind(),s.base(),s.location(),s.player(),new EntityId(999),null,null,null,false);
            assertThrows(IllegalArgumentException.class,()->w.restore(invalid));
        }
    }
    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type, InvocationHandler call) {return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},call);}
    static final class World implements AutoCloseable {
        final toniarts.openkeeper.game.random.SimulationRandom random=new toniarts.openkeeper.game.random.SimulationRandom(731);
        final GameEntityData data=new GameEntityData();
        final EntityId targetId=data.createEntity(),workerId=data.createEntity(),objectId=data.createEntity(),roomId=data.createEntity(),foodId=data.createEntity();
        TaskIdentityAllocator ids=new TaskIdentityAllocator(new TaskIdentityAllocator.State(0),()->TIME);TaskManager manager;
        final List<String> events=new ArrayList<>();Vector3f targetPosition=new Vector3f(2,0,3);boolean dragged;
        final ICreatureController target=creature(targetId,"target"),worker=creature(workerId,"worker");
        final IRoomController room;final IMapController map;final IObjectsController objects;final ICreaturesController creatures;
        final IPlayerController player;final INavigationService navigation;final IEntityPositionLookup positions;final IKwdFile definition;final ObjectController object;
        World() {
            var type=new GameObject();type.setObjectId((short)7);type.setPickUpPriority((short)12);
            definition=proxy(IKwdFile.class,(p,m,a)->{if(m.getName().equals("getObject"))return ((Number)a[0]).intValue()==7?type:null;throw new AssertionError(m.getName());});
            var roomDefinition=new Room();var instance=RoomInstance.restoreDefinition(roomDefinition,null);instance.addCoordinate(POINT);
            var tile=proxy(IMapTileController.class,(p,m,a)->switch(m.getName()){case "getOwnerId"->(short)3;case "getLocation"->POINT;default->throw new AssertionError(m.getName());});
            var mapData=proxy(IMapData.class,(p,m,a)->{if(m.getName().equals("getTile"))return tile;throw new AssertionError(m.getName());});
            var storage=proxy(IRoomObjectControl.class,(p,m,a)->switch(m.getName()){
                case "isFullCapacity"->false;case "getAvailableCoordinates"->List.of(POINT);
                case "addItem"->{events.add("store:"+((EntityId)a[0]).getId());data.setComponent((EntityId)a[0],new Stored(roomId,ObjectType.SPELL_BOOK));yield null;}
                default->throw new AssertionError(m.getName());});
            room=proxy(IRoomController.class,(p,m,a)->switch(m.getName()){
                case "getEntityId"->roomId;case "isRemoved","isDestroyed","isFullCapacity"->false;case "getOwnerId"->(short)3;
                case "getObjectControl"->storage;case "getRoomInstance"->instance;case "isTileAccessible"->true;
                case "hashCode"->3;case "equals"->p==a[0];default->throw new AssertionError(m.getName());});
            map=proxy(IMapController.class,(p,m,a)->switch(m.getName()){case "getRoomsByFunction"->List.of(room);case "getMapData"->mapData;default->throw new AssertionError(m.getName());});
            objects=proxy(IObjectsController.class,(p,m,a)->{if(m.getName().equals("getRandom"))return random;if(m.getName().equals("createController"))return new ObjectController((EntityId)a[0],data,type,null,map);throw new AssertionError(m.getName());});
            creatures=proxy(ICreaturesController.class,(p,m,a)->{if(m.getName().equals("createController"))return target;throw new AssertionError(m.getName());});
            object=new ObjectController(objectId,data,type,objects,map);
            positions=proxy(IEntityPositionLookup.class,(p,m,a)->switch(m.getName()){case "getEntityLocation"->tile;case "getEntityController"->new ObjectController(foodId,data,type,objects,map);default->throw new AssertionError(m.getName());});
            navigation=proxy(INavigationService.class,(p,m,a)->{if(m.getName().equals("findPath"))return new DefaultGraphPath<IMapTileInformation>();throw new AssertionError(m.getName());});
            var keeper=new Keeper(false,(short)3);player=proxy(IPlayerController.class,(p,m,a)->{if(m.getName().equals("getKeeper"))return keeper;throw new AssertionError(m.getName());});
            data.setComponents(objectId,new ObjectComponent((short)7,ObjectType.SPELL_BOOK),new Position(0,new Vector3f(2,0,3)),new Placeable());
            data.setComponents(foodId,new Food(),new Position(0,new Vector3f(2,0,3)));
            resetManager();
        }
        void resetManager(){if(manager!=null)manager.stop();manager=TaskManager.stageRestore(data,null,map,objects,creatures,navigation,List.of(player),null,positions,Map.of(),ids);}
        ICreatureController creature(EntityId id,String label){return proxy(ICreatureController.class,(p,m,a)->switch(m.getName()){
            case "getEntityId"->id;case "hashCode"->id.hashCode();case "equals"->p==a[0];case "getOwnerId"->(short)3;
            case "getPosition"->targetPosition;case "getLairLocation","getCreatureCoordinates"->POINT;
            case "hasLair","isUnconscious","isHungry"->true;case "isRecuperating","isDead"->false;case "isDragged"->dragged;
            case "setHaulable"->{dragged=a[0]!=null;events.add(label+":haul:"+dragged);yield null;}
            case "sleep"->{events.add(label+":sleep");yield null;}
            case "imprison"->{events.add(label+":prison:"+a[0]+":"+((EntityId)a[1]).getId());yield null;}
            case "eat"->{events.add(label+":eat:"+((toniarts.openkeeper.game.controller.entity.IEntityController)a[0]).getEntityId().getId());yield null;}
            case "setAssignedTask"->{events.add(label+":assign:"+((Task)a[0]).getId()+":"+((Task)a[0]).getTaskType());yield null;}
            default->throw new AssertionError(m.getName());});}
        AbstractTask create(EntityTaskSnapshots.Kind kind){return switch(kind){
            case SLEEP->new GoToSleep(ids,navigation,map,target);
            case EAT->new GoToEat(ids,navigation,map,positions,foodId,data,target);
            case RESCUE->new RescueCreatureTask(ids,manager,navigation,map,target,(short)3);
            case CAPTURE_ENEMY->new CaptureEnemyCreatureTask(ids,navigation,map,target,(short)3,manager);
            case CARRY_TO_LAIR->{dragged=true;yield new CarryCreatureToLairTask(ids,navigation,map,target,(short)3);}
            case CARRY_TO_PRISON->new CarryEnemyCreatureToPrison(ids,navigation,map,POINT,(short)3,room,manager,target);
            case FETCH_OBJECT->new FetchObjectTask(ids,manager,navigation,map,object,(short)3);
            case CARRY_OBJECT->new CarryObjectToStorageTask(ids,navigation,map,POINT,(short)3,room,manager,object);
        };}
        AbstractTask restore(EntityTaskSnapshots.State state){return EntityTaskSnapshots.restore(state,ids,navigation,map,id->room,manager,positions,data,definition,objects);}
        public void close(){manager.stop();data.close();}
    }
}
