/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector2f;
import java.util.*;
import org.junit.jupiter.api.Test;
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

    private static final class Fixture {
        final Terrain floor=new Terrain(), earth=new Terrain(), rock=new Terrain(), roomTile=new Terrain();
        final Terrain[][] terrain=new Terrain[20][20];
        final short[][] owners=new short[20][20];
        final int[][] gold=new int[20][20];
        final Set<Point> selected=new HashSet<>();
        final Set<Point> blocked=new HashSet<>();
        final AI ai=new AI();
        final Keeper keeper;
        final Room first=room((short)1),second=room((short)2);
        final PlayerRoomControl rooms;
        final IRoomController heart;
        final KeeperEconomySystem system;
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
            IKwdFile level=proxy(IKwdFile.class,(name,args)->switch(name){
                case "getRoomById"->((Number)args[0]).intValue()==1?first:second;
                default->throw new AssertionError(name);
            });
            IGameWorldController world=proxy(IGameWorldController.class,(name,args)->switch(name){
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
            IPlayerController player=proxy(IPlayerController.class,(name,args)->switch(name){
                case "getKeeper"->keeper;case "getRoomControl"->rooms;default->throw new AssertionError(name);
            });
            system=new KeeperEconomySystem(world,level,List.of(player));
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
