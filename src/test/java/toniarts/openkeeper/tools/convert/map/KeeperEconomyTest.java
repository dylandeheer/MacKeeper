/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import toniarts.openkeeper.game.component.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import toniarts.openkeeper.game.save.KeeperEconomySnapshots;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.player.PlayerRoomControl;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.logic.keeper.KeeperEconomySystem;
import toniarts.openkeeper.game.map.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.proxy;

class KeeperEconomyTest {
    @Test void authoredCampaignAiIsRetainedButAttachingMapDataDoesNotOverrideLobbyChoice() {
        Player raw = new Player(); raw.setAi(true); raw.setPlayerId((short)4);
        AI ai = new AI(); ai.setAiType(AI.AIType.PARANOID); raw.setAiAttributes(ai);
        Keeper campaign = new Keeper(raw);
        assertTrue(campaign.isAi()); assertEquals(AI.AIType.PARANOID, campaign.getAiType());
        Keeper human = new Keeper(false,(short)4); human.setPlayer(raw);
        assertFalse(human.isAi());
    }

    @Test void buildsDiscoveredRoomsInAuthoredOrderOnceAndPaysNormalPrice() {
        Fixture f = new Fixture();
        f.system.processTick(1);
        assertEquals(List.of((short)2),f.built);
        assertEquals(9100,f.keeper.getGold());
        assertEquals(9,f.rooms.getRoomSlabsCount(f.second));
        f.system.processTick(1);
        assertEquals(List.of((short)2,(short)1),f.built);
        f.system.processTick(1);
        assertEquals(2,f.built.size());
    }

    @Test void humanMissingHeartAndUndiscoveredRoomsReceiveNoCommands() {
        Fixture human = new Fixture(false); human.system.processTick(100);
        assertTrue(human.built.isEmpty()); assertTrue(human.selected.isEmpty());
        Fixture noHeart = new Fixture(); noHeart.rooms.onSold(noHeart.heart); noHeart.system.processTick(100);
        assertTrue(noHeart.built.isEmpty());
        Fixture research = new Fixture(); research.keeper.getAvailableRooms().forEach(r->r.setDiscovered(false));
        research.system.processTick(1); assertTrue(research.built.isEmpty());
    }

    @Test void decisionDelayDoesNotBurstAfterLongFrame() {
        Fixture f = new Fixture(); f.ai.setSpeed((short)4);
        f.system.processTick(3); assertTrue(f.built.isEmpty());
        f.system.processTick(1); assertEquals(1,f.built.size());
        f.system.processTick(100); assertEquals(2,f.built.size());
    }

    @Test void excavationWaitsForWorkersAndClaimingBeforeConstruction() {
        Fixture f = new Fixture();
        f.ai.setBuildAreaStartX(9); f.ai.setBuildAreaStartY(3);
        f.ai.setBuildAreaEndX(11); f.ai.setBuildAreaEndY(5);
        f.system.processTick(1);
        assertTrue(f.built.isEmpty()); assertFalse(f.selected.isEmpty());
        assertTrue(f.selected.stream().allMatch(p->p.x==9));
        for (int x=9;x<=11;x++) {
            for(int y=3;y<=5;y++) { f.terrain[x][y]=f.floor; f.owners[x][y]=4; }
            f.system.processTick(1);
        }
        assertEquals(List.of((short)2), f.built);
        assertEquals(9,f.rooms.getRoomSlabsCount(f.second));
    }

    @Test void enemyAndImpenetrableTilesCannotBeExcavatedForRooms() {
        Fixture f = new Fixture();
        f.ai.setBuildAreaStartX(9); f.ai.setBuildAreaStartY(3);
        f.ai.setBuildAreaEndX(11); f.ai.setBuildAreaEndY(5);
        f.owners[10][4]=5;
        f.system.processTick(1); assertTrue(f.built.isEmpty()); assertTrue(f.selected.isEmpty());
        f.owners[10][4]=2;
        f.terrain[10][4]=f.rock;
        f.system.processTick(1); assertTrue(f.selected.isEmpty());
    }

