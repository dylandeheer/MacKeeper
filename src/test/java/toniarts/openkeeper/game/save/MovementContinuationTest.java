/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.badlogic.gdx.math.Vector2;
import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.logic.MovementSystem;
import toniarts.openkeeper.game.navigation.steering.EntitySteeringFactory.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class MovementContinuationTest {
    @Test void walkingBrakingAndArrivalMatchAcrossRepeatedLoads() throws Exception {
        try(var a=new World()) {
            var id=a.walk();for(int i=0;i<25;i++)a.tick();
            assertTrue(a.body(id).velocity().x()>0);
            try(var b=a.copy()) {
                for(int i=0;i<1600;i++) {if(i%13==0)b.reload();step(a,b);}
                assertNull(a.data.getComponent(id,Navigation.class));assertTrue(a.state().bodies().isEmpty());
                var end=a.data.getComponent(id,Position.class).position;
                assertEquals(6,end.x,.2);assertEquals(2,end.z,.2);assertEquals(7,end.y);
            }
        }
    }

    @Test void angularVelocityAndInternalOrientationSurviveStationaryTurning() throws Exception {
        try(var a=new World()) {
            var id=a.entity(new Navigation(new Point(0,0),new Point(2,0),List.of()),.9f);
            for(int i=0;i<9;i++)a.tick();
            assertNotEquals(0,a.body(id).angularVelocity());
            assertEquals(-a.body(id).orientation(),a.data.getComponent(id,Position.class).rotation);
            try(var b=a.copy()) {for(int i=0;i<900;i++){if(i%7==0)b.reload();step(a,b);}}
        }
    }

    @Test void angularSpeedClampingKeepsTheDirectionOfTheTurn() {
        try(var w=new World()) {
            var id=w.entity(new Navigation(new Point(0,0),new Point(2,0),List.of()),.9f);
            w.movement.processTick(1.5f);
            assertTrue(w.body(id).angularVelocity()<0,"Clamping must not reverse a clockwise turn");
            assertEquals(20,Math.abs(w.body(id).angularVelocity()));
        }
    }

    @Test void allSteerablePropertiesAreDetachedAndRestored() throws Exception {
        var entity=new toniarts.openkeeper.game.navigation.steering.SteerableEntity(new EntityId(3),4,.7f,-2,6,-.5f);
        entity.getLinearVelocity().set(-1,2);entity.setAngularVelocity(-3);entity.setTagged(true);
        entity.setMaxLinearAcceleration(11);entity.setMaxAngularSpeed(6);entity.setMaxAngularAcceleration(9);entity.setZeroLinearSpeedThreshold(.03f);
        var plan=new Plan(List.of(new Waypoint(1,2),new Waypoint(3,4)),.75f);
        var body=MovementSnapshots.Body.capture(entity,-4,8,plan);var saved=new MovementSnapshots.State(List.of(entity.getEntityId()),List.of(),List.of(body));
        var decoded=MovementSnapshots.decode(MovementSnapshots.encode(saved)).bodies().getFirst();
        assertEquals(body,MovementSnapshots.Body.capture(decoded.restore(),-4,8,decoded.plan()));
        entity.getPosition().set(100,100);entity.getLinearVelocity().setZero();entity.setTagged(false);
        assertEquals(new Waypoint(-2,6),body.position());assertEquals(new Waypoint(-1,2),body.velocity());assertTrue(body.tagged());
    }

    @Test void activeRouteAndAccelerationAreIndependentOfSameTargetReplacementAndSpeedUpdates() throws Exception {
        try(var a=new World()) {
            var id=a.walk();for(int i=0;i<15;i++)a.tick();var plan=a.body(id).plan();
            a.data.setComponent(id,new Navigation(new Point(6,2),new Point(0,2),List.of(new Vector2(50,50),new Vector2(60,60))));
            a.data.setComponent(id,new Mobile(false,false,false,3));
            try(var b=a.copy()) {
                b.reload();step(a,b);assertEquals(plan,a.body(id).plan());assertEquals(3,a.body(id).linearSpeed());assertEquals(8,a.body(id).linearAcceleration());
                b.reload();for(int i=0;i<30;i++)step(a,b);
                for(var w:List.of(a,b))w.data.setComponent(id,new Navigation(new Point(9,2),null,List.of(new Vector2(0,0),new Vector2(9,2))));
                b.reload();step(a,b);assertEquals(9,a.body(id).targetX());assertEquals(12,a.body(id).linearAcceleration());
                for(int i=0;i<80;i++)step(a,b);
            }
        }
    }

    @Test void pendingAddsRemovalsAndChangesAfterBindingResumeInTheSamePhase() throws Exception {
        try(var a=new World()) {
            var gone=a.walk();var changed=a.walk();var replaced=a.walk();a.tick();
            a.data.removeEntity(gone);a.data.removeComponent(replaced,Navigation.class);
            var added=a.walk();var noRoute=a.entity(new Navigation(new Point(0,0),null,List.of()),0);
            try(var b=a.copy()) {
                b.reload();
                for(var w:List.of(a,b)) {
                    w.data.setComponent(changed,new Mobile(false,false,false,1));
                    w.data.setComponent(replaced,new Navigation(new Point(8,8),null,List.of(new Vector2(),new Vector2(8,8))));
                }
                b.reload();step(a,b);
                assertNull(a.data.getComponent(noRoute,Navigation.class));assertTrue(a.state().observed().contains(noRoute));
                assertTrue(a.state().bodies().stream().noneMatch(x->x.id().equals(gone)));
                assertTrue(a.state().bodies().stream().anyMatch(x->x.id().equals(added)));
                assertEquals(8,a.body(replaced).targetX());assertEquals(1,a.body(changed).linearSpeed());
                b.reload();step(a,b);assertFalse(a.state().observed().contains(noRoute));
                for(int i=0;i<60;i++)step(a,b);
            }
        }
    }

    @Test void noOpRoutesDoNotReplayInitializationDuringBinding() throws Exception {
        try(var a=new World()) {
            var id=a.entity(new Navigation(new Point(1,1),null,List.of(new Vector2(1,1))),0);
            try(var b=a.copy()) {
                assertNotNull(b.data.getComponent(id,Navigation.class));b.reload();step(a,b);
                assertNull(b.data.getComponent(id,Navigation.class));assertTrue(b.state().observed().contains(id));
                b.reload();step(a,b);assertTrue(b.state().observed().isEmpty());
            }
        }
    }

    @Test void routeGeometryAndPublishedPositionsDoNotShareMutableVectors() throws Exception {
        try(var a=new World()) {
            var target=new Point(6,2);var points=new ArrayList<>(List.of(new Vector2(),new Vector2(3,0),new Vector2(6,2)));
            var id=a.entity(new Navigation(target,null,points),0);a.tick();var before=a.data.getComponent(id,Position.class);var position=before.position.clone();
            var frozen=a.body(id).plan();points.get(1).set(50,50);points.clear();
            a.tick();assertEquals(position,before.position);assertNotSame(before.position,a.data.getComponent(id,Position.class).position);
            assertEquals(frozen,a.body(id).plan());
            assertEquals(6,a.body(id).targetX());
            assertEquals(3,frozen.path().size());assertEquals(new Waypoint(3,0),frozen.path().get(1));
            assertThrows(UnsupportedOperationException.class,()->frozen.path().clear());
        }
    }

    @Test void malformedMovementAndUnallocatedBindingsFailWithoutMutatingEntities() throws Exception {
        try(var a=new World()) {
            a.walk();a.tick();var state=a.state();var bytes=MovementSnapshots.encode(state);
            assertEquals(state,MovementSnapshots.decode(bytes));
            for(int i=0;i<bytes.length;i++){var cut=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->MovementSnapshots.decode(cut));}
            for(int offset:List.of(0,4)) {var bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,999);assertThrows(IOException.class,()->MovementSnapshots.decode(bad));}
            var badCount=bytes.clone();ByteBuffer.wrap(badCount).putInt(8,65537);assertThrows(IOException.class,()->MovementSnapshots.decode(badCount));
            var badBool=bytes.clone();badBool[badBool.length-1]=2;assertThrows(IOException.class,()->MovementSnapshots.decode(badBool));
            var nan=bytes.clone();ByteBuffer.wrap(nan).putFloat(nan.length-33,Float.NaN);assertThrows(IOException.class,()->MovementSnapshots.decode(nan));
            assertThrows(IOException.class,()->MovementSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
            var id=state.observed().getFirst();
            assertThrows(IllegalArgumentException.class,()->new MovementSnapshots.State(List.of(id,id),List.of(),List.of()));
            assertThrows(IllegalArgumentException.class,()->new MovementSnapshots.State(List.of(),List.of(id),List.of()));
            assertThrows(IllegalArgumentException.class,()->new MovementSnapshots.State(List.of(),List.of(),state.bodies()));
            assertThrows(IllegalArgumentException.class,()->new Plan(List.of(),Float.POSITIVE_INFINITY));
            assertThrows(IllegalArgumentException.class,()->new Waypoint(Float.NaN,0));
            assertThrows(IllegalArgumentException.class,()->new MovementSnapshots.State(List.of(id),List.of(),List.of()));
            var invalid=new MovementSnapshots.State(List.of(new EntityId(99999)),List.of(new EntityId(99999)),List.of());var before=EntitySnapshots.encode(a.data.capture());
            assertThrows(IllegalArgumentException.class,()->MovementSystem.restoreState(a.data,invalid));assertArrayEquals(before,EntitySnapshots.encode(a.data.capture()));
        }
    }

    static void step(World a,World b)throws Exception {a.tick();b.tick();assertEquals(a.state(),b.state());assertArrayEquals(EntitySnapshots.encode(a.data.capture()),EntitySnapshots.encode(b.data.capture()));}
    static final class World implements AutoCloseable {
        final GameEntityData data;MovementSystem movement;
        World(){data=new GameEntityData();movement=new MovementSystem(data);}
        World(GameEntityData data,MovementSnapshots.State state){this.data=data;movement=MovementSystem.restoreState(data,state);}
        EntityId entity(Navigation nav,float rotation){var id=data.createEntity();data.setComponents(id,new Position(rotation,new Vector3f(0,7,0)),new Mobile(false,false,false,2),nav);return id;}
        EntityId walk(){return entity(new Navigation(new Point(6,2),null,List.of(new Vector2(),new Vector2(3,0),new Vector2(6,2))),0);}
        void tick(){movement.processTick(1f/60);}
        MovementSnapshots.State state(){return movement.captureState();}
        MovementSnapshots.Body body(EntityId id){return state().bodies().stream().filter(b->b.id().equals(id)).findFirst().orElseThrow();}
        World copy()throws Exception {var bytes=EntitySnapshots.encode(data.capture());var state=MovementSnapshots.decode(MovementSnapshots.encode(state()));var w=new World(GameEntityData.restore(EntitySnapshots.decode(bytes)),state);assertEquals(state,w.state());assertArrayEquals(bytes,EntitySnapshots.encode(w.data.capture()));return w;}
        void reload()throws Exception {var bytes=EntitySnapshots.encode(data.capture());var state=MovementSnapshots.decode(MovementSnapshots.encode(state()));movement.stop();movement=MovementSystem.restoreState(data,state);assertEquals(state,state());assertArrayEquals(bytes,EntitySnapshots.encode(data.capture()));}
        public void close(){movement.stop();data.close();}
    }
}
