/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.data.TaskBaseState;
import toniarts.openkeeper.game.task.*;
import toniarts.openkeeper.game.task.worker.*;
import toniarts.openkeeper.game.task.objective.ObjectiveDigTileTask;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class TileTaskSnapshotsTest {
    static final Instant TIME=Instant.parse("2026-01-02T03:04:05.123456789Z");
    @Test void everyTileTaskContinuesItsActualWorkAndExecutionProgressAfterRepeatedRestore() throws Exception {
        for(var kind:TileTaskSnapshots.Kind.values()) {
            var source=new World();var copy=new World();var ids=new TaskIdentityAllocator(new TaskIdentityAllocator.State(0),()->TIME);
            var original=create(kind,ids,source.map);original.assign(source.creature,true);original.executeTask(source.creature,1.25f);
            var saved=TileTaskSnapshots.decode(TileTaskSnapshots.encode(TileTaskSnapshots.capture(original)));
            var restoredIds=new TaskIdentityAllocator(ids.captureState(),()->{throw new AssertionError("Restore sampled wall clock");});
            var restored=TileTaskSnapshots.restore(saved,restoredIds,null,copy.map);
            assertEquals(original.getId(),restored.getId());assertEquals(TIME,restored.getTaskCreated());
            assertEquals(0,copy.actions);assertEquals(0,copy.assignments);
            assertThrows(IllegalStateException.class,restored::getAssigneeCount);
            restored.bindRestoredAssignees(id->copy.creature);assertEquals(saved,TileTaskSnapshots.capture(restored));
            assertEquals(1,restored.getAssigneeCount());assertEquals(0,copy.assignments);
            int baselineActions=source.actions,baselineGold=source.gold;
            for(float time:new float[]{1.5f,2.5f,3.25f,4.1f}) {
                original.executeTask(source.creature,time);restored.executeTask(copy.creature,time);
                assertEquals(source.actions-baselineActions,copy.actions,kind.name());assertEquals(source.gold-baselineGold,copy.gold,kind.name());
                assertEquals(TileTaskSnapshots.capture(original),TileTaskSnapshots.capture(restored));
                assertEquals(ids.captureState(),restoredIds.captureState());
                var next=TileTaskSnapshots.restore(TileTaskSnapshots.decode(TileTaskSnapshots.encode(TileTaskSnapshots.capture(restored))),restoredIds,null,copy.map);
                next.bindRestoredAssignees(id->copy.creature);restored=next;
            }
            assertEquals(1,source.assignments);assertEquals(0,copy.assignments);
            original.unassign(source.creature);restored.unassign(copy.creature);assertEquals(TileTaskSnapshots.capture(original),TileTaskSnapshots.capture(restored));
        }
    }
    @Test void independentAllocatorCursorsRetainTimestampsAndRejectExhaustionWithoutAdvancing() {
        var a=new TaskIdentityAllocator(new TaskIdentityAllocator.State(0),()->TIME);var zero=a.allocate();assertEquals(0,zero.id());
        var b=new TaskIdentityAllocator(a.captureState(),()->TIME.plusSeconds(1));
        assertEquals(1,a.allocate().id());assertEquals(2,a.allocate().id());assertEquals(1,b.allocate().id());assertEquals(TIME.plusSeconds(1),b.allocate().created());
        assertThrows(IllegalArgumentException.class,()->b.validateRestored(new TaskIdentityAllocator.Identity(3,TIME)));
        var exhausted=new TaskIdentityAllocator(new TaskIdentityAllocator.State(Long.MAX_VALUE),()->TIME);
        assertThrows(IllegalStateException.class,exhausted::allocate);assertEquals(Long.MAX_VALUE,exhausted.captureState().nextId());
        assertThrows(IllegalArgumentException.class,()->new TaskIdentityAllocator.State(-1));
    }
    @Test void simultaneousAllocationsHaveNoDuplicatesOrLostCursorUpdates() throws Exception {
        var ids=new TaskIdentityAllocator(new TaskIdentityAllocator.State(0),()->TIME);var values=ConcurrentHashMap.<Long>newKeySet();
        try(var workers=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<?>>();for(int i=0;i<4;i++)futures.add(workers.submit(()->{for(int n=0;n<200;n++)assertTrue(values.add(ids.allocate().id()));}));
            for(var future:futures)future.get(10,TimeUnit.SECONDS);
        }
        assertEquals(800,values.size());assertEquals(800,ids.captureState().nextId());
    }
    @Test void failedAssigneeBindingCanRetryWithoutPublishingAssignmentsOrCallingAssign() {
        var ids=new TaskIdentityAllocator(new TaskIdentityAllocator.State(1),()->TIME);var world=new World();
        var state=new TileTaskSnapshots.State(TileTaskSnapshots.Kind.DIG,new TaskBaseState(new TaskIdentityAllocator.Identity(0,TIME),Map.of(new EntityId(0),.75f)),new Point(2,3),(short)3);
        var shell=TileTaskSnapshots.restore(state,ids,null,world.map);
        assertThrows(IllegalArgumentException.class,()->shell.bindRestoredAssignees(id->null));
        assertThrows(IllegalStateException.class,shell::captureBaseState);assertThrows(IllegalStateException.class,()->shell.assign(world.creature,true));
        shell.bindRestoredAssignees(id->world.creature);assertEquals(state,TileTaskSnapshots.capture(shell));assertEquals(0,world.assignments);
        assertThrows(IllegalStateException.class,()->shell.bindRestoredAssignees(id->world.creature));
        assertThrows(IllegalArgumentException.class,()->TileTaskSnapshots.restore(state,new TaskIdentityAllocator(),null,world.map));
        assertThrows(IllegalArgumentException.class,()->new TaskBaseState(state.base().identity(),Map.of(new EntityId(0),Float.NaN)));
        assertThrows(IllegalArgumentException.class,()->new TaskBaseState(state.base().identity(),Map.of(new EntityId(0),-1f)));
        assertThrows(UnsupportedOperationException.class,()->state.base().assignees().clear());
    }
    @Test void codecRejectsEveryTruncationInvalidNamesTimestampsReferencesAndTrailingBytes() throws Exception {
        var state=new TileTaskSnapshots.State(TileTaskSnapshots.Kind.DIG,new TaskBaseState(new TaskIdentityAllocator.Identity(0,TIME),Map.of(new EntityId(0),.5f)),new Point(2,3),(short)3);
        byte[] bytes=TileTaskSnapshots.encode(state);assertEquals(state,TileTaskSnapshots.decode(bytes));
        for(int i=0;i<bytes.length;i++){var cut=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->TileTaskSnapshots.decode(cut));}
        for(int offset:new int[]{0,4,8,15}){var bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class,()->TileTaskSnapshots.decode(bad));}
        var nanos=bytes.clone();ByteBuffer.wrap(nanos).putInt(31,1_000_000_000);assertThrows(IOException.class,()->TileTaskSnapshots.decode(nanos));
        var name=bytes.clone();name[12]='X';assertThrows(IOException.class,()->TileTaskSnapshots.decode(name));
        assertThrows(IOException.class,()->TileTaskSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
        assertThrows(IOException.class,()->TileTaskSnapshots.decode(new byte[1024*1024+1]));
        assertThrows(IllegalArgumentException.class,()->new TileTaskSnapshots.State(state.kind(),state.base(),new Point(-1,0),(short)3));
        assertThrows(IllegalArgumentException.class,()->new TileTaskSnapshots.State(state.kind(),state.base(),state.location(),(short)256));
    }
    static AbstractTileTask create(TileTaskSnapshots.Kind kind,TaskIdentityAllocator ids,IMapController map){var point=new Point(2,3);short player=3;return switch(kind){case DIG->new DigTileTask(ids,null,map,point,player);case CLAIM_TILE->new ClaimTileTask(ids,null,map,point,player);case CLAIM_ROOM->new ClaimRoomTask(ids,null,map,point,player);case CLAIM_WALL->new ClaimWallTileTask(ids,null,map,point,player);case REPAIR_WALL->new RepairWallTileTask(ids,null,map,point,player);case OBJECTIVE_DIG->new ObjectiveDigTileTask(ids,null,map,point,player);};}
    static final class World {
        int gold,actions,assignments;final ICreatureController creature;final IMapController map;
        World(){
            creature=(ICreatureController)Proxy.newProxyInstance(ICreatureController.class.getClassLoader(),new Class<?>[]{ICreatureController.class},(p,m,a)->switch(m.getName()){case "getEntityId"->new EntityId(0);case "hashCode"->0;case "equals"->p==a[0];case "isWorker"->true;case "setAssignedTask"->{assignments++;yield null;}case "addGold"->{gold+=(Integer)a[0];yield null;}default->throw new AssertionError(m.getName());});
            map=(IMapController)Proxy.newProxyInstance(IMapController.class.getClassLoader(),new Class<?>[]{IMapController.class},(p,m,a)->{
                assertEquals(new Point(2,3),a[0]);assertEquals((short)3,a[1]);
                return switch(m.getName()){case "damageTile"->{actions++;yield 7;}case "applyClaimTile"->{actions++;yield null;}default->throw new AssertionError(m.getName());};});
        }
    }
}