    @Test void incomeUsesOnlyExposedDepositsAndStopsAtAuthoredThreshold() {
        Fixture f = new Fixture(); f.keeper.setGold(0); f.ai.setMineGoldUntilGoldHeldIsGreaterThan(1000);
        f.gold[9][4]=500; f.gold[15][4]=1000;
        f.keeper.getAvailableRooms().clear();
        f.system.processTick(1);
        assertEquals(Set.of(new Point(9,4)),f.selected);
        f.selected.clear(); f.keeper.setGold(2000); f.system.processTick(1);
        assertTrue(f.selected.isEmpty());
    }

    @Test void blockedNearestSiteDoesNotPreventBuildingAtClearAlternative() {
        Fixture f = new Fixture();
        f.blocked.addAll(List.of(new Point(2,3),new Point(5,3),new Point(3,5)));
        f.system.processTick(1);
        assertEquals(List.of((short)2), f.built);
        for(Point p:f.blocked) assertSame(f.floor,f.terrain[p.x][p.y]);
    }

    @Test void pendingConstructionRechecksBlockersAndResearchAvailability() {
        Fixture f = new Fixture();
        f.ai.setBuildAreaStartX(9); f.ai.setBuildAreaStartY(3);
        f.ai.setBuildAreaEndX(11); f.ai.setBuildAreaEndY(5);
        f.system.processTick(1);
        for(int x=9;x<=11;x++)for(int y=3;y<=5;y++) { f.terrain[x][y]=f.floor; f.owners[x][y]=4; }
        f.blocked.add(new Point(10,4));
        f.system.processTick(1); assertTrue(f.built.isEmpty());
        f.blocked.clear();
        f.keeper.getAvailableRooms().forEach(r->r.setDiscovered(false));
        f.system.processTick(1); assertTrue(f.built.isEmpty());
        f.keeper.getAvailableRooms().get(1).setDiscovered(true);
        f.system.processTick(1); assertEquals(List.of((short)2), f.built);
    }

    @Test void prospectingUsesExposedEarthWithoutReadingHiddenGoldTargets() {
        Fixture f = new Fixture(); f.keeper.setGold(0); f.keeper.getAvailableRooms().clear();
        f.ai.setMineGoldUntilGoldHeldIsGreaterThan(1000);
        f.gold[15][4]=10000;
        f.system.processTick(1);
        assertEquals(1,f.selected.size());
        Point first=f.selected.iterator().next();
        assertTrue((first.x==1 || first.x==9 || first.y==1 || first.y==13));
        assertFalse(f.selected.contains(new Point(15,4)));
        for(int i=0;i<20;i++)f.system.processTick(1);
        assertEquals(4,f.selected.size());
        f.terrain[first.x][first.y]=f.floor;f.owners[first.x][first.y]=4;f.selected.remove(first);
        f.system.processTick(1);
        assertEquals(4,f.selected.size());
    }

    @Test void constructionBoundsDoNotPreventMiningOutsideTheBuildRectangle() {
        Fixture f = new Fixture(); f.keeper.setGold(0); f.keeper.getAvailableRooms().clear();
        f.ai.setBuildAreaStartX(2);f.ai.setBuildAreaEndX(8);
        f.ai.setBuildAreaStartY(2);f.ai.setBuildAreaEndY(12);
        f.gold[9][4]=500;
        f.system.processTick(1);
        assertEquals(Set.of(new Point(9,4)),f.selected);
    }

    @Test void prospectingNeverTagsEnemyWallsOrImpenetrableTerrain() {
        Fixture f = new Fixture();f.keeper.setGold(0);f.keeper.getAvailableRooms().clear();
        for(int x=0;x<20;x++)for(int y=0;y<20;y++)if(f.owners[x][y]==2) {
            if(x<4) f.owners[x][y]=5; else f.terrain[x][y]=f.rock;
        }
        for(int i=0;i<5;i++)f.system.processTick(1);
        assertTrue(f.selected.isEmpty());
    }

