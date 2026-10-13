/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.badlogic.gdx.ai.pfa.DefaultGraphPath;
import com.simsilica.es.EntityId;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.common.RoomInstance;
import toniarts.openkeeper.game.component.CreatureComponent;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.map.IMapTileInformation;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.save.TaskNodeReferences.Reference;
import toniarts.openkeeper.game.task.*;
import toniarts.openkeeper.game.task.objective.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.CreatureControlSnapshotsTest.proxy;

class ObjectiveTaskGraphTest {
    static final Instant TIME=Instant.parse("2026-01-02T03:04:05.123456789Z");
    static TaskIdentityAllocator ids(){return new TaskIdentityAllocator(new TaskIdentityAllocator.State(0),()->TIME);}
    static TaskGraphSnapshots.Context context(TaskIdentityAllocator.State allocation,GameEntityData data,ILevelInfo level){
        var nav=proxy(INavigationService.class,(m,a)->{throw new AssertionError("Restore replanned: "+m);});
        var map=proxy(IMapController.class,(m,a)->{throw new AssertionError("Restore queried map: "+m);});
        return new TaskGraphSnapshots.Context(new TaskIdentityAllocator(allocation,()->{throw new AssertionError("Restore allocated identity");}),data,null,nav,map,null,null,null,null,null,null,level);
    }
    @Test void plannedKillGraphPreservesSharedWrappersDetachedRetryAndRealCreatureCycleWithoutReplanning() throws Exception {
        try(var f=new CreatureControlSnapshotsTest.Fixture()) {
            f.data.getComponent(f.id,CreatureComponent.class).worker=true;
            var ids=ids();var parent=plan(ids,f.controller);
            assertEquals(2,parent.getTaskQueue().size());
            var dig=(ObjectiveTaskDecorator)parent.getTaskQueue().getFirst();
            var orphan=(ObjectiveTaskDecorator)parent.getTaskQueue().removeLast();
            assertTrue(dig.isWorkerPartyTask());assertFalse(orphan.isWorkerPartyTask());
            var shared=new ObjectiveTaskDecorator(parent.getId(),dig.getWrappedTask(),true);parent.addSubTask(shared);
            // A decorator owns a queue independently of its wrapped task's queue.
            var nested=new GoToTask(ids,null,null,new Point(6,7),(short)3);shared.addSubTask(nested);
            var wrapped=(GoToTask)orphan.getWrappedTask();wrapped.assign(f.controller,false);
            f.task=orphan;f.time=50;f.controller.navigateToAssignedTask();
            var creature=f.capture();assertNotNull(creature.failedTask());
            var state=TaskGraphSnapshots.capture(f.nodes,List.of(parent),ids.captureState());
            assertEquals(2,state.roots().size());assertEquals(7,state.nodes().size());
            assertSame(orphan,f.nodes.node((int)creature.failedTask().nodeId()));
            for(int round=0;round<3;round++)try(var copy=new CreatureControlSnapshotsTest.Fixture(GameEntityData.restore(f.data.capture()),true)) {
                var decoded=TaskGraphSnapshots.decode(TaskGraphSnapshots.encode(state));assertEquals(state,decoded);
                byte[] before=EntitySnapshots.encode(copy.data.capture());
                var c=context(state.allocation(),copy.data,null);var graph=TaskGraphSnapshots.restoreShells(decoded,c);
                var bindings=new HashMap<Long,Task>();for(int i=0;i<graph.nodes().size();i++)bindings.put((long)i,graph.nodes().get(i));
                copy.bindings=bindings;copy.task=graph.bindings().resolve(creature.failedTask());copy.time=f.time;copy.controller=copy.restore(creature);
                var restoredParent=(KillPlayer)graph.roots().get(1);
                assertThrows(IllegalArgumentException.class,()->graph.bindCreatures(id->null));
                assertThrows(IllegalStateException.class,()->restoredParent.creatureTarget().get());
                assertThrows(IllegalStateException.class,()->((AbstractTask)((ObjectiveTaskDecorator)copy.task).getWrappedTask()).captureBaseState());
                graph.bindCreatures(id->copy.controller);assertThrows(IllegalStateException.class,()->graph.bindCreatures(id->copy.controller));
                assertSame(copy.controller,restoredParent.creatureTarget().get());
                var restoredDig=(ObjectiveTaskDecorator)restoredParent.getTaskQueue().getFirst();
                var restoredShared=(ObjectiveTaskDecorator)restoredParent.getTaskQueue().getLast();
                assertNotSame(restoredDig,restoredShared);assertSame(restoredDig.getWrappedTask(),restoredShared.getWrappedTask());
                assertTrue(restoredShared.isWorkerPartyTask());assertEquals(1,restoredShared.getTaskQueue().size());
                assertEquals(parent.getId(),copy.task.getId());assertNotSame(copy.task,restoredDig);
                var refs=new TaskNodeReferences.Capture();assertEquals(creature,copy.controller.captureState(refs));
                assertEquals(state,TaskGraphSnapshots.capture(refs,graph.roots(),c.ids().captureState()));
                assertArrayEquals(before,EntitySnapshots.encode(copy.data.capture()));assertEquals(state.allocation(),c.ids().captureState());
                // Ordinary next actions operate on the actual saved GoTo object, including its assignee.
                if(round==0) {
                    for(double time:new double[]{50.1,50.9,51}) {
                        f.time=copy.time=time;f.controller.navigateToAssignedTask();copy.controller.navigateToAssignedTask();
                        assertEquals(f.searches-1,copy.searches);
                        assertEquals(f.controller.captureState(new TaskNodeReferences.Capture()),copy.controller.captureState(new TaskNodeReferences.Capture()));
                    }
                    wrapped.executeTask(f.controller,1);((ObjectiveTaskDecorator)copy.task).getWrappedTask().executeTask(copy.controller,1);
                    assertFalse(copy.task.isValid(copy.controller));assertFalse(wrapped.isValid(f.controller));
                    var fresh=new TaskNodeReferences.Capture();creature=f.controller.captureState(fresh);
                    state=TaskGraphSnapshots.capture(fresh,List.of(parent),ids.captureState());
                }
            }
        }
    }
    @Test void recapturePrunesRemovedChildrenButRetainsExplicitRetryOwners() throws Exception {
        var ids=ids();var parent=new GoToTask(ids,null,null,new Point(1,1),(short)3);
        var child=new GoToTask(ids,null,null,new Point(2,2),(short)3);parent.addSubTask(child);
        var state=TaskGraphSnapshots.capture(new TaskNodeReferences.Capture(),List.of(parent),ids.captureState());
        var restored=TaskGraphSnapshots.restoreShells(state,context(state.allocation(),null,null));restored.bindCreatures(id->null);
        assertEquals(state,TaskGraphSnapshots.capture(new TaskNodeReferences.Capture(),restored.roots(),ids.captureState()));
        var root=(ObjectiveTask)restored.roots().getFirst();Task removed=root.getTaskQueue().removeFirst();
        var pruned=TaskGraphSnapshots.capture(new TaskNodeReferences.Capture(),restored.roots(),ids.captureState());
        assertEquals(1,pruned.nodes().size());assertEquals(1,pruned.roots().size());
        var retryRefs=new TaskNodeReferences.Capture();retryRefs.reference(removed);
        var retained=TaskGraphSnapshots.capture(retryRefs,restored.roots(),ids.captureState());assertEquals(2,retained.nodes().size());assertEquals(2,retained.roots().size());
    }
    @Test void waypointAndGoToExecutionFlagsAndCachedCoordinatesSurviveRepeatedLoads() throws Exception {
        var thing=new Thing.ActionPoint();thing.setId((short)4);thing.setStartX(2);thing.setEndX(2);thing.setStartY(3);thing.setEndY(3);thing.setNextWaypointId((short)5);
        var point=new ActionPoint(thing);var ids=ids();var original=new SendToActionPoint(ids,null,null,point,(short)3);
        point.getCenter().set(9,9); // task retained the original coordinate, even if definition center mutates
        var assignments=new ArrayList<Integer>();
        var creature=proxy(ICreatureController.class,(m,a)->switch(m){
            case "getObjective"->Thing.HeroParty.Objective.SEND_TO_ACTION_POINT;case "getObjectiveTargetActionPointId"->4;
            case "setObjectiveTargetActionPointId"->{assignments.add(((Number)a[0]).intValue());yield null;}
            default->throw new AssertionError(m);});
        var level=proxy(ILevelInfo.class,(m,a)->{if(m.equals("getActionPoint"))return point;throw new AssertionError(m);});
        for(int round=0;round<3;round++) {
            var saved=ObjectiveTaskSnapshots.decode(ObjectiveTaskSnapshots.encode(ObjectiveTaskSnapshots.capture(original)));
            var restored=(SendToActionPoint)ObjectiveTaskSnapshots.restore(saved,ids,null,null,level,null);restored.bindRestoredAssignees(id->null);
            assertEquals(new Point(2,3),restored.getTaskLocation());assertSame(point,restored.getActionPoint());
            assertEquals(saved,ObjectiveTaskSnapshots.capture(restored));assertEquals(round==0,restored.isValid(creature));
            if(restored.isValid(creature))restored.executeTask(creature,1);
            assertEquals(List.of(5),assignments);assertTrue(restored.hasExecutedWaypoint());assertFalse(restored.hasExecutedGoTo());original=restored;
        }
        var go=new GoToTask(ids,null,null,new Point(1,1),(short)3);go.executeTask(null,1);
        var saved=ObjectiveTaskSnapshots.capture(go);var copy=ObjectiveTaskSnapshots.restore(saved,ids,null,null,null,null);copy.bindRestoredAssignees(id->null);
        assertFalse(copy.isValid(null));assertEquals(saved,ObjectiveTaskSnapshots.capture(copy));
        var waypoint=ObjectiveTaskSnapshots.capture(original);
        assertThrows(IllegalArgumentException.class,()->ObjectiveTaskSnapshots.restore(waypoint,ids,null,null,proxy(ILevelInfo.class,(m,a)->null),null));
    }
    @Test void invalidGraphsRejectCyclesIdentityConflictsOrphansAndIllegalQueueEdges() {
        var ids=ids();var task=new GoToTask(ids,null,null,new Point(1,1),(short)3);
        var payload=new TaskGraphSnapshots.ObjectiveWork(ObjectiveTaskSnapshots.capture(task));
        var a=new Reference(0,0);var b=new Reference(1,0);var allocation=new TaskIdentityAllocator.State(1);
        var first=new TaskGraphSnapshots.Node(a,payload,List.of());
        assertThrows(IllegalArgumentException.class,()->new TaskGraphSnapshots.State(allocation,List.of(a),List.of(first,new TaskGraphSnapshots.Node(b,payload,List.of())),List.of()));
        assertThrows(IllegalArgumentException.class,()->new TaskGraphSnapshots.State(allocation,List.of(a,a),List.of(first),List.of()));
        assertThrows(IllegalArgumentException.class,()->new TaskGraphSnapshots.State(allocation,List.of(),List.of(first),List.of()));
        assertThrows(IllegalArgumentException.class,()->new TaskGraphSnapshots.State(allocation,List.of(a),List.of(new TaskGraphSnapshots.Node(a,payload,List.of(b))),List.of()));
        assertThrows(IllegalArgumentException.class,()->new TaskGraphSnapshots.State(allocation,List.of(a),List.of(new TaskGraphSnapshots.Node(a,payload,List.of(a))),List.of()));
        assertThrows(IllegalArgumentException.class,()->new TaskGraphSnapshots.State(allocation,List.of(a),List.of(new TaskGraphSnapshots.Node(a,new TaskGraphSnapshots.Decorator(b,false),List.of()),new TaskGraphSnapshots.Node(b,new TaskGraphSnapshots.Decorator(a,false),List.of())),List.of()));
        assertThrows(IllegalArgumentException.class,()->new TaskGraphSnapshots.State(new TaskIdentityAllocator.State(0),List.of(a),List.of(first),List.of()));
        var tile=new ObjectiveDigTileTask(ids,null,null,new Point(2,2),(short)3);var tileRef=new Reference(1,tile.getId());
        var tilePayload=new TaskGraphSnapshots.Tile(TileTaskSnapshots.capture(tile));
        assertThrows(IllegalArgumentException.class,()->new TaskGraphSnapshots.State(ids.captureState(),List.of(a),List.of(new TaskGraphSnapshots.Node(a,payload,List.of(tileRef)),new TaskGraphSnapshots.Node(tileRef,tilePayload,List.of())),List.of()));
        assertThrows(IllegalArgumentException.class,()->new TaskGraphSnapshots.State(ids.captureState(),List.of(a,tileRef),List.of(first,new TaskGraphSnapshots.Node(tileRef,tilePayload,List.of(a))),List.of()));
        var longChain=new ArrayList<TaskGraphSnapshots.Node>();
        for(int i=0;i<258;i++)longChain.add(new TaskGraphSnapshots.Node(new Reference(i,0),i==257?payload:new TaskGraphSnapshots.Decorator(new Reference(i+1,0),false),List.of()));
        assertThrows(IllegalArgumentException.class,()->new TaskGraphSnapshots.State(allocation,List.of(a),longChain,List.of()));
        var mismatch=new Reference(0,1);assertThrows(IllegalArgumentException.class,()->new TaskGraphSnapshots.State(ids.captureState(),List.of(mismatch),List.of(first),List.of()));
    }
    @Test void codecsRejectTruncationUnknownHeadersTrailingDataAndInvalidValues() throws Exception {
        var ids=ids();var go=new GoToTask(ids,null,null,new Point(2,3),(short)3);
        var wrapper=new ObjectiveTaskDecorator(go.getId(),go,true);var state=TaskGraphSnapshots.capture(new TaskNodeReferences.Capture(),List.of(wrapper),ids.captureState());
        byte[] bytes=TaskGraphSnapshots.encode(state);assertEquals(state,TaskGraphSnapshots.decode(bytes));
        for(int i=0;i<bytes.length;i++){var cut=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->TaskGraphSnapshots.decode(cut));}
        assertThrows(IOException.class,()->TaskGraphSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
        for(int offset:new int[]{0,4,16}){var bad=bytes.clone();java.nio.ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class,()->TaskGraphSnapshots.decode(bad));}
        var objective=ObjectiveTaskSnapshots.capture(go);byte[] leaf=ObjectiveTaskSnapshots.encode(objective);
        for(int i=0;i<leaf.length;i++){var cut=Arrays.copyOf(leaf,i);assertThrows(IOException.class,()->ObjectiveTaskSnapshots.decode(cut));}
        assertThrows(IOException.class,()->ObjectiveTaskSnapshots.decode(Arrays.copyOf(leaf,leaf.length+1)));
        for(int offset:new int[]{0,4,8}){var bad=leaf.clone();java.nio.ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class,()->ObjectiveTaskSnapshots.decode(bad));}
        assertThrows(IllegalArgumentException.class,()->new ObjectiveTaskSnapshots.State(objective.kind(),objective.base(),null,(short)3,false,false,0,(short)0,null));
        assertThrows(IllegalArgumentException.class,()->new ObjectiveTaskSnapshots.State(objective.kind(),objective.base(),objective.location(),(short)3,false,true,0,(short)0,null));
        assertThrows(IllegalArgumentException.class,()->new ObjectiveTaskSnapshots.State(objective.kind(),objective.base(),objective.location(),(short)3,false,false,0,(short)0,new EntityId(0)));
        assertThrows(UnsupportedOperationException.class,()->state.nodes().clear());assertThrows(UnsupportedOperationException.class,()->state.roots().clear());
    }
    static KillPlayer plan(TaskIdentityAllocator ids,ICreatureController creature) {
        Point heart=new Point(7,7);var keeper=new Keeper(false,(short)4);keeper.setDungeonHeartLocation(heart);
        var instance=RoomInstance.restoreDefinition(new Room(),null);instance.addCoordinate(heart);
        var room=proxy(IRoomController.class,(m,a)->switch(m){case "isDestroyed"->false;case "getOwnerId"->(short)4;case "getRoomInstance"->instance;default->throw new AssertionError(m);});
        var map=proxy(IMapController.class,(m,a)->{if(m.equals("getRoomControllerByCoordinates"))return room;throw new AssertionError(m);});
        var terrain=new Terrain();terrain.setFlags(EnumSet.of(Terrain.TerrainFlag.SOLID,Terrain.TerrainFlag.ATTACKABLE));
        var definition=proxy(IKwdFile.class,(m,a)->{if(m.equals("getTerrain"))return terrain;throw new AssertionError(m);});
        var level=proxy(ILevelInfo.class,(m,a)->switch(m){case "getPlayer"->keeper;case "getLevelData"->definition;default->throw new AssertionError(m);});
        var barrier=tile(new Point(2,1));var path=new DefaultGraphPath<IMapTileInformation>();path.add(tile(new Point(1,1)));path.add(barrier);path.add(tile(heart));
        var nav=proxy(INavigationService.class,(m,a)->switch(m){case "findPath"->path;case "isAccessible"->a[1]!=barrier;default->throw new AssertionError(m);});
        return new KillPlayer(ids,nav,map,level,(short)4,creature);
    }
    static IMapTileInformation tile(Point point){return proxy(IMapTileInformation.class,(m,a)->switch(m){case "getLocation"->point;case "getTerrainId"->(short)1;default->throw new AssertionError(m);});}
}
