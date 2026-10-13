/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.creature.CreatureState;
import toniarts.openkeeper.game.controller.room.FiveByFiveRotatedController;
import toniarts.openkeeper.game.logic.*;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.utils.WorldUtils;
import static org.junit.jupiter.api.Assertions.*;

class AnimationContinuationTest {
    @Test void fallingPositionsLandingTimesAndStunStatesSurviveRepeatedLoads() throws Exception {
        try(var a=new World(3)) {
            EntityId stunned=a.falling(3),idle=a.falling(0);a.tick(1);
            try(var b=a.copy()) {
                for(float time:List.of(.5f,.25f,.25f)){b.reload();step(a,b,time);}
                assertEquals(WorldUtils.FLOOR_HEIGHT,a.data.getComponent(stunned,Position.class).position.y);
                assertNull(a.data.getComponent(stunned,CreatureFall.class));
                assertEquals(2,a.data.getComponent(stunned,CreatureAi.class).stateStartTime);
                assertEquals(CreatureState.FALLEN,a.data.getComponent(stunned,CreatureAi.class).getCreatureState());
                assertEquals(CreatureState.IDLE,a.data.getComponent(idle,CreatureAi.class).getCreatureState());
                b.reload();step(a,b,2);assertEquals(2,a.data.getComponent(stunned,CreatureAi.class).stateStartTime);
            }
        }
    }

    @Test void fallingWritesDoNotMutateThePreviouslyPublishedPosition() {
        try(var w=new World(3)) {
            var id=w.falling(1);var previous=w.data.getComponent(id,Position.class);var vector=previous.position.clone();
            w.tick(.25f);assertEquals(vector,previous.position);assertNotSame(previous.position,w.data.getComponent(id,Position.class).position);
            assertEquals(vector.y-.5f,w.data.getComponent(id,Position.class).position.y);
        }
    }

    @Test void heartStagesContinueAcrossRemovedPlugTombstonesAndCompletion() throws Exception {
        try(var a=new World(3)) {
            a.tick(2);
            try(var b=a.copy()) {
                b.reload();step(a,b,1);assertFalse(a.visible(a.stairs));
                b.reload();step(a,b,3);assertNotNull(a.data.getComponent(a.plug,Position.class));
                b.reload();step(a,b,.125f);assertNull(a.data.getComponent(a.plug,Position.class));
                b.reload();step(a,b,3);assertFalse(a.visible(a.stairs));
                b.reload();step(a,b,1.875f);assertFalse(a.visible(a.stairs));
                b.reload();step(a,b,.125f);assertTrue(a.visible(a.stairs));assertTrue(a.visible(a.arch));
                assertTrue(a.heart.captureState().done());assertTrue(a.heart.captureState().plugs().isEmpty());
                b.reload();step(a,b,20);assertTrue(a.visible(a.stairs));
            }
        }
    }

    @Test void bindingDoesNotHideAlreadyVisibleFurnitureOrRecreateRemovedParts() throws Exception {
        try(var a=new World(3)) {
            a.tick(1);a.data.setComponent(a.stairs,new ObjectViewState(FiveByFiveRotatedController.OBJECT_BIG_STEPS_ID,null,ObjectViewState.GameObjectAnimState.MESH_RESOURCE,true));
            a.data.removeEntity(a.arch);a.data.setComponent(a.plug,new Owner((short)4,(short)4));
            try(var b=a.copy()) {
                assertTrue(b.visible(b.stairs));assertNull(b.data.getComponent(b.arch,ObjectViewState.class));
                b.reload();step(a,b,11);assertTrue(a.visible(a.stairs));assertNull(a.data.getComponent(a.arch,ObjectViewState.class));
                b.reload();step(a,b,.25f);
            }
        }
    }

