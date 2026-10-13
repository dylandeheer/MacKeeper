/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.logic.PositionSystem;
import toniarts.openkeeper.game.map.*;
import toniarts.openkeeper.game.navigation.NavigationService;
import toniarts.openkeeper.game.navigation.pathfinding.INavigable;
import toniarts.openkeeper.game.random.SimulationRandom;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.TriggerContinuationTest.proxy;

class PositionContinuationTest {
    @Test void pendingMovementMembershipAndSensingStayStaleUntilTheOrdinaryTick() throws Exception {
        try(var live=new World()) {
            assertEquals(Set.of(id(1)),live.positions.getSensedEntities(id(0)));
            live.move(id(1),5);live.move(id(2),4);live.data.removeComponent(id(3),Position.class);
            var added=live.data.createEntity();live.move(added,2);
            for(int i=0;i<4;i++) try(var restored=live.copy()) {
                compare(live,restored);
                assertEquals(new Point(2,1),restored.positions.getEntityLocation(id(1)).getLocation());
                assertEquals(new Point(5,1),restored.positions.getEntityLocation(id(3)).getLocation());
                assertNull(restored.positions.getEntityLocation(added));
                assertTrue(restored.positions.isTileBlocked(3,1,(short)3));
                assertFalse(restored.positions.isTileBlocked(4,1,(short)3));
                assertEquals(Set.of(id(1)),restored.positions.getSensedEntities(id(0)));
            }
            try(var restored=live.copy()) {
                live.positions.processTick(.25f);restored.positions.processTick(.25f);compare(live,restored);
                assertEquals(Set.of(added),restored.positions.getSensedEntities(id(0)));
                assertNull(restored.positions.getEntityLocation(id(3)));
                assertFalse(restored.positions.isTileBlocked(3,1,(short)3));assertTrue(restored.positions.isTileBlocked(4,1,(short)3));
                assertNotNull(restored.path(1,3));assertNull(restored.path(1,5));
            }
        }
    }

    @Test void cachedObstacleBitsSurviveChangedBlueprintsAndSameTilePositions() throws Exception {
        try(var live=new World()) {
            var blueprint=live.data.createEntity();live.move(blueprint,4);
            live.data.setComponents(blueprint,new DoorComponent((short)1,true,true),new Owner((short)3,(short)3));
            live.positions.processTick(.25f);
            live.data.setComponent(id(2),new DoorComponent((short)1,true,true));
            live.data.setComponent(blueprint,new DoorComponent((short)1,true,false));
            live.move(id(2),3);live.move(blueprint,4);
            try(var restored=live.copy()) {
                for(var w:List.of(live,restored)) {
                    assertTrue(w.positions.isTileBlocked(3,1,(short)3));assertFalse(w.positions.isTileBlocked(4,1,(short)3));
                    w.positions.processTick(.25f);
                    assertTrue(w.positions.isTileBlocked(3,1,(short)3));assertFalse(w.positions.isTileBlocked(4,1,(short)3));
                    w.move(id(2),4);w.move(blueprint,5);w.positions.processTick(.25f);
                    assertFalse(w.positions.isTileBlocked(4,1,(short)3));assertTrue(w.positions.isTileBlocked(5,1,(short)3));
                }
                compare(live,restored);
            }
        }
    }

    @Test void emptySensorsRemovedSensesAndMutableReadResultsRemainIndependent() throws Exception {
        try(var live=new World()) {
            var sensor=live.data.createEntity();live.data.setComponent(sensor,new Senses(1,1));
            assertTrue(live.positions.getSensedEntities(sensor).isEmpty());
            live.move(sensor,1);live.positions.getSensedEntities(id(0));
            live.data.removeComponent(id(0),Senses.class);
            var snapshot=live.positions.captureState();
            try(var a=live.copy();var b=live.copy()) {
                assertTrue(a.positions.getSensedEntities(id(0)).isEmpty());
                assertTrue(a.positions.getSensedEntities(sensor).isEmpty());
                a.data.setComponent(id(0),new Senses(0,0));
                assertEquals(Set.of(id(1)),a.positions.getSensedEntities(id(0)));
                a.positions.getSensedEntities(id(0)).clear();
                assertEquals(List.of(id(1)),snapshot.sensed().get(id(0)));
                assertEquals(snapshot,b.positions.captureState());
                assertThrows(UnsupportedOperationException.class,()->snapshot.sensed().get(id(0)).clear());
                a.positions.processTick(.25f);assertEquals(Set.of(id(0),id(1)),a.positions.getSensedEntities(sensor));
            }
        }
    }

