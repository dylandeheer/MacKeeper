/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.*;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.map.*;
import toniarts.openkeeper.game.navigation.NavigationService;
import toniarts.openkeeper.game.navigation.pathfinding.INavigable;
import toniarts.openkeeper.game.random.SimulationRandom;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.task.ITaskManager;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.utils.Utils;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.TriggerContinuationTest.proxy;

class RandomContinuationTest {
    @Test void fixedAlgorithmVectorsAndAllSignedStateWordsSurviveTheCodec() throws Exception {
        var random=new SimulationRandom(0);
        for(long expected:new long[]{0xe220a8397b1dcdafL,0x6e789e6aa1b965f4L,0x06c45d188009454fL,0xf88bb8a8724c81ecL})assertEquals(expected,random.nextLong());
        for(long seed:new long[]{0,-1,Long.MIN_VALUE,Long.MAX_VALUE}) {
            var state=new SimulationRandom.State(seed);assertEquals(state,RandomSnapshots.decode(RandomSnapshots.encode(state)));
        }
    }

    @Test void repeatedRestorePreservesMixedNumericDrawsIncludingRejectionBounds() throws Exception {
        var a=new SimulationRandom(-731);var b=SimulationRandom.restore(a.captureState());
        for(int i=0;i<2000;i++) {
            b=SimulationRandom.restore(RandomSnapshots.decode(RandomSnapshots.encode(b.captureState())));
            int bound=switch(i%4){case 0->1;case 1->16;case 2->1073741825;default->Integer.MAX_VALUE;};
            int value=a.nextInt(bound);assertTrue(value>=0&&value<bound);assertEquals(value,b.nextInt(bound));
            assertEquals(a.nextLong(),b.nextLong());assertEquals(a.nextDouble(),b.nextDouble());assertEquals(a.nextFloat(),b.nextFloat());
            float limit=i%2==0?Float.MIN_VALUE:Float.MAX_VALUE;float f=a.nextFloat(limit);assertTrue(f>=0&&f<limit);assertEquals(f,b.nextFloat(limit));
            assertEquals(a.captureState(),b.captureState());
        }
        var saved=a.captureState();assertThrows(IllegalArgumentException.class,()->a.nextInt(0));
        for(float invalid:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->a.nextFloat(invalid));
        assertEquals(saved,a.captureState());
    }

