/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.badlogic.gdx.ai.pfa.DefaultGraphPath;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.creature.*;
import toniarts.openkeeper.game.data.CreatureControlState;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.task.*;
import toniarts.openkeeper.game.task.objective.ObjectiveTaskDecorator;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class CreatureControlSnapshotsTest {
    @Test void restoredFailedTaskRetainsIdentityDelayAndLaterRouteRecovery() throws Exception {
        try(var f=new Fixture()) {
            f.time=50;f.controller.navigateToAssignedTask();assertEquals(1,f.searches);
            try(var copy=f.copy(f.capture())) {
                assertEquals(f.capture(),copy.capture());
                for(double time:new double[]{50.1,50.9,51}) {
                    f.time=time;copy.time=time;f.controller.navigateToAssignedTask();copy.controller.navigateToAssignedTask();
                    assertEquals(f.searches-1,copy.searches);assertEquals(f.capture(),copy.capture());
                }
                f.destination=new Vector2f(4,6);copy.destination=new Vector2f(4,6);
                f.controller.navigateToAssignedTask();copy.controller.navigateToAssignedTask();assertEquals(f.searches-1,copy.searches);
                f.reachable=true;copy.reachable=true;f.time=52;copy.time=52;
                f.controller.navigateToAssignedTask();copy.controller.navigateToAssignedTask();
                assertNotNull(copy.data.getComponent(copy.id,Navigation.class));assertNull(copy.capture().failedTask());
                assertEquals(f.capture(),copy.capture());assertEntities(f,copy);
            }
        }
    }

    @Test void cachedObjectiveChildSurvivesQueueAdvanceWithSharedRuntimeId() throws Exception {
        try(var f=new Fixture()) {
            var first=new ObjectiveTaskDecorator(0,f.task);
            var second=new ObjectiveTaskDecorator(0,f.task);
            var parent=new ObjectiveTaskDecorator(0,f.task);
            parent.getTaskQueue().add(first);parent.getTaskQueue().add(second);
            f.task=parent.getCurrentTask();f.time=50;f.controller.navigateToAssignedTask();
            assertEquals(1,f.searches);
            parent.getTaskQueue().removeFirst();f.task=parent.getCurrentTask();
            assertSame(second,f.task);assertEquals(first.getId(),second.getId());
            var state=CreatureControlSnapshots.decode(CreatureControlSnapshots.encode(f.capture()));
            assertSame(first,f.nodes.nodes().getFirst());
            try(var copy=new Fixture(GameEntityData.restore(f.data.capture()),true)) {
                var restoredFirst=new ObjectiveTaskDecorator(0,copy.task);
                var restoredSecond=new ObjectiveTaskDecorator(0,copy.task);
                copy.task=restoredSecond;copy.bindings=Map.of(state.failedTask().nodeId(),restoredFirst);
                copy.nodes.reference(restoredFirst);copy.controller=copy.restore(state);
                assertEquals(state,copy.capture());
                f.time=50.1;copy.time=50.1;
                f.controller.navigateToAssignedTask();copy.controller.navigateToAssignedTask();
                // New object means an immediate search, even though time, IDs and target still match.
                assertEquals(2,f.searches);assertEquals(1,copy.searches);
                assertEquals(f.capture(),copy.capture());assertEntities(f,copy);
                assertSame(second,f.nodes.nodes().get(1));assertSame(restoredSecond,copy.nodes.nodes().get(1));
                f.time=50.2;copy.time=50.2;f.controller.navigateToAssignedTask();copy.controller.navigateToAssignedTask();
                assertEquals(2,f.searches);assertEquals(1,copy.searches);
            }
        }
    }

    @Test void nodeBindingsRejectAliasingAndMissingNodesAndCaptureUsesObjectIdentity() {
        Task first=proxy(Task.class,(name,args)->{if(name.equals("getId"))return 42L;throw new AssertionError(name);});
        Task second=proxy(Task.class,(name,args)->{if(name.equals("getId"))return 42L;throw new AssertionError(name);});
        var nodes=new TaskNodeReferences.Capture();var a=nodes.reference(first);var b=nodes.reference(second);
        assertNotEquals(a,b);assertEquals(a,nodes.reference(first));assertEquals(2,nodes.nodes().size());
        var bindings=new TaskNodeReferences.Bindings(Map.of(a.nodeId(),first,b.nodeId(),second));
        assertSame(first,bindings.resolve(a));assertSame(second,bindings.resolve(b));
        assertThrows(IllegalArgumentException.class,()->new TaskNodeReferences.Bindings(Map.of(0L,first,1L,first)));
        assertThrows(IllegalArgumentException.class,()->bindings.resolve(new TaskNodeReferences.Reference(3,42)));
        assertThrows(IllegalArgumentException.class,()->bindings.resolve(new TaskNodeReferences.Reference(0,43)));
        assertThrows(IllegalArgumentException.class,()->new TaskNodeReferences.Reference(-1,0));
        assertThrows(IllegalArgumentException.class,()->new TaskNodeReferences.Reference(0,-1));
    }

    @Test void restoredPaydayCheckDoesNotRepeatAnAlreadyScheduledSearch() throws Exception {
        try(var f=new Fixture()) {
            f.time=50;assertFalse(f.controller.collectPay());assertEquals(1,f.payChecks);assertEquals(54,f.capture().nextPayCheck());
            try(var copy=f.copy(f.capture())) {
                f.time=53.9;copy.time=53.9;assertFalse(f.controller.collectPay());assertFalse(copy.controller.collectPay());assertEquals(0,copy.payChecks);
                f.time=54;copy.time=54;assertFalse(f.controller.collectPay());assertFalse(copy.controller.collectPay());
                assertEquals(f.payChecks-1,copy.payChecks);assertEquals(f.capture(),copy.capture());assertEntities(f,copy);
            }
        }
    }

    @Test void bindsEveryFieldWithoutActionsAndContinuesTaskDurationAndMotionlessTime() throws Exception {
        try(var f=new Fixture()) {
            var room=f.data.createEntity();var point=new Point(8,9);
            var state=new CreatureControlState(f.id,(short)2,CreatureState.DEAD,CreatureState.STUNNED,CreatureState.DEAD,4.75f,7,null,point,new Point(10,11),5,
                    List.of(new CreatureControlState.HeartPath(room,point,4)));
            byte[] before=EntitySnapshots.encode(f.data.capture());
            try(var copy=f.copy(state)) {
                assertEquals(state,copy.capture());assertArrayEquals(before,EntitySnapshots.encode(copy.data.capture()));
                copy.data.setComponents(copy.id,new CreatureAi(0,CreatureState.DEAD,(short)2),new TaskComponent(0,null,null,TaskType.DIG_TILE,1.5f,true));
                copy.controller.processTick(.25f);assertTrue(copy.controller.isTimeToReEvaluate());
                assertEquals(1.75f,copy.data.getComponent(copy.id,TaskComponent.class).taskDuration);
                assertEquals(5,copy.capture().motionless());assertEquals(state.heartPaths(),copy.capture().heartPaths());
                copy.controller.resetReEvaluationTimer();assertEquals(0,copy.capture().motionless());
                assertEquals(0,copy.searches);assertEquals(0,copy.payChecks);
            }
            // IDLE.enter would query the world and assign work, so binding must not call it.
            var idle=new CreatureControlState(f.id,(short)2,CreatureState.IDLE,CreatureState.WORK,null,1,0,null,null,null,0,List.of());
            try(var copy=f.copy(idle)){assertEquals(idle,copy.capture());assertArrayEquals(before,EntitySnapshots.encode(copy.data.capture()));}
        }
    }

    @Test void pendingRemovedEntityRetainsCachedDefinitionAndInvalidTaskBindingsAreRejected() throws Exception {
        try(var f=new Fixture()) {
            f.controller.navigateToAssignedTask();var state=f.capture();f.data.removeEntity(f.id);
            try(var copy=f.copy(state)){assertEquals(state,copy.capture());}
            byte[] before=EntitySnapshots.encode(f.data.capture());
            f.task=null;assertThrows(IllegalArgumentException.class,()->f.restore(state));assertArrayEquals(before,EntitySnapshots.encode(f.data.capture()));
            f.task=proxy(Task.class,(name,args)->99L);assertThrows(IllegalArgumentException.class,()->f.restore(state));
            f.definition.setCreatureId((short)3);assertThrows(IllegalArgumentException.class,()->f.restore(state));
        }
    }

    @Test void codecRejectsMalformedValuesNamesReferencesAndEveryTruncation() throws Exception {
        var id=new EntityId(0);var state=new CreatureControlState(id,(short)2,CreatureState.WORK,CreatureState.IDLE,null,2,3,new TaskNodeReferences.Reference(0,0),new Point(1,2),new Point(3,4),1,
                List.of(new CreatureControlState.HeartPath(new EntityId(1),new Point(5,6),2)));
        byte[] bytes=CreatureControlSnapshots.encode(state);assertEquals(state,CreatureControlSnapshots.decode(bytes));
        for(int n=0;n<bytes.length;n++){byte[] cut=Arrays.copyOf(bytes,n);assertThrows(IOException.class,()->CreatureControlSnapshots.decode(cut));}
        for(int offset:new int[]{0,4,8}){byte[] bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class,()->CreatureControlSnapshots.decode(bad));}
        byte[] bool=bytes.clone();bool[18]=2;assertThrows(IOException.class,()->CreatureControlSnapshots.decode(bool));
        byte[] name=bytes.clone();name[23]='X';assertThrows(IOException.class,()->CreatureControlSnapshots.decode(name));
        assertThrows(IOException.class,()->CreatureControlSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
        assertThrows(IOException.class,()->CreatureControlSnapshots.decode(new byte[2*1024*1024+1]));
        assertThrows(IllegalArgumentException.class,()->new CreatureControlState(id,(short)2,null,null,null,Float.NaN,0,null,null,null,0,List.of()));
        assertThrows(IllegalArgumentException.class,()->new CreatureControlState(id,(short)2,null,null,null,0,0,new TaskNodeReferences.Reference(0,1),null,null,0,List.of()));
        assertThrows(IllegalArgumentException.class,()->new CreatureControlState(id,(short)2,null,null,null,0,0,null,null,null,0,List.of(state.heartPaths().getFirst(),state.heartPaths().getFirst())));
        assertThrows(UnsupportedOperationException.class,()->state.heartPaths().clear());
    }

    static void assertEntities(Fixture a,Fixture b)throws Exception{assertArrayEquals(EntitySnapshots.encode(a.data.capture()),EntitySnapshots.encode(b.data.capture()));}
    interface Call{Object invoke(String name,Object[]args);}
    static <T>T proxy(Class<T>type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m.getName(),a)));}
    static final class Fixture implements AutoCloseable {
        final GameEntityData data;final EntityId id;final Creature definition=new Creature();final INavigationService navigation;final ITaskManager tasks;
        final TaskNodeReferences.Capture nodes=new TaskNodeReferences.Capture();
        Map<Long,Task> bindings;
        CreatureControlState capture(){return controller.captureState(nodes);}
        CreatureController controller;double time;int searches,payChecks;boolean reachable;Vector2f destination=new Vector2f(9,9);Task task;
        Fixture(){this(new GameEntityData(),false);}
        Fixture(GameEntityData data,boolean restored){
            this.data=data;id=restored?new EntityId(0):data.createEntity();definition.setCreatureId((short)2);
            if(!restored){var creature=new CreatureComponent();creature.creatureId=2;creature.pay=1;data.setComponents(id,new Position(0,new Vector3f(1,0,1)),new TaskComponent(),new Owner((short)3,(short)3),new Health(100,100),creature,new CreatureSalary((short)3,10,0,0));}
            task=proxy(Task.class,(name,args)->switch(name){case "getId"->0L;case "getTarget"->destination;case "isFaceTarget"->false;case "getTaskType"->TaskType.DIG_TILE;default->throw new AssertionError(name);});
            navigation=proxy(INavigationService.class,(name,args)->{if(!name.equals("findPath"))throw new AssertionError(name);searches++;return reachable?new DefaultGraphPath<>():null;});
            tasks=proxy(ITaskManager.class,(name,args)->switch(name){case "getTaskById"->task;case "assignPaydayTask"->{payChecks++;yield false;}default->throw new AssertionError(name);});
            controller=new CreatureController(id,data,definition,navigation,tasks,()->time,Map.of(),null,null,null,null,null,null);
        }
        CreatureController restore(CreatureControlState state){return CreatureController.restoreState(state,new TaskNodeReferences.Bindings(bindings != null ? bindings : task == null ? Map.of() : Map.of(0L,task)),data,definition,navigation,tasks,()->time,Map.of(),null,null,null,null,null,null);}
        Fixture copy(CreatureControlState state)throws Exception{var copy=new Fixture(GameEntityData.restore(EntitySnapshots.decode(EntitySnapshots.encode(data.capture()))),true);copy.time=time;copy.controller=copy.restore(CreatureControlSnapshots.decode(CreatureControlSnapshots.encode(state)));return copy;}
        public void close(){data.close();}
    }
}
