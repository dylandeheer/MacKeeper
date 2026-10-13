/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.player.PlayerManufacturingControl;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.proxy;

class ManufacturingQueueTest {
    private static final short PLAYER = 3;
    private static IKwdFile definitions() {
        var door = new Door();door.setDoorId((short)1);door.setManufToBuild(100);door.setManufCrateObjectId((short)52);
        var trap = new Trap();trap.setTrapId((short)2);trap.setManufToBuild(200);trap.setManufCrateObjectId((short)42);
        var crate = new GameObject();crate.setFlags(EnumSet.of(GameObject.ObjectFlag.OBJECT_TYPE_CRATE));
        return proxy(IKwdFile.class, (name,args) -> switch(name) {
            case "getDoorById" -> ((Number)args[0]).intValue()==1 ? door : null;
            case "getTrapById" -> ((Number)args[0]).intValue()==2 ? trap : null;
            case "getObject" -> Set.of(42,52).contains(((Number)args[0]).intValue()) ? crate : null;
            default -> null;
        });
    }
    private static PlayerManufacturingControl control(GameEntityData data, short player) {
        return new PlayerManufacturingControl(player, definitions(), data);
    }
    private static EntityId blueprint(GameEntityData data, short owner, boolean trap) {
        var id=data.createEntity();data.setComponent(id,new Owner(owner,owner));
        if(trap) data.setComponents(id,new TrapComponent((short)2),new TrapViewState((short)2,true));
        else data.setComponent(id,new DoorComponent((short)1,false,true));
        return id;
    }
    private static void enqueue(GameEntityData data, PlayerManufacturingControl queue, boolean trap) {
        blueprint(data,PLAYER,trap);assertTrue(queue.request(trap,(short)(trap?2:1),new Point(4,5)));
    }
    private static void crate(GameEntityData data, short owner, short type) {
        data.setComponents(data.createEntity(),new Owner(owner,owner),new ObjectComponent(type,null));
    }
    @Test void sharedHeadWorkIsBoundedAndCompletionRequiresTheSameReadyOrder() {
        var data=new GameEntityData();
        try {
            var queue=control(data,PLAYER);var other=control(data,(short)4);
            assertNull(queue.contribute(10));assertFalse(queue.hasWork());
            enqueue(data,queue,false);enqueue(data,queue,true);
            var head=queue.getOrders().getFirst();
            assertFalse(queue.complete(head.id()));assertNull(other.contribute(500));
            assertEquals(40,queue.contribute(40).completedWork());
            // A second controller represents another workshop contributing to the same owner.
            assertEquals(70,control(data,PLAYER).contribute(30).completedWork());
            assertTrue(queue.contribute(Integer.MAX_VALUE).isComplete());
            assertEquals(2,queue.getOrders().size());
            assertEquals(0,queue.getOrders().getLast().completedWork());
            assertFalse(queue.complete(queue.getOrders().getLast().id()));
            assertTrue(queue.complete(head.id()));assertFalse(queue.complete(head.id()));
            assertEquals(0,queue.getOrders().getFirst().completedWork());
            assertThrows(IllegalArgumentException.class,()->queue.contribute(-1));
        } finally {data.close();}
    }
    @Test void cancellationRemovesFirstMatchingTypeAndOnlyDiscardsHeadWorkWhenHeadWasRemoved() {
        var data=new GameEntityData();
        try {
            var queue=control(data,PLAYER);
            enqueue(data,queue,false);enqueue(data,queue,true);enqueue(data,queue,false);
            var ids=queue.getOrders().stream().map(PlayerManufacturingControl.Order::id).toList();
            queue.contribute(45);
            assertTrue(queue.cancel(true,(short)2));assertFalse(queue.cancel(true,(short)2));
            assertEquals(45,queue.getOrders().getFirst().completedWork());
            assertTrue(queue.cancel(false,(short)1));
            assertEquals(ids.getLast(),queue.getOrders().getFirst().id());
            assertEquals(0,queue.getOrders().getFirst().completedWork());
        } finally {data.close();}
    }
    @Test void onlyOwnedMatchingCratesSatisfyBlueprintDemandAndEmbeddedWeaponsAreExcluded() {
        var data=new GameEntityData();
        try {
            var queue=control(data,PLAYER);
            assertFalse(queue.request(false,(short)1,new Point(4,5)));
            crate(data,(short)4,(short)52);crate(data,PLAYER,(short)42);
            enqueue(data,queue,false);assertEquals(1,queue.getOrders().size());
            crate(data,PLAYER,(short)52);
            assertTrue(queue.request(false,(short)1,new Point(4,5)));
            assertEquals(1,queue.getOrders().size());
            enqueue(data,queue,false);assertEquals(2,queue.getOrders().size());
            var embedded=blueprint(data,PLAYER,false);
            data.setComponents(embedded,new TrapComponent((short)2),new TrapViewState((short)2,true));
            assertFalse(queue.request(true,(short)2,new Point(4,5)));
            blueprint(data,(short)4,true);
            assertFalse(queue.request(true,(short)2,new Point(4,5)));
            enqueue(data,queue,true);assertEquals(2,queue.getOrders().size());
        } finally {data.close();}
    }
    @Test void queueCapacityPrecedesEvenStockSatisfiedRequestsAndFailuresDoNotAllocate() throws Exception {
        var data=new GameEntityData();
        try {
            var queue=control(data,PLAYER);
            for(int i=0;i<PlayerManufacturingControl.MAX_ORDERS;i++) enqueue(data,queue,false);
            blueprint(data,PLAYER,true);crate(data,PLAYER,(short)42);
            var before=EntitySnapshots.encode(data.capture());
            assertFalse(queue.request(true,(short)2,new Point(4,5)));
            assertFalse(queue.request(false,(short)99,new Point(4,5)));
            assertFalse(queue.request(false,(short)1,null));
            assertFalse(queue.request(false,(short)1,new Point(-1,5)));
            assertArrayEquals(before,EntitySnapshots.encode(data.capture()));
            assertTrue(queue.cancel(false,(short)1));
            assertTrue(queue.request(true,(short)2,new Point(4,5)));
            assertEquals(127,queue.getOrders().size());
        } finally {data.close();}
    }
    @Test void archiveRoundTripPreservesOrderWorkLocationsAndIdenticalContinuation() throws Exception {
        var data=new GameEntityData();
        try {
            var queue=control(data,PLAYER);enqueue(data,queue,false);enqueue(data,queue,true);queue.contribute(60);
            byte[] saved=EntitySnapshots.encode(data.capture());
            var restored=GameEntityData.restore(EntitySnapshots.decode(saved));
            try {
                var resumed=control(restored,PLAYER);
                assertEquals(queue.getOrders(),resumed.getOrders());
                assertArrayEquals(saved,EntitySnapshots.encode(restored.capture())); // Binding has no side effects.
                for(var q:List.of(queue,resumed)) {
                    assertTrue(q.contribute(50).isComplete());
                    var ready=q.getOrders().getFirst();
                    assertTrue(q.complete(ready.id()));
                    assertEquals(199,q.contribute(199).completedWork());
                }
                assertArrayEquals(EntitySnapshots.encode(data.capture()),EntitySnapshots.encode(restored.capture()));
            } finally {restored.close();}
        } finally {data.close();}
    }
    @Test void ownerValidationRejectsOrphansEvenWhenNoControllerWouldSelectThem() {
        var data=new GameEntityData();
        try {
            var id=data.createEntity();
            for(short owner:new short[]{-1,256,4}) {
                data.setComponent(id,new ManufacturingOrder(owner,false,(short)1,100,0,new Point(4,5)));
                assertThrows(IllegalArgumentException.class,()->PlayerManufacturingControl.validateOwners(data,Set.of(PLAYER)));
            }
            data.setComponent(id,new ManufacturingOrder(PLAYER,false,(short)1,100,0,new Point(4,5)));
            assertDoesNotThrow(()->PlayerManufacturingControl.validateOwners(data,Set.of(PLAYER)));
        } finally {data.close();}
    }
    @Test void bindingRejectsInvalidProgressDefinitionsAndOverfullQueues() {
        var data=new GameEntityData();
        try {
            var queue=control(data,PLAYER);enqueue(data,queue,false);enqueue(data,queue,true);
            var second=queue.getOrders().getLast();
            data.setComponent(second.id(),new ManufacturingOrder(PLAYER,true,(short)2,200,1,new Point(4,5)));
            assertThrows(IllegalArgumentException.class,()->control(data,PLAYER));
            data.setComponent(second.id(),new ManufacturingOrder(PLAYER,true,(short)2,201,0,new Point(4,5)));
            assertThrows(IllegalArgumentException.class,()->control(data,PLAYER));
            data.setComponent(second.id(),new ManufacturingOrder(PLAYER,true,(short)99,200,0,new Point(4,5)));
            assertThrows(IllegalArgumentException.class,()->control(data,PLAYER));
            data.removeEntity(second.id());
            var first=queue.getOrders().getFirst();
            data.setComponent(first.id(),new ManufacturingOrder(PLAYER,false,(short)1,100,101,new Point(4,5)));
            assertThrows(IllegalArgumentException.class,()->control(data,PLAYER));
            data.setComponent(first.id(),new ManufacturingOrder(PLAYER,false,(short)1,100,0,new Point(4,5)));
            for(int i=0;i<128;i++) data.setComponent(data.createEntity(),new ManufacturingOrder(PLAYER,false,(short)1,100,0,new Point(4,5)));
            assertThrows(IllegalArgumentException.class,()->control(data,PLAYER));
        } finally {data.close();}
    }
}
