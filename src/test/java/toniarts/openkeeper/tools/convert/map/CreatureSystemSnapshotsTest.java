/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.*;
import toniarts.openkeeper.game.data.CreatureControlState;
import toniarts.openkeeper.game.logic.CreatureAiSystem;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.task.ITaskManager;
import static org.junit.jupiter.api.Assertions.*;

class CreatureSystemSnapshotsTest {
    @Test void pendingRemovalRunsExitOnceAndPendingAdditionStartsOnNextTick() throws Exception {
        try(var f=new Fixture(new GameEntityData())) {
            var removed=f.creature(true);var retained=f.creature(true);var inactive=f.creature(false);
            f.pin.add(f.factory.createController(inactive));
            f.system=new CreatureAiSystem(f.data,f.factory,f.tasks);
            f.factory.getBoundController(removed).getStateMachine().changeState(CreatureState.SLEEPING);
            f.data.setComponent(removed,new CreatureRecuperating());f.data.removeComponent(removed,CreatureAi.class);
            var added=f.creature(true);
            try(var copy=f.copy()) {
                assertSame(copy.factory.getBoundController(retained),copy.factory.createController(retained));
                assertNotNull(copy.data.getComponent(removed,CreatureRecuperating.class));assertEquivalent(f,copy);
                assertEquals(3,copy.pin.size()); // Inactive cached controller is preserved as well.
                for(int i=0;i<2;i++) {
                    f.system.processTick(.25f);copy.system.processTick(.25f);assertEquivalent(f,copy);
                    assertEquals(i+1,copy.workerPasses);assertNull(copy.data.getComponent(removed,CreatureRecuperating.class));
                    assertEquals(List.of(retained,added),copy.system.captureState().activeControllers());
                }
                assertNull(copy.factory.getBoundController(removed).getStateMachine().getCurrentState());
                assertEquals(CreatureState.SLEEPING,copy.factory.getBoundController(removed).getStateMachine().getPreviousState());
            }
        }
    }

    @Test void repeatedPreTickSaveAndChangesAfterBindingPreserveObservedMembership() throws Exception {
        try(var f=new Fixture(new GameEntityData())) {
            var old=f.creature(true);var stable=f.creature(true);f.system=new CreatureAiSystem(f.data,f.factory,f.tasks);
            f.system.processTick(.25f);f.data.removeComponent(old,CreatureAi.class);var added=f.creature(true);
            try(var first=f.copy();var second=first.copy()) {
                assertEquivalent(first,second);
                // A post-load addition is still pending; deleting an already pending addition must cancel it.
                first.data.removeComponent(added,CreatureAi.class);second.data.removeComponent(added,CreatureAi.class);
                var next=first.creature(true);assertEquals(next,second.creature(true));
                for(int i=0;i<2;i++) {
                    first.system.processTick(.5f);second.system.processTick(.5f);assertEquivalent(first,second);
                    assertEquals(List.of(stable,next),second.system.captureState().activeControllers());
                }
                assertEquals(1.25f,second.data.getComponent(stable,TaskComponent.class).taskDuration);
            }
        }
    }

    @Test void cachedSpeciesAndRemovedEntityBindingDoNotReadCurrentCreatureComponents() throws Exception {
        try(var f=new Fixture(new GameEntityData())) {
            var changed=f.creature(true);var removed=f.creature(true);f.system=new CreatureAiSystem(f.data,f.factory,f.tasks);
            f.data.getComponent(changed,CreatureComponent.class).creatureId=3;f.data.removeEntity(removed);
            try(var copy=f.copy()) {
                assertEquivalent(f,copy);
                assertEquals(2,copy.states().getFirst().creatureType());assertEquals(2,copy.states().getLast().creatureType());
                assertNotNull(copy.factory.getBoundController(removed));
                f.system.processTick(.25f);copy.system.processTick(.25f);assertEquivalent(f,copy);
                assertEquals(List.of(changed),copy.system.captureState().activeControllers());
            }
        }
    }

    @Test void invalidBatchDoesNotPublishPartialCacheAndMissingActiveBindingsAreRejected() throws Exception {
        try(var f=new Fixture(new GameEntityData())) {
            var id=f.creature(false);var good=state(id,(short)2);var bad=state(new EntityId(999),(short)2);
            byte[] before=EntitySnapshots.encode(f.data.capture());
            assertThrows(IllegalArgumentException.class,()->f.factory.restoreCreatureControllers(List.of(good,bad),new TaskNodeReferences.Bindings(Map.of())));
            assertThrows(IllegalArgumentException.class,()->f.factory.getBoundController(id));
            assertThrows(IllegalArgumentException.class,()->CreatureAiSystem.restoreState(f.data,f.factory,f.tasks,new CreatureAiSystem.SavedState(List.of(id))));
            assertThrows(IllegalArgumentException.class,()->f.factory.restoreCreatureControllers(List.of(good,good),new TaskNodeReferences.Bindings(Map.of())));
            assertArrayEquals(before,EntitySnapshots.encode(f.data.capture()));
            f.pin.addAll(f.factory.restoreCreatureControllers(List.of(good),new TaskNodeReferences.Bindings(Map.of())));
            assertSame(f.pin.getFirst(),f.factory.createController(id));
            assertThrows(IllegalStateException.class,()->f.factory.restoreCreatureControllers(List.of(),new TaskNodeReferences.Bindings(Map.of())));
        }
        try(var empty=new Fixture(new GameEntityData())) {
            empty.factory.restoreCreatureControllers(List.of(),new TaskNodeReferences.Bindings(Map.of()));
            assertThrows(IllegalStateException.class,()->empty.factory.restoreCreatureControllers(List.of(),new TaskNodeReferences.Bindings(Map.of())));
        }
    }

