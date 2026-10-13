/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.badlogic.gdx.ai.fsm.StateMachine;
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
import toniarts.openkeeper.game.controller.object.ObjectController;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.game.controller.room.storage.IRoomObjectControl;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.listener.*;
import toniarts.openkeeper.game.map.*;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.task.*;
import toniarts.openkeeper.game.task.objective.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class TaskManagerContinuationTest {
    record Snapshot(GameEntityData.Snapshot entities, TaskManagerSnapshots.State manager, TaskGraphSnapshots.State graph) { }
    @Test void pendingTaskChangesReleaseReservationsOnceAndKeepFutureAllocationAcrossRepeatedLoads() throws Exception {
        try(var source=new World()) {
            assertTrue(source.manager.assignClosestRoomTask(source.creature(0),ObjectType.LAIR,null));source.manager.processTick(.1f);
            var old=source.manager.getTaskById(source.data.getComponent(new EntityId(0),TaskComponent.class).taskId);
            assertEquals(1,old.getAssigneeCount());
            assertTrue(source.manager.assignObjectiveTask(source.creature(0),Thing.HeroParty.Objective.SEND_TO_ACTION_POINT));
            assertFalse(source.manager.assignClosestRoomTask(source.creature(1),ObjectType.LAIR,null));
            var snapshot=source.capture();assertEquals(1,snapshot.manager().reservations().size());assertEquals(List.of(new EntityId(0)),snapshot.manager().tasks().dirty());
            source.events.clear();
            try(var copy=new World(snapshot)) {
                assertEquals(snapshot.manager(),copy.capture().manager());assertEquals(snapshot.graph(),copy.capture().graph());assertTrue(copy.events.isEmpty());
                source.manager.processTick(.1f);copy.manager.processTick(.1f);
                assertEquals(0,old.getAssigneeCount());assertTrue(copy.capture().manager().reservations().isEmpty());same(source,copy);
                for(int round=0;round<3;round++) {
                    assertTrue(source.manager.assignClosestRoomTask(source.creature(1),ObjectType.LAIR,null));
                    assertTrue(copy.manager.assignClosestRoomTask(copy.creature(1),ObjectType.LAIR,null));same(source,copy);
                    source.manager.processTick(.1f);copy.manager.processTick(.1f);
                    source.data.removeComponent(new EntityId(1),TaskComponent.class);copy.data.removeComponent(new EntityId(1),TaskComponent.class);
                    var pending=copy.capture();copy.reload(pending);assertEquals(pending.manager(),copy.capture().manager());
                    source.manager.processTick(.1f);copy.manager.processTick(.1f);same(source,copy);
                    assertTrue(copy.capture().manager().reservations().isEmpty());
                    source.manager.processTick(.1f);copy.manager.processTick(.1f);same(source,copy);
                }
            }
        }
    }
    @Test void queuedAdditionsCreateOneRescueAndOneFetchWithEquivalentTargetsIdsAndNoBindReplay() throws Exception {
        try(var source=new World()) {
            source.data.setComponent(new EntityId(2),new Unconscious(4));
            var object=source.data.createEntity();source.data.setComponents(object,new ObjectComponent((short)7,ObjectType.SPELL_BOOK),new Position(0,new Vector3f(3,0,3)),new Placeable());
            var snapshot=source.capture();assertTrue(snapshot.manager().unconscious().observed().isEmpty());assertTrue(snapshot.manager().freeObjects().observed().isEmpty());
            try(var copy=new World(snapshot)) {
                assertTrue(copy.capture().manager().registered().isEmpty());
                source.manager.processTick(.1f);copy.manager.processTick(.1f);same(source,copy);
                assertEquals(2,copy.capture().manager().registered().size());
                assertEquals(List.of(TaskType.RESCUE_CREATURE,TaskType.FETCH_OBJECT),copy.capture().manager().registered().keySet().stream().sorted().map(id->copy.manager.getTaskById(id).getTaskType()).toList());
                var again=copy.capture();copy.reload(again);source.manager.processTick(.1f);copy.manager.processTick(.1f);same(source,copy);
                assertEquals(2,copy.capture().manager().registered().size());
                // A newly queued replacement with equal values is still a real observation event.
                source.data.setComponent(object,new Placeable());copy.data.setComponent(object,new Placeable());
                var equal=copy.capture();assertEquals(List.of(object),equal.manager().freeObjects().dirty());copy.reload(equal);
                source.manager.processTick(.1f);copy.manager.processTick(.1f);same(source,copy);
                assertEquals(2,copy.capture().manager().registered().size());
            }
        }
    }
    @Test void pendingWorkIntentsResumeOnceWithStableTaskTiesAndFallbackBehavior() throws Exception {
        try(var source=new World()) {
            // Ordinary tasks share timestamps and distance, and are enqueued in reverse ID order.
            var first=new GoToTask(source.allocator,source.navigation,source.map,new Point(3,1),(short)3);
            var second=new GoToTask(source.allocator,source.navigation,source.map,new Point(1,3),(short)3);
            source.manager.addTask((short)3,second);source.manager.addTask((short)3,first);
            source.manager.addToUnemployedWorkerQueue(source.creature(1),WorkContinuation.IDLE_UPDATE);
            source.manager.addToUnemployedWorkerQueue(source.creature(0),WorkContinuation.IDLE_ENTER);
            source.manager.addToUnemployedWorkerQueue(source.creature(2),WorkContinuation.IDLE_ENTER);
            var snapshot=source.capture();assertEquals(3,snapshot.manager().workers().size());source.events.clear();
            try(var copy=new World(snapshot)) {
                source.manager.processUnemployedWorkerQueue();copy.manager.processUnemployedWorkerQueue();
                assertEquals(source.events,copy.events);assertEquals(List.of("0:assign:0","0:state:WORK","1:assign:1","1:state:WORK","2:wander"),copy.events);
                assertTrue(copy.capture().manager().workers().isEmpty());same(source,copy);
                int size=copy.events.size();copy.manager.processUnemployedWorkerQueue();assertEquals(size,copy.events.size());
            }
        }
    }
    @Test void idleEntryAndRecheckKeepTheirDifferentContinuationsAndGoldFallback() throws Exception {
        try(var w=new World()) {
            var creature=w.creature(0);CreatureState.IDLE.enter(creature);
            assertEquals(List.of("0:unassign"),w.events);assertEquals(WorkContinuation.IDLE_ENTER,w.capture().manager().workers().getFirst().continuation());
            w.manager.processUnemployedWorkerQueue();assertEquals(List.of("0:unassign","0:wander"),w.events);
            w.events.clear();w.stopped=false;CreatureState.IDLE.update(creature);w.manager.processUnemployedWorkerQueue();assertEquals(List.of("0:reset"),w.events);
            w.events.clear();w.stopped=true;CreatureState.IDLE.update(creature);w.manager.processUnemployedWorkerQueue();assertEquals(List.of("0:reset","0:wander"),w.events);
            w.events.clear();w.gold=true;w.deposit=true;CreatureState.IDLE.enter(creature);w.manager.processUnemployedWorkerQueue();assertEquals(List.of("0:unassign","0:deposit","0:state:WORK"),w.events);
            w.events.clear();w.deposit=false;CreatureState.IDLE.enter(creature);w.manager.processUnemployedWorkerQueue();assertEquals(List.of("0:unassign","0:deposit","0:wander"),w.events);
        }
    }
    @Test void invalidOwnershipBindingIsAtomicAndActivationAndListenerLifetimeAreOneShot() throws Exception {
        try(var source=new World()) {
            source.manager.assignObjectiveTask(source.creature(0),Thing.HeroParty.Objective.SEND_TO_ACTION_POINT);source.manager.addTask((short)3,source.manager.getTaskById(0));
            var saved=source.capture();
            try(var copy=new World(saved,false)) {
                assertThrows(IllegalStateException.class,copy.manager::start);assertThrows(IllegalStateException.class,()->copy.manager.processTick(.1f));
                var graph=copy.shells(saved);graph.bindCreatures(copy::creature);
                var s=saved.manager();var bad=new TaskManagerSnapshots.State(List.of(new TaskManagerSnapshots.Queue((short)4,s.queues().getFirst().tasks())),s.registered(),s.entityTasks(),s.reservations(),s.workers(),s.tasks(),s.unconscious(),s.corpses(),s.freeObjects());
                assertThrows(IllegalArgumentException.class,()->copy.manager.bindRestoredState(bad,graph,copy::creature,id->copy.room));
                assertNull(copy.manager.getTaskById(0));
                copy.manager.bindRestoredState(s,graph,copy::creature,id->copy.room);
                assertThrows(IllegalStateException.class,()->copy.manager.bindRestoredState(s,graph,copy::creature,id->copy.room));
                copy.manager.start();assertEquals(3,copy.listeners.size());copy.manager.start();assertEquals(3,copy.listeners.size());
                copy.manager.stop();assertTrue(copy.listeners.isEmpty());copy.manager.stop();assertThrows(IllegalStateException.class,copy.manager::start);
            }
        }
    }
    @Test void graphOwnerAndPerTaskQueueOwnerAreValidatedBeforePublishing() throws Exception {
        try(var source=new World()) {
            source.manager.assignObjectiveTask(source.creature(0),Thing.HeroParty.Objective.SEND_TO_ACTION_POINT);
            source.manager.addTask((short)3,source.manager.getTaskById(0));var saved=source.capture();
            var nodes=new ArrayList<>(saved.graph().nodes());var node=nodes.getFirst();
            var original=((TaskGraphSnapshots.ObjectiveWork)node.payload()).state();
            var foreign=new ObjectiveTaskSnapshots.State(original.kind(),original.base(),original.location(),(short)4,
                    original.goToExecuted(),original.waypointExecuted(),original.actionPoint(),original.targetPlayer(),original.creature());
            nodes.set(0,new TaskGraphSnapshots.Node(node.reference(),new TaskGraphSnapshots.ObjectiveWork(foreign),node.queue()));
            var badGraph=new TaskGraphSnapshots.State(saved.graph().allocation(),saved.graph().roots(),nodes,saved.graph().removedRooms());
            var corrupted=new Snapshot(saved.entities(),saved.manager(),badGraph);
            try(var copy=new World(saved,false)) {
                var bad=copy.shells(corrupted);bad.bindCreatures(copy::creature);
                assertThrows(IllegalArgumentException.class,()->copy.manager.bindRestoredState(saved.manager(),bad,copy::creature,id->copy.room));
                assertNull(copy.manager.getTaskById(0));
                var wrongOwner=source.shells(saved);wrongOwner.bindCreatures(source::creature);
                assertThrows(IllegalArgumentException.class,()->copy.manager.bindRestoredState(saved.manager(),wrongOwner,copy::creature,id->copy.room));
                var good=copy.shells(saved);good.bindCreatures(copy::creature);copy.manager.bindRestoredState(saved.manager(),good,copy::creature,id->copy.room);
                assertThrows(IllegalStateException.class,copy.manager::processUnemployedWorkerQueue);copy.manager.start();
                assertNotNull(copy.manager.getTaskById(0));
            }
        }
    }
    @Test void failedActivationUnregistersPartialListenersAndClosesTheManager() throws Exception {
        try(var source=new World()) {
            var snapshot=source.capture();
            try(var copy=new World(snapshot,false)) {
                var graph=copy.shells(snapshot);graph.bindCreatures(copy::creature);copy.manager.bindRestoredState(snapshot.manager(),graph,copy::creature,id->copy.room);
                copy.failListener=true;
                assertThrows(IllegalArgumentException.class,copy.manager::start);assertTrue(copy.listeners.isEmpty());
                assertThrows(IllegalStateException.class,copy.manager::start);assertThrows(IllegalStateException.class,copy.manager::processUnemployedWorkerQueue);
            }
        }
    }
    @Test void managerCodecRejectsMalformedCollectionsReferencesEnumsAndEveryTruncation() throws Exception {
        try(var w=new World()) {
            w.manager.assignClosestRoomTask(w.creature(0),ObjectType.LAIR,null);w.manager.processTick(.1f);w.manager.addToUnemployedWorkerQueue(w.creature(1),WorkContinuation.IDLE_UPDATE);
            var s=w.capture().manager();byte[] bytes=TaskManagerSnapshots.encode(s);assertEquals(s,TaskManagerSnapshots.decode(bytes));
            for(int i=0;i<bytes.length;i++){var cut=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->TaskManagerSnapshots.decode(cut));}
            for(int offset:new int[]{0,4,8}){var bad=bytes.clone();java.nio.ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class,()->TaskManagerSnapshots.decode(bad));}
            assertThrows(IOException.class,()->TaskManagerSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
            var badEnum=bytes.clone();var word="IDLE_UPDATE".getBytes(java.nio.charset.StandardCharsets.UTF_8);int at=indexOf(badEnum,word);assertTrue(at>0);badEnum[at]='X';assertThrows(IOException.class,()->TaskManagerSnapshots.decode(badEnum));
            assertThrows(IllegalArgumentException.class,()->new TaskManagerSnapshots.Query(List.of(),List.of(new EntityId(1))));
            assertThrows(IllegalArgumentException.class,()->new TaskManagerSnapshots.Query(List.of(new EntityId(0),new EntityId(0)),List.of()));
            assertThrows(IllegalArgumentException.class,()->new TaskManagerSnapshots.State(s.queues(),s.registered(),Map.of(),s.reservations(),s.workers(),s.tasks(),s.unconscious(),s.corpses(),s.freeObjects()));
            assertThrows(IllegalArgumentException.class,()->new TaskManagerSnapshots.State(s.queues(),s.registered(),s.entityTasks(),List.of(s.reservations().getFirst(),s.reservations().getFirst()),s.workers(),s.tasks(),s.unconscious(),s.corpses(),s.freeObjects()));
            assertThrows(UnsupportedOperationException.class,()->s.workers().clear());
        }
    }
    static int indexOf(byte[] a,byte[] b){outer:for(int i=0;i<=a.length-b.length;i++){for(int j=0;j<b.length;j++)if(a[i+j]!=b[j])continue outer;return i;}return -1;}
    static void same(World a,World b)throws Exception{assertEquals(a.capture().manager(),b.capture().manager());assertEquals(a.capture().graph(),b.capture().graph());assertArrayEquals(EntitySnapshots.encode(a.data.capture()),EntitySnapshots.encode(b.data.capture()));}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    static final class World implements AutoCloseable {
        GameEntityData data;TaskManager manager;TaskIdentityAllocator allocator;
        final Map<EntityId,ICreatureController> creatures=new HashMap<>();final List<String> events=new ArrayList<>();final Set<Object> listeners=Collections.newSetFromMap(new IdentityHashMap<>());
        final EntityId roomId=new EntityId(3);final Point point=new Point(4,4);final Keeper keeper=new Keeper(false,(short)3);
        final IRoomController room;final IMapController map;final IMapData mapData;final IGameWorldController world;final IObjectsController objects;final ICreaturesController creatureFactory;
        final INavigationService navigation;final IPlayerController player;final ILevelInfo level;final IKwdFile definition;final List<ActionPoint> actions=new ArrayList<>();
        boolean stopped=true,gold,deposit,failListener;int actionId=4;
        World() throws IOException {this(null,true);}
        World(Snapshot snapshot) throws IOException {this(snapshot,true);}
        World(Snapshot snapshot,boolean bind) throws IOException {
            data=snapshot==null?new GameEntityData():GameEntityData.restore(snapshot.entities());
            if(snapshot==null){for(int i=0;i<4;i++)data.createEntity();for(int i=0;i<3;i++){var c=new CreatureComponent();c.worker=true;data.setComponents(new EntityId(i),c,new Owner((short)3,(short)3),new Health(100,100),new Position(0,new Vector3f(1,0,1)));}}
            for(int i=0;i<3;i++){final EntityId id=new EntityId(i);creatures.put(id,makeCreature(id));}
            for(int i=0;i<2;i++){var ap=new Thing.ActionPoint();ap.setId((short)(4+i));ap.setStartX(i==0?3:1);ap.setEndX(i==0?3:1);ap.setStartY(i==0?1:3);ap.setEndY(i==0?1:3);actions.add(new ActionPoint(ap));}
            var type=new GameObject();type.setObjectId((short)7);type.setPickUpPriority((short)10);
            definition=proxy(IKwdFile.class,(p,m,a)->{if(m.getName().equals("getObject"))return type;throw new AssertionError(m.getName());});
            var storage=proxy(IRoomObjectControl.class,(p,m,a)->switch(m.getName()){case "getAvailableCoordinates"->List.of(point);case "isFullCapacity"->false;default->throw new AssertionError(m.getName());});
            var instance=RoomInstance.restoreDefinition(new Room(),null);instance.addCoordinate(point);
            room=proxy(IRoomController.class,(p,m,a)->switch(m.getName()){
                case "getEntityId"->roomId;case "getOwnerId"->(short)3;case "getRoomInstance"->instance;case "getObjectControl"->storage;
                case "isRemoved","isDestroyed","isFullCapacity"->false;case "isTileAccessible"->true;case "hashCode"->3;case "equals"->p==a[0];default->throw new AssertionError(m.getName());});
            mapData=proxy(IMapData.class,(p,m,a)->switch(m.getName()){
                case "getHeight","getWidth"->10;case "getTile"->tile(a.length==1?(Point)a[0]:new Point((Integer)a[0],(Integer)a[1]));default->throw new AssertionError(m.getName());});
            map=proxy(IMapController.class,(p,m,a)->switch(m.getName()){
                case "getRoomsByFunction"->List.of(room);case "getMapData"->mapData;case "getRoomController"->room;
                case "addListener"->{listeners.add(a[a.length-1]);yield null;}case "removeListener"->{listeners.remove(a[a.length-1]);yield null;}default->throw new AssertionError(m.getName());});
            world=proxy(IGameWorldController.class,(p,m,a)->switch(m.getName()){case "addListener"->{listeners.add(a[0]);if(failListener)throw new IllegalArgumentException("listener registration failed");yield null;}case "removeListener"->{listeners.remove(a[0]);yield null;}default->throw new AssertionError(m.getName());});
            objects=proxy(IObjectsController.class,(p,m,a)->{if(m.getName().equals("createController"))return new ObjectController((EntityId)a[0],data,type,null,map);throw new AssertionError(m.getName());});
            creatureFactory=proxy(ICreaturesController.class,(p,m,a)->{if(m.getName().equals("createController"))return creature((EntityId)a[0]);throw new AssertionError(m.getName());});
            navigation=proxy(INavigationService.class,(p,m,a)->switch(m.getName()){case "findPath"->new DefaultGraphPath<IMapTileInformation>();case "isAccessible"->true;default->throw new AssertionError(m.getName());});
            player=proxy(IPlayerController.class,(p,m,a)->{if(m.getName().equals("getKeeper"))return keeper;throw new AssertionError(m.getName());});
            level=proxy(ILevelInfo.class,(p,m,a)->switch(m.getName()){case "getActionPoint"->actions.get(((Number)a[0]).intValue()-4);default->throw new AssertionError(m.getName());});
            stage(snapshot==null?new TaskIdentityAllocator.State(0):snapshot.graph().allocation());
            if(bind){if(snapshot==null){var refs=new TaskNodeReferences.Capture();var state=manager.captureState(refs);snapshot=new Snapshot(data.capture(),state,manager.captureTaskGraph(refs));}bind(snapshot);}
        }
        void stage(TaskIdentityAllocator.State state){allocator=new TaskIdentityAllocator(state,()->Instant.parse("2026-01-02T03:04:05Z"));manager=TaskManager.stageRestore(data,world,map,objects,creatureFactory,navigation,List.of(player),level,null,Map.of(),allocator);}
        TaskGraphSnapshots.Restored shells(Snapshot s){return TaskGraphSnapshots.restoreShells(s.graph(),new TaskGraphSnapshots.Context(allocator,data,definition,navigation,map,id->room,manager,world,null,objects,id->player,level));}
        void bind(Snapshot s){byte[] before;try{before=EntitySnapshots.encode(data.capture());var graph=shells(s);graph.bindCreatures(this::creature);manager.bindRestoredState(s.manager(),graph,this::creature,id->room);manager.start();assertArrayEquals(before,EntitySnapshots.encode(data.capture()));}catch(IOException e){throw new AssertionError(e);}}
        void reload(Snapshot s){manager.stop();assertTrue(listeners.isEmpty());stage(s.graph().allocation());bind(s);}
        Snapshot capture() throws IOException {var refs=new TaskNodeReferences.Capture();var state=manager.captureState(refs);return new Snapshot(data.capture(),state,manager.captureTaskGraph(refs));}
        ICreatureController creature(int id){return creature(new EntityId(id));}ICreatureController creature(EntityId id){return creatures.get(id);}
        ICreatureController makeCreature(EntityId id){
            var machine=proxy(StateMachine.class,(p,m,a)->{if(m.getName().equals("changeState")){events.add(id.getId()+":state:"+a[0]);return null;}throw new AssertionError(m.getName());});
            return proxy(ICreatureController.class,(p,m,a)->switch(m.getName()){
                case "getEntityId"->id;case "hashCode"->id.hashCode();case "equals"->p==a[0];case "getOwnerId"->(short)3;
                case "getCreatureCoordinates"->new Point(1,1);case "getPosition"->new Vector3f(1,0,1);case "getStateMachine"->machine;
                case "getObjectiveTargetActionPointId"->actionId;case "getObjective"->Thing.HeroParty.Objective.SEND_TO_ACTION_POINT;
                case "isWorker","isTimeToReEvaluate"->true;case "isStopped"->stopped;case "isTooMuchGold"->gold;
                case "dropGoldToTreasury"->{events.add(id.getId()+":deposit");yield deposit;}
                case "setAssignedTask"->{var task=(Task)a[0];events.add(id.getId()+":assign:"+task.getId());data.setComponent(id,new TaskComponent(task.getId(),task.getTaskTarget(),task.getTaskLocation(),task.getTaskType(),0,false));yield null;}
                case "navigateToRandomPoint"->{events.add(id.getId()+":wander");yield null;}
                case "unassingCurrentTask"->{events.add(id.getId()+":unassign");yield null;}
                case "resetReEvaluationTimer"->{events.add(id.getId()+":reset");yield null;}
                case "findWork"->{manager.addToUnemployedWorkerQueue((ICreatureController)p,(WorkContinuation)a[0]);yield null;}
                case "getParty"->null;case "shouldFleeOrAttack","hasObjective","collectPay","needsLair","hasLair","isHungry"->false;
                default->throw new AssertionError(m.getName());});
        }
        IMapTileController tile(Point location){if(location.x<0||location.y<0||location.x>=10||location.y>=10)return null;return proxy(IMapTileController.class,(p,m,a)->switch(m.getName()){case "getLocation"->location;case "getOwnerId"->(short)3;default->throw new AssertionError(m.getName());});}
        public void close(){manager.stop();assertTrue(listeners.isEmpty());data.close();}
    }
}
