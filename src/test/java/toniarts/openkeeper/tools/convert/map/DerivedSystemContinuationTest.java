/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.CreatureState;
import toniarts.openkeeper.game.controller.player.PlayerRoomControl;
import toniarts.openkeeper.game.controller.room.*;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.game.controller.room.storage.IRoomObjectControl;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.logic.*;
import toniarts.openkeeper.game.map.*;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.proxy;

class DerivedSystemContinuationTest {
    static final EntityId VAULT=new EntityId(3), CARRIER=new EntityId(4), CARGO=new EntityId(5), DOOR=new EntityId(6), GOLD_A=new EntityId(7), GOLD_B=new EntityId(8), BOOK=new EntityId(9), SECOND_BOOK=new EntityId(10);

    @Test void creatureViewClockAndPendingMembershipPreserveFutureAnimationTransitions() throws Exception {
        try(var a=new World()) {
            a.tick(.5f);a.data.setComponent(CARRIER,new Navigation(new Point(1,0),null,List.of()));
            try(var b=a.copy()) {
                b.reload();step(a,b,.25f);assertEquals(Creature.AnimationType.WALK,a.view().state);assertEquals(.75,a.view().stateStartTime);
                b.reload();for(var w:List.of(a,b)){w.data.removeComponent(CARRIER,Navigation.class);w.data.setComponent(CARRIER,new Unconscious(.75));}
                step(a,b,.25f);assertEquals(Creature.AnimationType.DIE,a.view().state);assertEquals(1,a.view().stateStartTime);
                for(var w:List.of(a,b))w.data.removeComponent(CARRIER,Position.class);
                b.reload();step(a,b,.25f);assertEquals(1,a.view().stateStartTime);
                for(var w:List.of(a,b)){w.data.setComponent(CARRIER,new Position(0,new Vector3f(4,2,0)));w.data.setComponent(CARRIER,new Death(1.25));}
                b.reload();step(a,b,.25f);assertEquals(Creature.AnimationType.DEATH_POSE,a.view().state);assertEquals(1.5,a.view().stateStartTime);
            }
        }
    }

    @Test void doorQueriesRebuildWithPendingLocksOwnersAndCreatureMovement() throws Exception {
        try(var a=new World()) {
            a.tick(.5f);assertTrue(a.door().open);a.data.setComponent(CARRIER,new Owner((short)4,(short)4));
            try(var b=a.copy()) {
                b.reload();step(a,b,.25f);assertFalse(a.door().open);
                for(var w:List.of(a,b)){w.data.setComponent(CARRIER,new Owner((short)3,(short)3));w.data.setComponent(DOOR,new DoorComponent((short)1,true,false));}
                b.reload();step(a,b,.25f);assertFalse(a.door().open);
                for(var w:List.of(a,b))w.data.setComponent(DOOR,new DoorComponent((short)1,false,false));
                b.reload();step(a,b,.25f);assertTrue(a.door().open);
                b.reload();for(var w:List.of(a,b))w.data.setComponent(CARRIER,new Position(0,new Vector3f(9,2,0)));
                step(a,b,.25f);assertFalse(a.door().open);
            }
        }
    }

    @Test void haulingRebuildPreservesHeightAndHandlesPendingMissingOrReplacedHaulers() throws Exception {
        try(var a=new World()) {
            a.tick(.5f);assertEquals(new Vector3f(4,3,0),a.data.getComponent(CARGO,Position.class).position);
            a.data.setComponent(CARRIER,new Position(.7f,new Vector3f(5,8,2)));
            try(var b=a.copy()) {
                b.reload();step(a,b,.25f);assertEquals(new Vector3f(5,3,2),a.data.getComponent(CARGO,Position.class).position);
                b.reload();for(var w:List.of(a,b))w.data.removeComponent(CARRIER,Position.class);
                step(a,b,.25f);assertNull(a.data.getComponent(CARGO,HauledBy.class));
                for(var w:List.of(a,b)){w.data.setComponent(CARGO,new HauledBy(DOOR));w.data.setComponent(DOOR,new Position(0,new Vector3f(7,1,0)));}
                b.reload();step(a,b,.25f);assertEquals(new Vector3f(7,3,0),a.data.getComponent(CARGO,Position.class).position);
            }
        }
    }