    @Test void repeatedReloadsPreserveActualNavigationAcrossFutureMovesRemovalReadditionAndNewEntities() throws Exception {
        try(var live=new World()) {
            List<EntityId> movers=new ArrayList<>();
            for(int i=0;i<300;i++){var id=live.data.createEntity();movers.add(id);live.move(id,1+i%5);}
            live.positions.processTick(.125f);World restored=live.copy();
            try {
                for(int tick=0;tick<30;tick++) {
                    var target=movers.get(tick*7%300);
                    for(var w:List.of(live,restored)) {
                        w.positions.getSensedEntities(id(0));
                        // Last observed membership remains present through remove/re-add coalescing.
                        w.data.removeComponent(target,Position.class);w.move(target,1+tick%5);
                        w.move(id(2),2+tick%4);
                        w.data.setComponent(id(2),new DoorComponent((short)1,tick%3==0,false));
                        var born=w.data.createEntity();w.move(born,1+tick%5);
                        if(tick>2)w.data.removeEntity(movers.get(tick-3));
                    }
                    var next=restored.copy();restored.close();restored=next;
                    compare(live,restored);
                    assertEquals(live.navigation.findRandomAccessibleTile(new Point(1,1),4,Walker.INSTANCE),
                            restored.navigation.findRandomAccessibleTile(new Point(1,1),4,Walker.INSTANCE));
                    live.positions.processTick(.125f);restored.positions.processTick(.125f);compare(live,restored);
                    assertEquals(live.random.captureState(),restored.random.captureState());
                }
            } finally {restored.close();}
        }
    }

    @Test void invalidReferencesDimensionsAndCoordinatesFailBeforeMutatingTheStore() throws Exception {
        try(var live=new World()) {
            var state=live.positions.captureState();byte[] before=EntitySnapshots.encode(live.data.capture());
            var badId=id(100);
            List<PositionSnapshots.State> invalid=List.of(
                    new PositionSnapshots.State(7,4,state.observed(),state.sensed()),
                    new PositionSnapshots.State(6,4,Map.of(badId,new PositionSnapshots.Location(1,1,false)),Map.of()),
                    new PositionSnapshots.State(6,4,Map.of(),Map.of(badId,List.of())),
                    new PositionSnapshots.State(6,4,Map.of(),Map.of(id(0),List.of(badId))));
            for(var bad:invalid)assertThrows(IllegalArgumentException.class,()->PositionSystem.restoreState(live.map,live.data,live.creatures,live.doors,null,bad));
            assertArrayEquals(before,EntitySnapshots.encode(live.data.capture()));assertEquals(state,live.positions.captureState());
            assertThrows(IllegalArgumentException.class,()->new PositionSnapshots.State(0,4,Map.of(),Map.of()));
            assertThrows(IllegalArgumentException.class,()->new PositionSnapshots.State(65535,65535,Map.of(),Map.of()));
            assertThrows(IllegalArgumentException.class,()->new PositionSnapshots.State(6,4,Map.of(id(0),new PositionSnapshots.Location(6,1,false)),Map.of()));
            assertThrows(IllegalArgumentException.class,()->new PositionSnapshots.State(6,4,Map.of(),Map.of(id(0),List.of(id(1),id(1)))));
            assertThrows(IllegalArgumentException.class,()->new PositionSnapshots.State(6,4,Map.of(),Map.of(id(0),List.of(id(2),id(1)))));
        }
    }

    @Test void malformedSpatialSectionsRejectDuplicatesCountsBooleansAndTrailingData() throws Exception {
        var state=new PositionSnapshots.State(6,4,Map.of(id(0),new PositionSnapshots.Location(1,1,true)),Map.of(id(0),List.of(id(1))));
        byte[] bytes=PositionSnapshots.encode(state);assertEquals(state,PositionSnapshots.decode(bytes));
        for(int size=0;size<bytes.length;size++){var cut=Arrays.copyOf(bytes,size);assertThrows(IOException.class,()->PositionSnapshots.decode(cut));}
        assertThrows(IOException.class,()->PositionSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
        for(int offset:new int[]{0,4,8,12,16}){var bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class,()->PositionSnapshots.decode(bad));}
        var booleanBad=bytes.clone();booleanBad[36]=2;assertThrows(IOException.class,()->PositionSnapshots.decode(booleanBad));
        for(int variant=0;variant<4;variant++){var bad=corrupt(variant);assertThrows(IOException.class,()->PositionSnapshots.decode(bad));}
        assertThrows(IOException.class,()->PositionSnapshots.decode(new byte[32*1024*1024+1]));
    }