    @Test void perceivedGoldGuidesWorkersThroughEarthOneFrontierStepAtATime() {
        Fixture f = new Fixture(); f.preparePerceivedGold(10);
        f.system.processTick(1);
        assertEquals(Set.of(new Point(9,4)),f.selected);
        f.terrain[9][4]=f.floor; f.owners[9][4]=4; f.selected.clear();
        f.system.processTick(1);
        assertEquals(Set.of(new Point(10,4)),f.selected);
    }

    @Test void perceptionRequiresRevealFlagAndStrictRange() {
        Fixture noFlag=new Fixture(); noFlag.preparePerceivedGold(10);
        noFlag.earth.getFlags().remove(Terrain.TerrainFlag.REVEAL_THROUGH_FOG_OF_WAR);
        noFlag.system.processTick(1);
        assertEquals(Set.of(new Point(4,1)),noFlag.selected);
        Fixture outside=new Fixture(); outside.preparePerceivedGold(8);
        outside.system.processTick(1);
        assertEquals(Set.of(new Point(4,1)),outside.selected);
    }

    @Test void captiveForeignAndDeadCreaturesCannotRevealIncomeTargets() {
        for(int state=0;state<4;state++) {
            Fixture f=new Fixture(); EntityId watcher=f.preparePerceivedGold(10);
            switch(state) {
                case 0 -> f.data.setComponent(watcher,new Owner((short)5,(short)5));
                case 1 -> f.data.setComponent(watcher,new Owner((short)4,(short)5));
                case 2 -> f.data.setComponent(watcher,new Health(0,100));
                case 3 -> f.data.setComponent(watcher,new CreatureImprisoned());
            }
            f.system.processTick(1);
            assertEquals(Set.of(new Point(4,1)),f.selected);
        }
    }

    @Test void creatureOnClosedDoorCannotRevealGoldUntilDoorOpens() {
        Fixture f=new Fixture();f.preparePerceivedGold(10);
        EntityId door=f.data.createEntity();
        f.data.setComponents(door,new DoorComponent(),new DoorViewState((short)1,false,false,false),
                new Position(0,new Vector3f(4,0,4)));
        f.system.processTick(1);
        assertEquals(Set.of(new Point(4,1)),f.selected);
        f.selected.clear();
        f.data.setComponent(door,new DoorViewState((short)1,false,false,true));
        f.system.processTick(1);
        assertEquals(Set.of(new Point(9,4)),f.selected);
    }

    @Test void incomeRoutingCannotPassThroughEnemyLandOrImpenetrableWalls() {
        for(boolean enemy:List.of(true,false)) {
            Fixture f=new Fixture();f.preparePerceivedGold(10);
            for(int y=0;y<20;y++) {
                if(enemy) f.owners[9][y]=5; else f.terrain[9][y]=f.rock;
            }
            f.system.processTick(1);
            assertEquals(Set.of(new Point(4,1)),f.selected);
        }
    }

    @Test void restoredDecisionCadenceKeepsFractionalDelayAndDoesNotBurst() throws Exception {
        Fixture a=new Fixture(), b=new Fixture();a.ai.setSpeed((short)4);b.ai.setSpeed((short)4);
        step(a,b,.75f);reload(b);step(a,b,3);assertTrue(a.built.isEmpty());
        reload(b);step(a,b,.25f);assertEquals(List.of((short)2),a.built);
        reload(b);step(a,b,100);assertEquals(List.of((short)2,(short)1),a.built);
        reload(b);step(a,b,.5f);assertEquals(.5,a.system.captureState().players().get((short)4).elapsed());
    }

    @Test void restoredExcavationPlanRetainsProgressAndBuildsAtTheSameFutureTick() throws Exception {
        Fixture a=new Fixture(),b=new Fixture();
        for(var f:List.of(a,b)) {
            f.ai.setBuildAreaStartX(9);f.ai.setBuildAreaStartY(3);f.ai.setBuildAreaEndX(11);f.ai.setBuildAreaEndY(5);
        }
        step(a,b,1);assertNotNull(a.system.captureState().players().get((short)4).plan());
        step(a,b,.25f);reload(b);
        for(int x=9;x<=11;x++) {
            for(var f:List.of(a,b)) for(int y=3;y<=5;y++){f.terrain[x][y]=f.floor;f.owners[x][y]=4;}
            reload(b);step(a,b,1);
        }
        assertEquals(List.of((short)2),a.built);assertEquals(9100,a.keeper.getGold());
        assertNull(a.system.captureState().players().get((short)4).plan());
    }