    @Test void fallMembershipChangesAndConstructionDelayDoNotResetOnLoad() throws Exception {
        try(var a=new World(14)) {
            var removed=a.falling(1);a.tick(.5f);a.data.removeComponent(removed,CreatureFall.class);
            var pending=a.falling(0);
            try(var b=a.copy()) {
                b.reload();step(a,b,1);assertNull(a.data.getComponent(removed,CreatureAi.class));
                assertNotNull(a.data.getComponent(pending,CreatureFall.class));
                b.reload();for(var w:List.of(a,b))w.data.setComponent(removed,new CreatureFall());
                step(a,b,2);assertNotNull(a.data.getComponent(removed,CreatureAi.class));
                b.reload();step(a,b,10.5f);assertTrue(a.heart.captureState().done());assertTrue(a.visible(a.stairs));
                // Retain the current authored-delay behavior: a delay beyond the
                // final absolute stage enters completion directly.
                assertNotNull(a.data.getComponent(a.plug,Position.class));
                b.reload();var before=b.state();var future=b.object(FiveByFiveRotatedController.OBJECT_BIG_STEPS_ID,(short)3);
                assertTrue(b.visible(future));b.reload();assertTrue(b.visible(future));assertEquals(before,b.state());
            }
        }
    }

    @Test void emptyConstructionAndMalformedAnimationSectionsAreSafe() throws Exception {
        var empty=new GameEntityData();try {
            var heart=new DungeonHeartConstruction(empty,3);heart.processTick(20);
            var saved=heart.captureState();assertFalse(saved.done());assertTrue(saved.plugs().isEmpty());
            var restored=DungeonHeartConstruction.restoreState(empty,saved);restored.processTick(1);assertEquals(21,restored.captureState().elapsed());heart.stop();restored.stop();
        } finally {empty.close();}
        try(var w=new World(3)) {
            var state=w.state();var bytes=AnimationSnapshots.encode(state);assertEquals(state,AnimationSnapshots.decode(bytes));
            for(int i=0;i<bytes.length;i++){var truncated=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->AnimationSnapshots.decode(truncated));}
            var bad=bytes.clone();bad[0]=0;assertThrows(IOException.class,()->AnimationSnapshots.decode(bad));
            var bool=bytes.clone();bool[28]=2;assertThrows(IOException.class,()->AnimationSnapshots.decode(bool));
            assertThrows(IOException.class,()->AnimationSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
            var h=state.heart();
            assertThrows(IllegalArgumentException.class,()->new AnimationSnapshots.Heart(-1,3,false,h.plugs(),h.stairs(),h.arches()));
            assertThrows(IllegalArgumentException.class,()->new AnimationSnapshots.Heart(0,Float.NaN,false,h.plugs(),h.stairs(),h.arches()));
            assertThrows(IllegalArgumentException.class,()->new AnimationSnapshots.Heart(0,3,true,h.plugs(),h.stairs(),h.arches()));
            assertThrows(IllegalArgumentException.class,()->new AnimationSnapshots.Heart(0,3,false,h.plugs(),Map.of(),h.arches()));
            assertThrows(UnsupportedOperationException.class,()->h.stairs().get(w.plug).clear());
            var unknown=new EntityId(999);var invalid=new AnimationSnapshots.Heart(0,3,false,List.of(unknown),Map.of(unknown,List.of()),Map.of(unknown,List.of()));
            var before=EntitySnapshots.encode(w.data.capture());assertThrows(IllegalArgumentException.class,()->DungeonHeartConstruction.restoreState(w.data,invalid));
            var missingFurniture=new AnimationSnapshots.Heart(0,3,false,List.of(w.plug),Map.of(w.plug,List.of(unknown)),Map.of(w.plug,List.of()));
            assertThrows(IllegalArgumentException.class,()->DungeonHeartConstruction.restoreState(w.data,missingFurniture));
            assertArrayEquals(before,EntitySnapshots.encode(w.data.capture()));
        }
    }