    @Test void malformedRandomSectionsFailWithoutAcceptingOtherAlgorithmsOrTrailingData() throws Exception {
        var bytes=RandomSnapshots.encode(new SimulationRandom.State(731));
        for(int i=0;i<16;i++){var cut=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->RandomSnapshots.decode(cut));}
        for(int offset:List.of(0,4)){var bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,2);assertThrows(IOException.class,()->RandomSnapshots.decode(bad));}
        assertThrows(IOException.class,()->RandomSnapshots.decode(Arrays.copyOf(bytes,17)));
    }

    @Test void jobsLeaderSelectionNavigationGoldScatterAndTexturesContinueTogetherDespiteVisualDraws() throws Exception {
        World a=new World();World b=null;
        try {
            for(int i=0;i<7;i++)a.step();b=a.copy();var jobs=new HashSet<Creature.JobType>();var leaders=new HashSet<EntityId>();var destinations=new HashSet<Point>();
            for(int i=0;i<80;i++) {
                var next=b.copy();b.close();b=next;
                var expected=a.step();jobs.add(expected.job());leaders.add(expected.leader());destinations.add(expected.destination());
                // UI/audio/effects and an unrelated running world must not consume this saved stream.
                for(int j=0;j<23;j++){Utils.getRandom().nextLong();com.jme3.math.FastMath.rand.nextDouble();}
                var unrelated=new SimulationRandom(i);for(int j=0;j<19;j++)unrelated.nextInt(7);
                assertEquals(expected,b.step());assertEquals(a.random.captureState(),b.random.captureState());
                assertArrayEquals(EntitySnapshots.encode(a.data.capture()),EntitySnapshots.encode(b.data.capture()));
            }
            assertEquals(2,jobs.size());assertEquals(2,leaders.size());assertTrue(destinations.size()>4);
        } finally {a.close();if(b!=null)b.close();}
    }

    @Test void emptySelectionsDoNotDrawAndRestoredFactoriesRejectMissingRandomDependencies() {
        var random=new SimulationRandom(3);var before=random.captureState();
        assertNull(Utils.getRandomItem(List.of(),random));assertTrue(Utils.getRandomItem((Collection<Object>)List.of(),random).isEmpty());assertEquals(before,random.captureState());
        assertThrows(NullPointerException.class,()->ObjectsController.restoreBindings(null,null,Map.of(),()->0,null,null,null));
        assertThrows(NullPointerException.class,()->CreaturesController.restoreBindings(null,null,Map.of(),()->0,null,null,null,null));
    }

    record Choice(Creature.JobType job,EntityId leader,Point destination,int texture) { }
    static final class Selected extends RuntimeException {final Creature.JobType job;Selected(Creature.JobType job){this.job=job;}}
    static final class World implements AutoCloseable {
        final GameEntityData data;final SimulationRandom random;final ObjectsController objects;final CreaturesController creatures;
        final NavigationService navigation;final IMapController map;final IMapTileInformation textureTile;final Terrain terrain=new Terrain();
        final INavigable navigable=proxy(INavigable.class,(n,a)->{if(n.equals("getCost"))return 1f;throw new AssertionError(n);});
        final Thing.HeroParty party=PartySnapshotsTest.definition((short)3,3);
        final List<PartySnapshotsTest.Member> members=List.of(new PartySnapshotsTest.Member(10,true),new PartySnapshotsTest.Member(11,false),new PartySnapshotsTest.Member(12,false));
        World()throws Exception {this(initialData(),new SimulationRandom(731));}
        World(GameEntityData data,SimulationRandom random) {
            this.data=data;this.random=random;terrain.setFlags(EnumSet.of(Terrain.TerrainFlag.RANDOM_TEXTURE));terrain.setTextureFrames((short)7);
            var mapData=proxy(IMapData.class,(n,a)->switch(n){case "getWidth","getHeight"->5;case "getTile"->{int x=(int)a[0],y=(int)a[1];yield x<0||y<0||x>=5||y>=5?null:tile(x,y);}default->throw new AssertionError(n);});
            map=proxy(IMapController.class,(n,a)->{if(n.equals("getMapData"))return mapData;throw new AssertionError(n);});textureTile=tile(0,0);
            navigation=new NavigationService(map,null,random);
            var creature=new Creature();creature.setCreatureId((short)2);var jobs=new Creature.JobPreference[2];
            for(int i=0;i<2;i++){jobs[i]=new Creature.JobPreference();jobs[i].setJobType(i==0?Creature.JobType.RESEARCH:Creature.JobType.TRAIN);jobs[i].setChance((short)(i==0?25:75));}creature.setHappyJobs(jobs);
            var gold=new GameObject();gold.setObjectId((short)1);gold.setFlags(EnumSet.of(GameObject.ObjectFlag.OBJECT_TYPE_GOLD));
            var kwd=proxy(IKwdFile.class,(n,a)->switch(n){case "getThings"->List.of();case "getCreature"->creature;case "getObject"->gold;default->throw new AssertionError(n);});
            var tasks=proxy(ITaskManager.class,(n,a)->switch(n){case "isTaskAvailable"->true;case "assignTask"->throw new Selected((Creature.JobType)a[1]);default->throw new AssertionError(n);});
            var world=proxy(IGameWorldController.class,(n,a)->switch(n){case "getMapController"->map;case "getObjectsController"->objects();case "getShotsController"->null;default->throw new AssertionError(n);});
            var game=proxy(IGameController.class,(n,a)->switch(n){case "getGameWorldController"->world;case "getTaskManager"->tasks;case "getNavigationService"->navigation;case "getEntityLookupService"->null;default->throw new AssertionError(n);});
            objects=ObjectsController.restoreBindings(kwd,data,Map.of(),()->0,game,null,random);
            creatures=CreaturesController.restoreBindings(kwd,data,Map.of(),()->0,game,map,null,random);
            assertSame(random,objects.getRandom());assertSame(random,creatures.getRandom());
        }
        IObjectsController objects(){return objects;}
        static GameEntityData initialData(){var data=new GameEntityData();var creature=data.createEntity();var c=new CreatureComponent();c.creatureId=2;c.name="Fixture";c.bloodType="Fixture";data.setComponents(creature,c,new Owner((short)3,(short)3));var id=data.createEntity();var tile=new MapTile();tile.p=new Point(0,0);tile.bridgeTerrainType=Tile.BridgeTerrainType.WATER;data.setComponent(id,tile);return data;}
        IMapTileInformation tile(int x,int y){return proxy(IMapTileInformation.class,(n,a)->switch(n){case "getX"->x;case "getY"->y;case "getLocation"->new Point(x,y);case "getEntityId"->new EntityId(1);default->throw new AssertionError(n);});}
        Choice step(){
            var selected=assertThrows(Selected.class,()->creatures.createController(new EntityId(0)).findWork(WorkContinuation.IDLE_UPDATE));
            var state=new PartyState(1,(short)3,true,PartyType.NONE,new EntityId(10),List.of(new PartyState.Member(0,new EntityId(10)),new PartyState.Member(1,new EntityId(11)),new PartyState.Member(2,new EntityId(12))));
            var restored=PartyController.restoreState(state,party,()->2,id->members.get((int)id.getId()-10).controller,random);
            var leader=restored.getPartyLeader().getEntityId();var point=navigation.findRandomAccessibleTile(new Point(2,2),3,navigable);
            objects.addLooseGold((short)3,point.x,point.y,10,100);
            MapTileController.setAttributesFromTerrain(data,textureTile,terrain,random);
            return new Choice(selected.job,leader,point,data.getComponent(new EntityId(1),MapTile.class).randomTextureIndex);
        }
        World copy()throws Exception{var saved=RandomSnapshots.decode(RandomSnapshots.encode(random.captureState()));var w=new World(GameEntityData.restore(EntitySnapshots.decode(EntitySnapshots.encode(data.capture()))),SimulationRandom.restore(saved));assertEquals(saved,w.random.captureState());return w;}
        public void close(){data.close();}
    }
}