    @Test void stalledPlanRetainsAbandonmentCounterAcrossRepeatedLoads() throws Exception {
        Fixture a=new Fixture(),b=new Fixture();
        for(var f:List.of(a,b)) {
            f.ai.setBuildAreaStartX(9);f.ai.setBuildAreaStartY(3);f.ai.setBuildAreaEndX(11);f.ai.setBuildAreaEndY(5);
        }
        step(a,b,1);
        for(int i=0;i<59;i++){reload(b);step(a,b,1);}
        assertEquals(59,a.system.captureState().players().get((short)4).unchanged());
        reload(b);step(a,b,1);assertNull(a.system.captureState().players().get((short)4).plan());
        assertEquals(60,a.system.captureState().players().get((short)4).unchanged());
        reload(b);step(a,b,1);assertNotNull(a.system.captureState().players().get((short)4).plan());
        assertEquals(0,a.system.captureState().players().get((short)4).unchanged());
    }

    @Test void rememberedGoldSurvivesLostPerceptionAndIsPrunedAtTheNormalDecision() throws Exception {
        Fixture a=new Fixture(),b=new Fixture();var watcherA=a.preparePerceivedGold(10);var watcherB=b.preparePerceivedGold(10);
        step(a,b,1);assertEquals(Set.of(new Point(12,4)),a.system.captureState().players().get((short)4).knownGold());
        a.data.removeEntity(watcherA);b.data.removeEntity(watcherB);step(a,b,.5f);
        for(var f:List.of(a,b)){f.terrain[9][4]=f.floor;f.owners[9][4]=4;f.selected.clear();}
        reload(b);step(a,b,.5f);assertEquals(Set.of(new Point(10,4)),a.selected);
        for(var f:List.of(a,b)){f.gold[12][4]=0;f.selected.clear();}
        reload(b);assertFalse(b.system.captureState().players().get((short)4).knownGold().isEmpty());
        step(a,b,1);assertTrue(a.system.captureState().players().get((short)4).knownGold().isEmpty());
    }

    @Test void stalePlanAndInactiveOwnerRevalidateOnTheNextTickInsteadOfDuringLoad() throws Exception {
        for(int change=0;change<4;change++) {
            Fixture a=new Fixture(),b=new Fixture();
            for(var f:List.of(a,b)) {
                f.ai.setBuildAreaStartX(9);f.ai.setBuildAreaStartY(3);f.ai.setBuildAreaEndX(11);f.ai.setBuildAreaEndY(5);
            }
            step(a,b,1);
            for(var f:List.of(a,b)) switch(change) {
                case 0->f.blocked.add(new Point(10,4));
                case 1->f.keeper.getAvailableRooms().forEach(r->r.setDiscovered(false));
                case 2->f.rooms.onSold(f.heart);
                case 3->f.keeper.getPlayer().setAiAttributes(null);
            }
            var before=a.system.captureState();reload(b);assertEquals(before,b.system.captureState());
            step(a,b,1);assertTrue(a.built.isEmpty());
            if(change==2)assertTrue(a.system.captureState().players().isEmpty());
            else if(change==3)assertEquals(before,a.system.captureState());
            else assertNull(a.system.captureState().players().get((short)4).plan());
        }
    }