    @Test void moodRebuildRetainsCountersAndUsesCurrentBedMembership() throws Exception {
        try(var a=new World()) {
            a.tick(.5f);assertTrue(a.data.getComponent(CARRIER,CreatureMood.class).noLair>0);
            a.data.setComponent(CARRIER,new CreatureSleep(CARGO,0,0));
            try(var b=a.copy()) {
                b.reload();step(a,b,.25f);assertEquals(0,a.data.getComponent(CARRIER,CreatureMood.class).noLair);
                b.reload();for(var w:List.of(a,b))w.data.removeEntity(CARGO);
                step(a,b,.25f);assertTrue(a.data.getComponent(CARRIER,CreatureMood.class).noLair>0);
                for(var w:List.of(a,b))w.data.removeComponent(CARRIER,CreatureSleep.class);
                int anger=a.data.getComponent(CARRIER,CreatureMood.class).noLair;
                b.reload();step(a,b,.25f);assertEquals(anger,a.data.getComponent(CARRIER,CreatureMood.class).noLair);
            }
        }
    }

    @Test void looseObjectRebuildPreservesStorageOrderPartialGoldAndPendingRemoval() throws Exception {
        try(var a=new World()) {
            // Copy before the first query update; binding must not deposit anything.
            try(var b=a.copy()) {
                b.reload();assertEquals(20,b.data.getComponent(VAULT,Gold.class).gold);
                step(a,b,.25f);assertEquals(50,a.data.getComponent(VAULT,Gold.class).gold);
                assertNull(a.data.getComponent(GOLD_A,Gold.class));assertEquals(10,a.data.getComponent(GOLD_B,Gold.class).gold);
                assertNotNull(a.data.getComponent(BOOK,Stored.class));assertNull(a.data.getComponent(SECOND_BOOK,Stored.class));
                b.reload();step(a,b,.25f);assertEquals(List.of("gold:20","gold:10","book:9"),a.events);
                for(var w:List.of(a,b)){w.data.removeEntity(BOOK);w.data.setComponent(VAULT,new Gold(0,50));}
                b.reload();step(a,b,.25f);assertEquals(10,a.data.getComponent(VAULT,Gold.class).gold);assertNull(a.data.getComponent(GOLD_B,Gold.class));
                assertNotNull(a.data.getComponent(SECOND_BOOK,Stored.class));
                b.reload();step(a,b,.25f);assertEquals(2,a.events.stream().filter(e->e.startsWith("book:")).count());
            }
        }
    }

    @Test void bridgeBindingKeepsExistingDeadlinesAndIntentionalAbsenceWithoutNewInitialization() throws Exception {
        try(var a=new World()) {
            assertEquals(5,a.data.getComponent(new EntityId(0),WoodenBridgeDecay.class).endTime);
            assertEquals(7,a.data.getComponent(new EntityId(2),WoodenBridgeDecay.class).endTime);
            a.tick(1);a.data.removeComponent(new EntityId(0),WoodenBridgeDecay.class);
            try(var b=a.copy()) {
                b.reload();assertNull(b.data.getComponent(new EntityId(0),WoodenBridgeDecay.class));step(a,b,5);
                assertNotNull(a.data.getComponent(new EntityId(2),WoodenBridgeDecay.class));
                b.reload();step(a,b,1);assertNull(a.data.getComponent(new EntityId(2),WoodenBridgeDecay.class));assertTrue(a.events.contains("bridge:2"));
                for(var w:List.of(a,b)){w.data.setComponent(new EntityId(0),new WoodenBridgeDecay(8));w.data.setComponent(new EntityId(1),new WoodenBridgeDecay(8));}
                b.reload();step(a,b,1);assertEquals(List.of("bridge:0","bridge:1"),a.events.subList(a.events.size()-2,a.events.size()));
                b.reload();step(a,b,1);assertEquals(3,a.events.stream().filter(e->e.startsWith("bridge:")).count());
            }
        }
    }