    @Test void incompatibleLiveObjectReferencesAndOverlappingRolesFailBeforeMutation() throws Exception {
        try(var w=new World(3)) {
            var creature=w.falling(1);var empty=w.data.createEntity();var snapshot=EntitySnapshots.encode(w.data.capture());
            for(var wrong:List.of(creature,empty,w.stairs)) {
                var invalid=new AnimationSnapshots.Heart(7,3,false,List.of(wrong),Map.of(wrong,List.of()),Map.of(wrong,List.of()));
                assertThrows(IllegalArgumentException.class,()->DungeonHeartConstruction.restoreState(w.data,invalid));
            }
            var wrongFurniture=new AnimationSnapshots.Heart(7,3,false,List.of(w.plug),Map.of(w.plug,List.of(creature)),Map.of(w.plug,List.of()));
            assertThrows(IllegalArgumentException.class,()->DungeonHeartConstruction.restoreState(w.data,wrongFurniture));
            assertThrows(IllegalArgumentException.class,()->new AnimationSnapshots.Heart(7,3,false,List.of(w.plug),Map.of(w.plug,List.of(w.plug)),Map.of(w.plug,List.of())));
            assertThrows(IllegalArgumentException.class,()->new AnimationSnapshots.Heart(7,3,false,List.of(w.plug),Map.of(w.plug,List.of(w.stairs)),Map.of(w.plug,List.of(w.stairs))));
            assertArrayEquals(snapshot,EntitySnapshots.encode(w.data.capture()));
            assertTrue(w.data.hasEntity(empty));w.data.removeEntity(empty);assertFalse(w.data.hasEntity(empty));assertTrue(w.data.wasAllocated(empty));
        }
    }

    static void step(World a,World b,float time)throws Exception {a.tick(time);b.tick(time);assertEquals(a.state(),b.state());assertArrayEquals(EntitySnapshots.encode(a.data.capture()),EntitySnapshots.encode(b.data.capture()));}
    static final class World implements AutoCloseable {
        final GameEntityData data;EntityId plug,stairs,arch;CreatureFallSystem fall;DungeonHeartConstruction heart;
        World(float delay){this(new GameEntityData());plug=object(FiveByFiveRotatedController.OBJECT_PLUG_ID,(short)3);stairs=object(FiveByFiveRotatedController.OBJECT_BIG_STEPS_ID,(short)3);arch=object(FiveByFiveRotatedController.OBJECT_ARCHES_ID,(short)3);
            object(FiveByFiveRotatedController.OBJECT_PLUG_ID,(short)3);object(FiveByFiveRotatedController.OBJECT_PLUG_ID,(short)4);object(FiveByFiveRotatedController.OBJECT_BIG_STEPS_ID,(short)4);object(FiveByFiveRotatedController.OBJECT_ARCHES_ID,(short)4);
            fall=new CreatureFallSystem(data);heart=new DungeonHeartConstruction(data,delay);
        }
        World(GameEntityData data){this.data=data;}
        EntityId object(short type,short owner){var id=data.createEntity();data.setComponents(id,new ObjectComponent(type,null),new Owner(owner,owner),new Position(0,new Vector3f()),new ObjectViewState(type,null,ObjectViewState.GameObjectAnimState.MESH_RESOURCE,true));return id;}
        EntityId falling(float stun){var id=data.createEntity();var creature=new CreatureComponent();creature.creatureId=7;creature.stunDuration=stun;data.setComponents(id,creature,new CreatureFall(),new Position(.5f,new Vector3f(2,WorldUtils.FLOOR_HEIGHT+4,3)));return id;}
        boolean visible(EntityId id){return data.getComponent(id,ObjectViewState.class).visible;}
        void tick(float time){heart.processTick(time);fall.processTick(time);}
        AnimationSnapshots.State state(){return new AnimationSnapshots.State(fall.captureTime(),heart.captureState());}
        void bind(AnimationSnapshots.State state){heart=DungeonHeartConstruction.restoreState(data,state.heart());fall=CreatureFallSystem.restoreState(data,state.fall());}
        World copy()throws Exception {var result=new World(GameEntityData.restore(EntitySnapshots.decode(EntitySnapshots.encode(data.capture()))));result.plug=plug;result.stairs=stairs;result.arch=arch;var before=EntitySnapshots.encode(result.data.capture());result.bind(AnimationSnapshots.decode(AnimationSnapshots.encode(state())));assertArrayEquals(before,EntitySnapshots.encode(result.data.capture()));return result;}
        void reload()throws Exception {var state=AnimationSnapshots.decode(AnimationSnapshots.encode(state()));var before=EntitySnapshots.encode(data.capture());fall.stop();heart.stop();bind(state);assertEquals(state,state());assertArrayEquals(before,EntitySnapshots.encode(data.capture()));}
        public void close(){fall.stop();heart.stop();data.close();}
    }
}