    @Test void economyCodecRejectsMalformedValuesAndKeepsCollectionsDetached() throws Exception {
        var points=new HashSet<>(Set.of(new Point(7,8)));var players=new HashMap<Short,KeeperEconomySnapshots.Brain>();
        players.put((short)4,new KeeperEconomySnapshots.Brain(.5,points,new KeeperEconomySnapshots.Plan((short)2,1,2,3,4),12,5));
        var saved=new KeeperEconomySnapshots.State(players);points.clear();players.clear();
        assertEquals(1,saved.players().get((short)4).knownGold().size());
        assertThrows(UnsupportedOperationException.class,()->saved.players().clear());
        assertThrows(UnsupportedOperationException.class,()->saved.players().get((short)4).knownGold().clear());
        var bytes=KeeperEconomySnapshots.encode(saved);assertEquals(saved,KeeperEconomySnapshots.decode(bytes));
        for(int i=0;i<bytes.length;i++){var truncated=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->KeeperEconomySnapshots.decode(truncated));}
        byte[] header=bytes.clone();header[0]=0;assertThrows(IOException.class,()->KeeperEconomySnapshots.decode(header));
        assertThrows(IOException.class,()->KeeperEconomySnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
        assertThrows(IllegalArgumentException.class,()->new KeeperEconomySnapshots.Brain(Double.NaN,Set.of(),null,0,-1));
        assertThrows(IllegalArgumentException.class,()->new KeeperEconomySnapshots.Brain(0,Set.of(),null,61,0));
        assertThrows(IllegalArgumentException.class,()->new KeeperEconomySnapshots.Brain(0,Set.of(),null,0,-2));
        assertThrows(IllegalArgumentException.class,()->new KeeperEconomySnapshots.Plan((short)2,0,0,100000,100000));
        assertThrows(IllegalArgumentException.class,()->new KeeperEconomySnapshots.State(Map.of((short)1,saved.players().get((short)4))));
        // Duplicate owner and duplicate point records are rejected, even though a map/set could hide them.
        try(var buffer=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(buffer)) {
            out.writeInt(0x4d4b4543);out.writeInt(1);out.writeInt(2);
            out.write(bytes,12,bytes.length-12);out.write(bytes,12,bytes.length-12);
            assertThrows(IOException.class,()->KeeperEconomySnapshots.decode(buffer.toByteArray()));
        }
        try(var buffer=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(buffer)) {
            out.writeInt(0x4d4b4543);out.writeInt(1);out.writeInt(1);out.writeShort(4);out.writeDouble(0);out.writeInt(2);
            out.writeInt(1);out.writeInt(2);out.writeInt(1);out.writeInt(2);out.writeBoolean(false);out.writeInt(0);out.writeInt(-1);
            assertThrows(IOException.class,()->KeeperEconomySnapshots.decode(buffer.toByteArray()));
        }
    }

    @Test void economyBindingRejectsForeignOwnersAndInvalidMapReferencesWithoutCommands() {
        Fixture f=new Fixture();
        var ordinary=new KeeperEconomySnapshots.Brain(.75,Set.of(),null,0,-1);
        for(var invalid:List.of(new KeeperEconomySnapshots.State(Map.of((short)5,ordinary)),
                new KeeperEconomySnapshots.State(Map.of((short)4,new KeeperEconomySnapshots.Brain(0,Set.of(new Point(20,0)),null,0,-1))),
                new KeeperEconomySnapshots.State(Map.of((short)4,new KeeperEconomySnapshots.Brain(0,Set.of(),new KeeperEconomySnapshots.Plan((short)88,1,1,3,3),0,-1))),
                new KeeperEconomySnapshots.State(Map.of((short)4,new KeeperEconomySnapshots.Brain(0,Set.of(),new KeeperEconomySnapshots.Plan((short)2,19,1,3,3),0,-1))))) {
            assertThrows(IllegalArgumentException.class,()->KeeperEconomySystem.restoreState(f.world,f.level,List.of(f.player),f.data,invalid));
            assertTrue(f.built.isEmpty());assertTrue(f.selected.isEmpty());assertEquals(10000,f.keeper.getGold());
        }
        assertThrows(IllegalArgumentException.class,()->KeeperEconomySystem.restoreState(f.world,f.level,List.of(f.player,f.player),f.data,new KeeperEconomySnapshots.State(Map.of())));
    }

