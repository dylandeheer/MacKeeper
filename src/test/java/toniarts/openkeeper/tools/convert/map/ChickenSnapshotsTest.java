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
import toniarts.openkeeper.game.controller.chicken.*;
import toniarts.openkeeper.game.logic.ChickenAiSystem;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.save.*;
import static org.junit.jupiter.api.Assertions.*;

class ChickenSnapshotsTest {
    @Test void uninitializedAndHatchingControllersResumeTheSameTransitionsAndClock() throws Exception {
        try (var f=new Fixture(new GameEntityData(),new GameTimeController())) {
            var id=f.egg();var source=new ChickenAiSystem(f.data,f.objects);
            try {
                assertNull(source.captureState().controllers().getFirst().current());
                try (var copy=f.copy(source)) {
                    assertUnchanged(f,source,copy);assertEquals(0,copy.randomCalls);
                    for (float step:new float[]{.25f,.8f,.8f,1.25f,.1f,2.1f}) {
                        f.clock.processTick(step);copy.clock.processTick(step);source.processTick(step);copy.system.processTick(step);
                        assertUnchanged(f,source,copy);assertEquals(f.randomCalls,copy.randomCalls);
                    }
                    assertEquals(ObjectsController.OBJECT_CHICKEN_ID,copy.data.getComponent(id,ObjectComponent.class).objectId);
                }
            } finally {source.stop();}
        }
    }

    @Test void stateBindingPreservesPreviousGlobalAndMotionlessWithoutEnteringOrExiting() throws Exception {
        try (var f=new Fixture(new GameEntityData(),new GameTimeController())) {
            var id=f.egg();var state=new ChickenController.SavedState(id,ChickenState.WANDERING,ChickenState.HATCHING_END,ChickenState.PECKING,4.75f);
            byte[] before=EntitySnapshots.encode(f.data.capture());
            var controller=ChickenController.restoreState(state,f.data,f.egg,f.chicken,f.navigation,f.clock,f.objects,f.map);
            assertEquals(state,controller.captureState());assertArrayEquals(before,EntitySnapshots.encode(f.data.capture()));assertEquals(0,f.randomCalls);
            assertFalse(controller.isTimeToReEvaluate());
            controller.processTick(.25f);
            assertEquals(5,controller.captureState().motionless());assertTrue(controller.isTimeToReEvaluate());
            assertEquals(ChickenState.PECKING,controller.getStateMachine().getCurrentState());
            assertEquals(ChickenState.WANDERING,controller.getStateMachine().getPreviousState());
            controller.resetReEvaluationTimer();assertEquals(0,controller.captureState().motionless());
            // Saved WANDERING must not have re-entered or replayed HATCHING_END.exit.
            assertEquals(0,f.randomCalls);assertEquals(ObjectsController.OBJECT_EGG_ID,f.data.getComponent(id,ObjectComponent.class).objectId);
        }
    }

    @Test void pendingRemovalsRetainTheirExitCallbackAndNewChickensStartOnce() throws Exception {
        try (var f=new Fixture(new GameEntityData(),new GameTimeController())) {
            var removed=f.egg();var gone=f.egg();var source=new ChickenAiSystem(f.data,f.objects);
            try {
                f.objects.createChickenController(removed).getStateMachine().changeState(ChickenState.HATCHING_END);
                f.data.removeComponent(removed,ChickenAi.class);f.data.removeEntity(gone);var added=f.egg();
                try (var copy=f.copy(source)) {
                    assertUnchanged(f,source,copy);
                    source.processTick(.25f);copy.system.processTick(.25f);assertUnchanged(f,source,copy);
                    assertEquals(ObjectsController.OBJECT_CHICKEN_ID,copy.data.getComponent(removed,ObjectComponent.class).objectId);
                    assertNull(copy.data.getComponent(removed,ChickenAi.class));assertEquals(added,copy.system.captureState().controllers().getFirst().entityId());
                    source.processTick(.25f);copy.system.processTick(.25f);assertUnchanged(f,source,copy);
                }
            } finally {source.stop();}
        }
    }