    @Test void boundedCodecRejectsEveryTruncationCorruptMembershipAndTrailingBytes() throws Exception {
        var state=new CreatureAiSystem.SavedState(List.of(new EntityId(0),new EntityId(17)));
        byte[] bytes=CreatureSystemSnapshots.encode(state);assertEquals(state,CreatureSystemSnapshots.decode(bytes));
        for(int i=0;i<bytes.length;i++){var cut=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->CreatureSystemSnapshots.decode(cut));}
        for(int offset:new int[]{0,4,8,12}){var bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class,()->CreatureSystemSnapshots.decode(bad));}
        assertThrows(IOException.class,()->CreatureSystemSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
        assertThrows(IOException.class,()->CreatureSystemSnapshots.decode(new byte[12+65_536*8+1]));
        assertThrows(IllegalArgumentException.class,()->new CreatureAiSystem.SavedState(List.of(new EntityId(-1))));
        assertThrows(IllegalArgumentException.class,()->new CreatureAiSystem.SavedState(List.of(new EntityId(0),new EntityId(0))));
        assertThrows(IllegalArgumentException.class,()->new CreatureAiSystem.SavedState(List.of(new EntityId(1),new EntityId(0))));
        assertThrows(UnsupportedOperationException.class,()->state.activeControllers().clear());
    }

    static CreatureControlState state(EntityId id,short species){return new CreatureControlState(id,species,CreatureState.DEAD,null,null,0,0,null,null,null,0,List.of());}
    static void assertEquivalent(Fixture a,Fixture b)throws Exception {
        assertEquals(a.system.captureState(),b.system.captureState());assertEquals(a.states(),b.states());
        assertArrayEquals(EntitySnapshots.encode(a.data.capture()),EntitySnapshots.encode(b.data.capture()));
    }
    interface Call{Object invoke(String method,Object[] args);}
    static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m.getName(),a)));}
    static final class Fixture implements AutoCloseable {
        final GameEntityData data;final CreaturesController factory;final ITaskManager tasks;final List<ICreatureController> pin=new ArrayList<>();
        CreatureAiSystem system;int workerPasses;
        Fixture(GameEntityData data){
            this.data=data;var definition=new Creature();definition.setCreatureId((short)2);
            var kwd=proxy(IKwdFile.class,(m,a)->switch(m){case "getCreature"->((Short)a[0])==2?definition:null;case "getThings"->{assertSame(Thing.HeroParty.class,a[0]);yield List.of();}default->throw new AssertionError(m);});
            tasks=proxy(ITaskManager.class,(m,a)->{if(m.equals("processUnemployedWorkerQueue")){workerPasses++;yieldCheck();return null;}throw new AssertionError(m);});
            var nav=proxy(INavigationService.class,(m,a)->{throw new AssertionError(m);});
            var world=proxy(IGameWorldController.class,(m,a)->switch(m){case "getObjectsController","getShotsController"->null;default->throw new AssertionError(m);});
            var game=proxy(IGameController.class,(m,a)->switch(m){case "getNavigationService"->nav;case "getTaskManager"->tasks;case "getEntityLookupService"->null;case "getGameWorldController"->world;default->throw new AssertionError(m);});
            factory=CreaturesController.restoreBindings(kwd,data,Map.of(),()->1,game,null,null);
        }
        void yieldCheck(){if(system!=null)for(var id:system.captureState().activeControllers()) assertTrue(data.getComponent(id,TaskComponent.class).taskDuration>0);}
        EntityId creature(boolean active){var id=data.createEntity();var c=new CreatureComponent();c.creatureId=2;data.setComponents(id,c,new Position(0,new Vector3f()),new TaskComponent(0,null,null,null,0,true));if(active)data.setComponent(id,new CreatureAi(0,CreatureState.DEAD,(short)2));return id;}
        List<CreatureControlState> states(){return factory.captureControllerStates(new TaskNodeReferences.Capture());}
        Fixture copy()throws Exception {
            var copy=new Fixture(GameEntityData.restore(EntitySnapshots.decode(EntitySnapshots.encode(data.capture()))));
            byte[] before=EntitySnapshots.encode(copy.data.capture());var states=new ArrayList<CreatureControlState>();
            for(var state:states())states.add(CreatureControlSnapshots.decode(CreatureControlSnapshots.encode(state)));
            copy.pin.addAll(copy.factory.restoreCreatureControllers(states,new TaskNodeReferences.Bindings(Map.of())));
            copy.system=CreatureAiSystem.restoreState(copy.data,copy.factory,copy.tasks,CreatureSystemSnapshots.decode(CreatureSystemSnapshots.encode(system.captureState())));
            assertArrayEquals(before,EntitySnapshots.encode(copy.data.capture()));return copy;
        }
        public void close(){if(system!=null)system.stop();data.close();}
    }
}