    private static void reload(Fixture f) throws Exception {
        var saved=f.system.captureState();var selected=Set.copyOf(f.selected);var built=List.copyOf(f.built);long gold=f.keeper.getGold();
        var restored=KeeperEconomySystem.restoreState(f.world,f.level,List.of(f.player),f.data,KeeperEconomySnapshots.decode(KeeperEconomySnapshots.encode(saved)));
        assertEquals(saved,restored.captureState());assertEquals(selected,f.selected);assertEquals(built,f.built);assertEquals(gold,f.keeper.getGold());
        f.system.stop();f.system=restored;assertEquals(saved,restored.captureState());
    }
    private static void step(Fixture a,Fixture b,float time) {
        a.system.processTick(time);b.system.processTick(time);
        assertEquals(a.system.captureState(),b.system.captureState());assertEquals(a.built,b.built);assertEquals(a.selected,b.selected);
        assertEquals(a.keeper.getGold(),b.keeper.getGold());assertEquals(a.rooms.getRoomSlabsCount(a.first),b.rooms.getRoomSlabsCount(b.first));
        assertEquals(a.rooms.getRoomSlabsCount(a.second),b.rooms.getRoomSlabsCount(b.second));
        for(int x=0;x<20;x++)for(int y=0;y<20;y++){assertEquals(a.terrain[x][y].getFlags(),b.terrain[x][y].getFlags());assertEquals(a.owners[x][y],b.owners[x][y]);}
    }