    @Test void dirtyViewsKeepEqualValueReplacementsButDoNotRepairUnchangedViews() throws Exception {
        try (var f=new Fixture(new GameEntityData(),new GameTimeController())) {
            // No Position means these query members have no active chicken controller.
            var unchanged=f.viewOnly();var sameInstance=f.viewOnly();var equalValue=f.viewOnly();var removed=f.viewOnly();
            var source=new ChickenAiSystem(f.data,f.objects);
            try {
                f.data.setComponent(sameInstance,f.data.getComponent(sameInstance,ObjectViewState.class));
                f.data.setComponent(equalValue,wrongView());f.data.removeComponent(removed,ObjectViewState.class);var added=f.viewOnly();
                var state=source.captureState();
                assertTrue(state.dirtyViews().contains(equalValue));assertTrue(state.dirtyViews().contains(sameInstance));
                assertFalse(state.dirtyViews().contains(unchanged));
                try (var copy=f.copy(source)) {
                    // A second save before any tick must preserve queued work as well.
                    var secondState=ChickenSnapshots.decode(ChickenSnapshots.encode(copy.system.captureState()));
                    assertEquals(state,secondState);
                    source.processTick(.25f);copy.system.processTick(.25f);assertUnchanged(f,source,copy);
                    assertEquals(ObjectViewState.GameObjectAnimState.MESH_RESOURCE,copy.data.getComponent(unchanged,ObjectViewState.class).animState);
                    for (var id:List.of(sameInstance,equalValue,added)) assertEquals(ObjectViewState.GameObjectAnimState.ADDITIONAL_RESOURCE_1,copy.data.getComponent(id,ObjectViewState.class).animState);
                    assertNull(copy.data.getComponent(removed,ObjectViewState.class));
                    source.processTick(.25f);copy.system.processTick(.25f);assertUnchanged(f,source,copy);
                }
            } finally {source.stop();}
        }
    }

    @Test void changesAfterBindingAreCombinedWithSavedPendingViews() throws Exception {
        try (var f=new Fixture(new GameEntityData(),new GameTimeController())) {
            var savedDirty=f.viewOnly();var laterDirty=f.viewOnly();var source=new ChickenAiSystem(f.data,f.objects);
            try {
                f.data.setComponent(savedDirty,wrongView());
                try (var copy=f.copy(source)) {
                    f.data.setComponent(laterDirty,wrongView());copy.data.setComponent(laterDirty,wrongView());
                    var sourceState=source.captureState();assertEquals(sourceState,copy.system.captureState());
                    source.processTick(.25f);copy.system.processTick(.25f);assertUnchanged(f,source,copy);
                }
            } finally {source.stop();}
        }
    }