    private static byte[] corrupt(int variant) throws IOException {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeInt(0x4d4b5350);out.writeInt(1);out.writeInt(6);out.writeInt(4);out.writeInt(variant==0?2:0);
        if(variant==0)for(int i=0;i<2;i++){out.writeLong(0);out.writeInt(1);out.writeInt(1);out.writeBoolean(false);}
        out.writeInt(variant==3?65537:2);
        if(variant==1){for(int i=0;i<2;i++){out.writeLong(0);out.writeInt(0);}}
        if(variant==2){out.writeLong(0);out.writeInt(1_000_001);}
        return bytes.toByteArray();
    }
    private static EntityId id(long value){return new EntityId(value);}
    private static void compare(World a,World b)throws Exception {
        assertEquals(a.positions.captureState(),b.positions.captureState());
        for(int y=0;y<4;y++)for(int x=0;x<6;x++) {
            var expected=a.positions.getEntitiesInLocation(x,y);
            assertEquals(expected.stream().sorted().toList(),expected);assertEquals(expected,b.positions.getEntitiesInLocation(x,y));
            assertEquals(a.positions.isTileBlocked(x,y,(short)3),b.positions.isTileBlocked(x,y,(short)3));
        }
        assertEquals(a.positions.getSensedEntities(id(0)),b.positions.getSensedEntities(id(0)));
        assertEquals(a.path(1,5),b.path(1,5));assertEquals(a.path(1,3),b.path(1,3));
        assertArrayEquals(EntitySnapshots.encode(a.data.capture()),EntitySnapshots.encode(b.data.capture()));
    }
    enum Walker implements INavigable {
        INSTANCE;
        public short getOwnerId(){return 3;}public boolean canFly(){return false;}
        public boolean canWalkOnWater(){return false;}public boolean canWalkOnLava(){return false;}public boolean canMoveDiagonally(){return false;}
    }
    static final class World implements AutoCloseable {
        final GameEntityData data;final SimulationRandom random;final PositionSystem positions;
        final IMapController map;final ICreaturesController creatures;final IDoorsController doors;final NavigationService navigation;
        final IMapTileInformation[][] tiles=new IMapTileInformation[6][4];
        World()throws Exception{this(initialData(),new SimulationRandom(73),null);}
        World(GameEntityData data,SimulationRandom random,PositionSnapshots.State state) {
            this.data=data;this.random=random;
            var floor=new Terrain();floor.setFlags(EnumSet.noneOf(Terrain.TerrainFlag.class));var wall=new Terrain();wall.setFlags(EnumSet.of(Terrain.TerrainFlag.SOLID));
            for(int y=0;y<4;y++)for(int x=0;x<6;x++){final int tx=x,ty=y;tiles[x][y]=proxy(IMapTileInformation.class,(n,a)->switch(n){
                case "getX"->tx;case "getY"->ty;case "getIndex"->ty*6+tx;case "getLocation"->new Point(tx,ty);
                case "equals"->a[0]==tiles[tx][ty];case "hashCode"->ty*6+tx;case "toString"->tx+","+ty;default->throw new AssertionError(n);});}
            var grid=proxy(IMapData.class,(n,a)->switch(n){case "getWidth"->6;case "getHeight"->4;
                case "getTile"->{int x=a[0] instanceof Point p?p.x:(int)a[0],y=a[0] instanceof Point p?p.y:(int)a[1];yield x<0||x>=6||y<0||y>=4?null:tiles[x][y];}
                default->throw new AssertionError(n);});
            map=proxy(IMapController.class,(n,a)->switch(n){case "getMapData"->grid;case "getTerrain"->((IMapTileInformation)a[0]).getY()==1?floor:wall;default->throw new AssertionError(n);});
            creatures=proxy(ICreaturesController.class,(n,a)->{throw new AssertionError("Unexpected creature binding: "+n);});
            doors=proxy(IDoorsController.class,(n,a)->{throw new AssertionError("Unexpected door binding: "+n);});
            byte[] before;
            try {before=EntitySnapshots.encode(data.capture());}catch(IOException e){throw new UncheckedIOException(e);}
            positions=state==null?new PositionSystem(map,data,creatures,doors,null):PositionSystem.restoreState(map,data,creatures,doors,null,state);
            try {assertArrayEquals(before,EntitySnapshots.encode(data.capture()));}catch(IOException e){throw new UncheckedIOException(e);}
            navigation=new NavigationService(map,positions,random);
        }
        static GameEntityData initialData(){var d=new GameEntityData();for(int x:new int[]{1,2,3,5}){var e=d.createEntity();d.setComponent(e,position(x));}
            d.setComponent(id(0),new Senses(1,1));d.setComponents(id(2),new DoorComponent((short)1,true,false),new Owner((short)3,(short)3));return d;}
        static Position position(int x){return new Position(0,new com.jme3.math.Vector3f(x,0,1));}
        void move(EntityId id,int x){data.setComponent(id,position(x));}
        List<Point> path(int from,int to){var result=navigation.findPath(new Point(from,1),new Point(to,1),Walker.INSTANCE);if(result==null)return null;var points=new ArrayList<Point>();for(var tile:result)points.add(tile.getLocation());return points;}
        World copy()throws Exception{return new World(GameEntityData.restore(EntitySnapshots.decode(EntitySnapshots.encode(data.capture()))),SimulationRandom.restore(random.captureState()),PositionSnapshots.decode(PositionSnapshots.encode(positions.captureState())));}
        public void close(){positions.stop();data.close();}
    }
}