    private static final class Fixture {
        final Terrain floor=new Terrain(), earth=new Terrain(), rock=new Terrain(), roomTile=new Terrain();
        final Terrain[][] terrain=new Terrain[20][20];
        final short[][] owners=new short[20][20];
        final int[][] gold=new int[20][20];
        final Set<Point> selected=new HashSet<>();
        final Set<Point> blocked=new HashSet<>();
        final AI ai=new AI();
        final com.simsilica.es.base.DefaultEntityData data = new com.simsilica.es.base.DefaultEntityData();
        final Creature scout = new Creature();
        final Keeper keeper;
        final Room first=room((short)1),second=room((short)2);
        final PlayerRoomControl rooms;
        final IRoomController heart;
        KeeperEconomySystem system;
        IGameWorldController world;
        IKwdFile level;
        IPlayerController player;
        final List<Short> built=new ArrayList<>();
        Fixture() { this(true); }
        Fixture(boolean computer) {
            floor.setFlags(EnumSet.of(Terrain.TerrainFlag.OWNABLE));
            earth.setFlags(EnumSet.of(Terrain.TerrainFlag.SOLID,Terrain.TerrainFlag.TAGGABLE));
            rock.setFlags(EnumSet.of(Terrain.TerrainFlag.SOLID,Terrain.TerrainFlag.IMPENETRABLE));
            roomTile.setFlags(EnumSet.of(Terrain.TerrainFlag.ROOM,Terrain.TerrainFlag.OWNABLE));
            for(int x=0;x<20;x++) for(int y=0;y<20;y++) {
                boolean owned=x>=2&&x<=8&&y>=2&&y<=12;
                terrain[x][y]=owned?floor:earth;owners[x][y]=(short)(owned?4:2);
            }
            Player raw=new Player();raw.setPlayerId((short)4);raw.setAi(computer);raw.setAiAttributes(ai);
            ai.setSpeed((short)1);ai.setDefaultSize((short)9);ai.setBuildOrder(List.of((short)2,(short)1));
            keeper=new Keeper(raw);keeper.setGold(10000);
            keeper.getAvailableRooms().add(new ResearchableEntity((short)1,true,ResearchableType.ROOM));
            keeper.getAvailableRooms().add(new ResearchableEntity((short)2,true,ResearchableType.ROOM));
            rooms=new PlayerRoomControl(keeper,List.of(first,second));
            Room heartRoom=room((short)99);
            heart=controller(heartRoom,true,List.of(new Point(4,4)));
            rooms.onBuild(heart);
            terrain[4][4]=roomTile;
            IMapDataInformation<IMapTileController> data=proxy(IMapDataInformation.class,(name,args)->switch(name){
                case "getWidth","getHeight"->20;
                case "getTile"->{Point p=args.length==1?(Point)args[0]:new Point((int)args[0],(int)args[1]);yield tile(p);}
                default->throw new AssertionError(name);
            });
            IMapController map=proxy(IMapController.class,(name,args)->switch(name){
                case "getMapData"->data;
                case "getTerrain"->{var t=(IMapTileInformation)args[0];yield terrain[t.getLocation().x][t.getLocation().y];}
                case "isTaggable"->{Point p=(Point)args[0];yield tile(p)!=null&&terrain[p.x][p.y]==earth;}
                case "isSelected"->selected.contains(args[0]);
                case "selectTiles"->{Vector2f p=(Vector2f)args[0];selected.add(new Point((int)p.x,(int)p.y));yield null;}
                case "isBuildable"->{Point p=(Point)args[0];yield tile(p)!=null&&terrain[p.x][p.y]==floor&&owners[p.x][p.y]==4;}
                default->throw new AssertionError(name);
            });
            level=proxy(IKwdFile.class,(name,args)->switch(name){
                case "getCreature"->scout;
                case "getRoomById"->switch(((Number)args[0]).intValue()){case 1->first;case 2->second;default->null;};
                default->throw new AssertionError(name);
            });
            world=proxy(IGameWorldController.class,(name,args)->switch(name){
                case "getMapController"->map;
                case "getConstructionBlockingTiles"->Set.copyOf(blocked);
                case "build"->{
                    Vector2f start=(Vector2f)args[0],end=(Vector2f)args[1];short id=(short)args[3];
                    List<Point> plots=new ArrayList<>();
                    for(int x=(int)start.x;x<=end.x;x++)for(int y=(int)start.y;y<=end.y;y++) {
                        assertFalse(blocked.contains(new Point(x,y)));assertSame(floor,terrain[x][y]);assertEquals(4,owners[x][y]);
                        terrain[x][y]=roomTile;plots.add(new Point(x,y));
                    }
                    keeper.setGold(keeper.getGold()-plots.size()*100);built.add(id);
                    rooms.onBuild(controller(id==1?first:second,false,plots));yield null;
                }
                default->throw new AssertionError(name);
            });
            player=proxy(IPlayerController.class,(name,args)->switch(name){
                case "getKeeper"->keeper;case "getRoomControl"->rooms;default->throw new AssertionError(name);
            });
            system=new KeeperEconomySystem(world,level,List.of(player),this.data);
        }
        EntityId preparePerceivedGold(float range) {
            keeper.setGold(0); keeper.getAvailableRooms().clear();
            scout.getAttributes().setPerceptionRange(range);
            earth.getFlags().add(Terrain.TerrainFlag.REVEAL_THROUGH_FOG_OF_WAR);
            gold[12][4]=1000;
            EntityId id=data.createEntity();
            data.setComponents(id,new CreatureComponent(),new Owner((short)4,(short)4),
                    new Position(0,new Vector3f(4,0,4)),new Health(100,100));
            return id;
        }
        IMapTileController tile(Point p) {
            if(p.x<0||p.y<0||p.x>=20||p.y>=20)return null;
            return proxy(IMapTileController.class,(name,args)->switch(name){
                case "getOwnerId"->owners[p.x][p.y];case "getLocation"->p;case "getGold"->gold[p.x][p.y];default->throw new AssertionError(name);
            });
        }
        static Room room(short id) {Room r=new Room();r.setRoomId(id);r.setOrderInEditor(id);r.setCost(100);r.setFlags(EnumSet.of(Room.RoomFlag.BUILDABLE,Room.RoomFlag.PLACEABLE_ON_LAND));return r;}
        static IRoomController controller(Room r,boolean heart,List<Point> points) {
            var instance=new toniarts.openkeeper.common.RoomInstance(r);
            points.forEach(instance::addCoordinate);
            return proxy(IRoomController.class,(name,args)->switch(name){
                case "getRoom"->r;case "isDungeonHeart"->heart;case "getRoomInstance"->instance;
                case "hashCode"->System.identityHashCode(instance);case "equals"->false;
                default->throw new AssertionError(name);
            });
        }
    }
}