    @Test void viewClockCodecRejectsMalformedValues() throws Exception {
        var clock=new LifetimeSnapshots.Clock(73.125);var bytes=CreatureViewSnapshots.encode(clock);assertEquals(clock,CreatureViewSnapshots.decode(bytes));
        for(int i=0;i<bytes.length;i++){var cut=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->CreatureViewSnapshots.decode(cut));}
        assertThrows(IOException.class,()->CreatureViewSnapshots.decode(Arrays.copyOf(bytes,17)));
        var header=bytes.clone();header[0]=0;assertThrows(IOException.class,()->CreatureViewSnapshots.decode(header));
        var version=bytes.clone();version[7]=99;assertThrows(IOException.class,()->CreatureViewSnapshots.decode(version));
        for(double value:List.of(-1d,Double.NaN,Double.POSITIVE_INFINITY)) {var bad=bytes.clone();java.nio.ByteBuffer.wrap(bad).putDouble(8,value);assertThrows(IOException.class,()->CreatureViewSnapshots.decode(bad));}
    }

    static void step(World a,World b,float time)throws Exception {a.tick(time);b.tick(time);assertEquals(a.views.captureTime(),b.views.captureTime());assertEquals(a.clock.captureState(),b.clock.captureState());assertEquals(a.events,b.events);assertArrayEquals(EntitySnapshots.encode(a.data.capture()),EntitySnapshots.encode(b.data.capture()));}
    static final class World implements AutoCloseable {
        final GameEntityData data;GameTimeController clock=new GameTimeController();final List<String> events=new ArrayList<>();
        final IKwdFile kwd;final IMapController map;final IEntityPositionLookup lookup;final IGameWorldController world;final Map<Short,IPlayerController> players;
        CreatureViewSystem views;WoodenBridgeDecaySystem bridges;CreatureMoodSystem mood;DoorViewSystem doors;LooseObjectSystem loose;HaulingSystem hauling;
        World(){this(new GameEntityData());
            for(int i=0;i<3;i++){var id=data.createEntity();data.setComponent(id,new MapTile(0,(short)8,i==1?Tile.BridgeTerrainType.WATER:Tile.BridgeTerrainType.LAVA,new Point(i,0),i));}
            data.setComponent(new EntityId(2),new WoodenBridgeDecay(7));
            var vault=data.createEntity();data.setComponent(vault,new Gold(20,50));
            var carrier=data.createEntity();var c=new CreatureComponent();c.creatureId=7;
            data.setComponents(carrier,c,new Position(0,new Vector3f(4,2,0)),new Owner((short)3,(short)3),new CreatureSleep(null,0,0),new CreatureMood(),new CreatureAi(0,CreatureState.IDLE,(short)7),new CreatureViewState((short)7,0,Creature.AnimationType.IDLE_1));
            var cargo=data.createEntity();data.setComponents(cargo,new Position(.5f,new Vector3f(0,3,0)),new HauledBy(carrier));
            var door=data.createEntity();data.setComponents(door,new DoorComponent((short)1,false,false),new DoorViewState((short)1,false,false,false),new Position(0,new Vector3f(4,0,0)),new Owner((short)3,(short)3));
            for(int i=0;i<4;i++){var id=data.createEntity();data.setComponents(id,new ObjectComponent((short)1,i<2?ObjectType.GOLD:ObjectType.SPELL_BOOK),new Position(0,new Vector3f()),new Placeable());if(i<2)data.setComponent(id,new Gold(20,20));}
            views=new CreatureViewSystem(data);bridges=new WoodenBridgeDecaySystem(data,clock,world,kwd,map,5);bindQueries();
        }
        World(GameEntityData data){this.data=data;
            var room=new Room();room.setRoomId((short)8);var lair=new Room();lair.setRoomId((short)2);lair.setFlags(EnumSet.of(Room.RoomFlag.BUILDABLE));var terrain=new Terrain();terrain.setTerrainId((short)8);
            var creature=new Creature();creature.setCreatureId((short)7);creature.setLairObjectId((short)1);creature.getAttributes().setAngerNoLair((short)8);creature.getAttributes().setUnhappyThreshold((short)100);
            kwd=proxy(IKwdFile.class,(name,args)->switch(name){case "getTerrainList"->List.of(terrain);case "getRoomByTerrain"->room;case "getRoomById"->lair;case "getCreature"->creature;default->throw new AssertionError(name);});
            var keeper=new Keeper(false,(short)3);var rooms=new PlayerRoomControl(keeper,List.of(lair));rooms.setTypeAvailable(lair,true,true);
            players=Map.of((short)3,proxy(IPlayerController.class,(name,args)->switch(name){case "getKeeper"->keeper;case "getRoomControl"->rooms;default->throw new AssertionError(name);}));
            IMapDataInformation<IMapTileInformation> grid=proxy(IMapDataInformation.class,(name,args)->switch(name){case "getWidth"->3;case "getHeight"->1;case "getTile"->tile(new Point((int)args[0],(int)args[1]));default->throw new AssertionError(name);});
            var storage=proxy(IRoomObjectControl.class,(name,args)->{if(name.equals("isFullCapacity"))return !data.findEntities(null,Stored.class).isEmpty();if(name.equals("addItem")){var id=(EntityId)args[0];events.add("book:"+id.getId());data.setComponent(id,new Stored(new EntityId(0),ObjectType.SPELL_BOOK));return null;}throw new AssertionError(name);});
            var goldStorage=proxy(IRoomObjectControl.class,(name,args)->{if(name.equals("isFullCapacity"))return data.getComponent(VAULT,Gold.class).gold>=50;throw new AssertionError(name);});
            var store=proxy(IRoomController.class,(name,args)->switch(name){case "hasObjectControl"->true;case "getObjectControl"->args[0]==ObjectType.GOLD?goldStorage:storage;case "isRemoved","isDestroyed"->false;case "getOwnerId"->(short)3;default->throw new AssertionError(name);});
            map=proxy(IMapController.class,(name,args)->switch(name){case "getMapData"->grid;case "getRoomControllerByCoordinates"->((Point)args[0]).x==0?store:null;default->throw new AssertionError(name);});
            lookup=proxy(IEntityPositionLookup.class,(name,args)->{
                if(name.equals("getEntityLocation")){var p=data.getComponent((EntityId)args[0],Position.class);return p==null?null:tile(new Point(Math.round(p.position.x),Math.round(p.position.z)));}
                if(name.equals("getEntitiesInLocation")){var p=(Point)args[0];return data.findEntities(null,Position.class).stream().filter(id->{var at=data.getComponent(id,Position.class).position;return Math.round(at.x)==p.x&&Math.round(at.z)==p.y;}).sorted().toList();}throw new AssertionError(name);});
            world=proxy(IGameWorldController.class,(name,args)->{
                if(name.equals("addGold")){var gold=data.getComponent(VAULT,Gold.class);int amount=(int)args[2],accepted=Math.min(amount,50-gold.gold);data.setComponent(VAULT,new Gold(gold.gold+accepted,50));events.add("gold:"+accepted);return amount-accepted;}
                if(name.equals("destroyRoomTiles")){for(var p:(List<Point>)args[0])events.add("bridge:"+p.x);return null;}throw new AssertionError(name);});
        }
        IMapTileInformation tile(Point point){return proxy(IMapTileInformation.class,(name,args)->switch(name){case "getOwnerId"->(short)(point.x==9?4:3);case "getLocation"->point;case "getEntityId"->new EntityId(point.x);case "getTerrainId"->data.getComponent(new EntityId(point.x),MapTile.class).terrainId;case "getBridgeTerrainType"->data.getComponent(new EntityId(point.x),MapTile.class).bridgeTerrainType;default->throw new AssertionError(name);});}
        void bindQueries(){mood=new CreatureMoodSystem(data,kwd,players.values());doors=new DoorViewSystem(data,lookup);loose=new LooseObjectSystem(data,map,players,lookup,world);hauling=new HaulingSystem(data);}
        CreatureViewState view(){return data.getComponent(CARRIER,CreatureViewState.class);}DoorViewState door(){return data.getComponent(DOOR,DoorViewState.class);}
        void tick(float time){clock.processTick(time);bridges.processTick(time);mood.processTick(time);views.processTick(time);doors.processTick(time);loose.processTick(time);hauling.processTick(time);}
        void bind(LifetimeSnapshots.Clock view){views=CreatureViewSystem.restoreState(data,view);bridges=WoodenBridgeDecaySystem.restoreBindings(data,clock,world);bindQueries();}
        World copy()throws Exception {var result=new World(GameEntityData.restore(EntitySnapshots.decode(EntitySnapshots.encode(data.capture()))));result.clock=new GameTimeController(clock.captureState());result.events.addAll(events);var before=EntitySnapshots.encode(result.data.capture());result.bind(CreatureViewSnapshots.decode(CreatureViewSnapshots.encode(views.captureTime())));assertArrayEquals(before,EntitySnapshots.encode(result.data.capture()));assertEquals(events,result.events);return result;}
        void reload()throws Exception {var saved=CreatureViewSnapshots.decode(CreatureViewSnapshots.encode(views.captureTime()));var before=EntitySnapshots.encode(data.capture());var previous=List.copyOf(events);stop();bind(saved);assertArrayEquals(before,EntitySnapshots.encode(data.capture()));assertEquals(previous,events);}
        void stop(){views.stop();bridges.stop();mood.stop();doors.stop();loose.stop();hauling.stop();}
        public void close(){stop();data.close();}
    }
}