    @Test void malformedSectionsAndInvalidBindingsFailBeforeChangingEntitiesOrCaches() throws Exception {
        var id=new EntityId(0);var controller=new ChickenController.SavedState(id,ChickenState.HATCHING_START,ChickenState.PECKING,null,3.5f);
        var state=new ChickenAiSystem.SavedState(List.of(controller),List.of(id),List.of(id));
        byte[] bytes=ChickenSnapshots.encode(state);assertEquals(state,ChickenSnapshots.decode(bytes));
        for(int n=0;n<bytes.length;n++){byte[] cut=Arrays.copyOf(bytes,n);assertThrows(IOException.class,()->ChickenSnapshots.decode(cut));}
        for(int offset:new int[]{0,4,8,12}) {byte[] bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class,()->ChickenSnapshots.decode(bad));}
        byte[] bool=bytes.clone();bool[20]=2;assertThrows(IOException.class,()->ChickenSnapshots.decode(bool));
        byte[] name=bytes.clone();name[25]='X';assertThrows(IOException.class,()->ChickenSnapshots.decode(name));
        assertThrows(IOException.class,()->ChickenSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
        assertThrows(IllegalArgumentException.class,()->new ChickenController.SavedState(id,null,null,null,Float.NaN));
        assertThrows(IllegalArgumentException.class,()->new ChickenController.SavedState(id,null,null,null,-1));
        assertThrows(IllegalArgumentException.class,()->new ChickenAiSystem.SavedState(List.of(controller,controller),List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->new ChickenAiSystem.SavedState(List.of(),List.of(),List.of(id)));
        assertThrows(UnsupportedOperationException.class,()->state.controllers().clear());
        try(var f=new Fixture(new GameEntityData(),new GameTimeController())) {
            byte[] before=EntitySnapshots.encode(f.data.capture());assertThrows(IllegalArgumentException.class,()->ChickenAiSystem.restoreState(f.data,f.objects,state));
            assertArrayEquals(before,EntitySnapshots.encode(f.data.capture()));var actual=f.egg();
            var good=new ChickenController.SavedState(actual,null,null,null,0);
            var restored=f.objects.restoreChickenControllers(List.of(good));assertSame(restored.getFirst(),f.objects.createChickenController(actual));
            assertThrows(IllegalStateException.class,()->f.objects.restoreChickenControllers(List.of(good)));
        }
    }

    static ObjectViewState wrongView(){return new ObjectViewState(ObjectsController.OBJECT_CHICKEN_ID,null,ObjectViewState.GameObjectAnimState.MESH_RESOURCE,true);}
    static void assertUnchanged(Fixture f,ChickenAiSystem source,Fixture copy) throws Exception {
        assertEquals(source.captureState(),copy.system.captureState());assertArrayEquals(EntitySnapshots.encode(f.data.capture()),EntitySnapshots.encode(copy.data.capture()));
    }
    interface Call {Object invoke(String name,Object[] args);}
    static <T>T proxy(Class<T>type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m.getName(),a)));}
    static final class Fixture implements AutoCloseable {
        final GameEntityData data;final GameTimeController clock;final GameObject egg=new GameObject(),chicken=new GameObject();
        final INavigationService navigation;final IMapController map;final ObjectsController objects;ChickenAiSystem system;int randomCalls;
        Fixture(GameEntityData data,GameTimeController clock){
            this.data=data;this.clock=clock;
            var start=art(10);var end=art(20);var peck=art(10);egg.setMeshResource(start);egg.setAdditionalResources(List.of(end));chicken.setAdditionalResources(List.of(peck));
            egg.setFlags(EnumSet.of(GameObject.ObjectFlag.OBJECT_TYPE_FOOD));chicken.setFlags(EnumSet.of(GameObject.ObjectFlag.OBJECT_TYPE_FOOD));chicken.setSpeed(1);
            var definition=proxy(IKwdFile.class,(m,a)->{if(m.equals("getObject")) return ((Number)a[0]).intValue()==ObjectsController.OBJECT_EGG_ID?egg:chicken;throw new AssertionError(m);});
            navigation=proxy(INavigationService.class,(m,a)->{if(m.equals("findRandomAccessibleTile")||m.equals("findRandomTileInRoom")){randomCalls++;return null;}throw new AssertionError(m);});
            map=proxy(IMapController.class,(m,a)->{throw new AssertionError(m);});
            var world=proxy(IGameWorldController.class,(m,a)->{if(m.equals("getMapController"))return map;throw new AssertionError(m);});
            var game=proxy(IGameController.class,(m,a)->switch(m){case "getNavigationService"->navigation;case "getGameWorldController"->world;default->throw new AssertionError(m);});
            objects=ObjectsController.restoreBindings(definition,data,Map.of(),clock,game,null);
        }
        Fixture copy(ChickenAiSystem source)throws Exception{
            var copy=new Fixture(GameEntityData.restore(EntitySnapshots.decode(EntitySnapshots.encode(data.capture()))),new GameTimeController(clock.captureState()));
            copy.system=ChickenAiSystem.restoreState(copy.data,copy.objects,ChickenSnapshots.decode(ChickenSnapshots.encode(source.captureState())));return copy;
        }
        EntityId egg(){return objects.spawnChicken((short)3,new Vector3f(1,0,1));}
        EntityId viewOnly(){var id=data.createEntity();data.setComponents(id,new ChickenAi(0,ChickenState.PECKING),wrongView());return id;}
        static ArtResource art(int frames){var a=new ArtResource();a.setData("frames",frames);a.setData("fps",10);return a;}
        public void close(){if(system!=null)system.stop();data.close();}
    }
}
