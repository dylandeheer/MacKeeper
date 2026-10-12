/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.asset.AssetKey;
import com.jme3.asset.AssetManager;
import com.jme3.math.Vector2f;
import com.jme3.scene.Node;
import com.simsilica.es.base.DefaultEntityData;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.RoomComponent;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.PartyType;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.map.MapInformation;
import toniarts.openkeeper.game.trigger.TriggerControl;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.view.map.MapRoomContainer;
import toniarts.openkeeper.view.map.MapViewController;

import static org.junit.jupiter.api.Assertions.*;

class HeroGateCollapseTest {
    @Test void triggerCollapsesWholeGateAndRepeatedRequestsAreHarmless() {
        Fixture f = new Fixture(false);
        var id = f.gate.getEntityId();
        List<Point> footprint = List.copyOf(f.gate.getRoomInstance().getCoordinates());
        assertTrue(f.map.isClaimableRoom(new Point(1, 1), (short) 3));
        var trigger = new TriggerControl(null, f.level, () -> 0, f.map, null, 1);
        trigger.update(.1f);
        trigger.update(.1f);
        assertTrue(f.gate.isDestroyed());
        assertTrue(f.gate.getRoomInstance().isDestroyed());
        assertTrue(f.data.getComponent(id, RoomComponent.class).destroyed);
        assertEquals(0, f.gate.getHealth());
        assertEquals(footprint, f.gate.getRoomInstance().getCoordinates());
        for (Point p : footprint) {
            assertEquals(id, f.map.getMapData().getTile(p).getRoomId());
            assertEquals(1, f.map.getMapData().getTile(p).getTerrainId());
            assertEquals(0, f.map.getMapData().getTile(p).getHealth());
            assertFalse(f.map.isClaimableRoom(p, (short) 3));
        }
        assertFalse(f.map.collapseHeroGate(new Point(1, 1)));
        assertFalse(f.map.changeRoomOwner(new Point(1, 1), (short) 3));
    }

    @Test void collapseRejectsFloorMissingCoordinatesAndOrdinaryRooms() {
        Fixture f = new Fixture(false);
        assertFalse(f.map.collapseHeroGate(new Point(-1, 1)));
        assertFalse(f.map.collapseHeroGate(new Point(0, 0)));
        f.room.setTileConstruction(Room.TileConstruction.NORMAL);
        assertFalse(f.map.collapseHeroGate(new Point(1, 1)));
        assertFalse(f.gate.isDestroyed());
    }

    @Test void solidWallBesideASingleThreeByOneGateResolvesItsGate() {
        Fixture f = new Fixture(true);
        f.map.alterTerrain(new Point(4, 1), (short) 2, (short) 0);
        assertTrue(f.map.collapseHeroGate(new Point(4, 1)));
        assertTrue(f.gate.isDestroyed());
        assertEquals(2, f.map.getMapData().getTile(4, 1).getTerrainId());
        assertFalse(f.map.collapseHeroGate(new Point(4, 1)));
    }

    @Test void adjacentFloorAndAmbiguousWallTargetsDoNotCollapseGates() {
        Fixture f = new Fixture(true);
        assertFalse(f.map.collapseHeroGate(new Point(4, 1)));
        f.map.alterTerrain(new Point(4, 1), (short) 2, (short) 0);
        for (int y = 2; y <= 4; y++) f.map.alterTerrain(new Point(4, y), (short) 1, (short) 1);
        f.map.updateRooms(new Point[]{new Point(4, 2), new Point(4, 3), new Point(4, 4)});
        assertEquals(2, f.map.getRoomControllers().size());
        assertFalse(f.map.collapseHeroGate(new Point(4, 1)));
        assertTrue(f.map.getRoomControllers().stream().noneMatch(IRoomController::isDestroyed));
    }

    @Test void collapsedGateDoesNotCreateOrReplaceAPartyButOtherLocationsStillWork() {
        Fixture f = new Fixture(false);
        var creatures = new CreaturesController(f.kwd, f.data, Map.of(), () -> 0, null, f.map, f.level);
        assertFalse(creatures.getParty((short) 1).isCreated());
        f.map.collapseHeroGate(new Point(1, 1));
        creatures.spawnHeroParty((short) 1, PartyType.INVASION_PARTY, new Vector2f(1, 1));
        assertFalse(creatures.getParty((short) 1).isCreated());
        creatures.spawnHeroParty((short) 1, PartyType.INVASION_PARTY, new Vector2f(0, 0));
        var created = creatures.getParty((short) 1);
        assertTrue(created.isCreated());
        creatures.spawnHeroParty((short) 1, PartyType.INVASION_PARTY, new Vector2f(2, 2));
        assertSame(created, creatures.getParty((short) 1));
    }

    @Test void liveTwoByTwoGateIsRedrawnUsingDestroyedModels() {
        verifyRenderedTransition(false, "Hero_GateDestroyed");
    }

    @Test void liveThreeByOneGateUsesDestroyedFloorsAndWalls() {
        verifyRenderedTransition(true, "_DESTROYED");
    }

    private void verifyRenderedTransition(boolean threeByOne, String destroyedName) {
        Fixture f = new Fixture(threeByOne);
        List<Point[]> dirty = new ArrayList<>();
        MapRoomContainer rooms = new MapRoomContainer(f.data, f.kwd, dirty::add);
        rooms.start();
        List<String> loaded = new ArrayList<>();
        AssetManager assets = proxy(AssetManager.class, (method, args) -> {
            if (!method.equals("loadModel")) throw new AssertionError(method);
            String name = ((AssetKey<?>) args[0]).getName();
            loaded.add(name);
            return new Node(name); // Synthetic geometry only; no retail assets in tests.
        });
        var info = new MapInformation<>(f.map.getMapData(), f.kwd, f.keepers.values(), rooms::getRoomInformation);
        MapViewController view = new MapViewController(assets, f.kwd, info, (short) 3, false) {
            @Override protected void updateProgress(float progress) { }
        };
        view.load(assets, f.kwd);
        assertTrue(loaded.stream().anyMatch(n -> n.contains(threeByOne ? "3Tile_Hero_Gate" : "Hero_Gate")));
        assertTrue(loaded.stream().noneMatch(n -> n.contains(destroyedName)));
        loaded.clear();
        f.map.collapseHeroGate(new Point(1, 1));
        rooms.update();
        assertEquals(1, dirty.size());
        view.updateTiles(dirty.removeFirst());
        var gateModels = loaded.stream().filter(n -> n.contains(threeByOne ? "3Tile_Hero_Gate" : "Hero_Gate")).toList();
        assertFalse(gateModels.isEmpty());
        assertTrue(gateModels.stream().allMatch(n -> n.contains(destroyedName)), gateModels.toString());
        assertFalse(info.isClaimableRoom(new Point(1, 1), (short) 3));
        rooms.update();
        assertTrue(dirty.isEmpty());
        rooms.stop();
    }

    private static final class Fixture {
        final DefaultEntityData data = new DefaultEntityData();
        final Map<Short, Keeper> keepers = Map.of((short) 1, new Keeper(false, (short) 1),
                (short) 3, new Keeper(false, (short) 3));
        final Room room = new Room();
        final IKwdFile kwd;
        final ILevelInfo level;
        final MapController map;
        final IRoomController gate;

        Fixture(boolean threeByOne) {
            room.setRoomId((short) 42);
            room.setName("Test hero gate");
            room.setTileConstruction(threeByOne ? Room.TileConstruction.HERO_GATE_3_BY_1 : Room.TileConstruction.HERO_GATE_2_BY_2);
            room.setObjects(Collections.nCopies(6, (short) 0));
            room.setFlags(EnumSet.of(Room.RoomFlag.PLACEABLE_ON_LAND));
            ArtResource gateArt = art(threeByOne ? "3Tile_Hero_Gate" : "Hero_Gate");
            room.setCompleteResource(gateArt);
            Terrain floor = new Terrain(), gateTile = new Terrain(), wall = new Terrain();
            wall.setTerrainId((short) 2);
            wall.setFlags(EnumSet.of(Terrain.TerrainFlag.SOLID));
            floor.setName("Floor");
            floor.setFlags(EnumSet.of(Terrain.TerrainFlag.OWNABLE));
            floor.setCompleteResource(art("TestFloor"));
            gateTile.setTerrainId((short) 1);
            gateTile.setFlags(EnumSet.of(Terrain.TerrainFlag.ROOM));
            gateTile.setStartingHealth(100);
            gateTile.setMaxHealth(100);
            GameMap source = new GameMap(5, 5);
            for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) {
                boolean gate = threeByOne ? (y == 1 && x >= 1 && x <= 3) : (x >= 1 && x <= 2 && y >= 1 && y <= 2);
                Tile tile = new Tile();
                tile.setTerrainId((short) (gate ? 1 : 0));
                tile.setPlayerId((short) (gate ? 1 : 3));
                source.setTile(x, y, tile);
            }
            Thing.Room fixed = new Thing.Room();
            fixed.setPosX(1);
            fixed.setPosY(1);
            fixed.setDirection(Thing.Room.Direction.NORTH);
            Thing.HeroParty party = new Thing.HeroParty();
            party.setId((short) 1);
            party.setHeroPartyMembers(List.of());
            TriggerAction action = new TriggerAction(null);
            action.setId(1);
            action.setType(TriggerAction.ActionType.COLLAPSE_HERO_GATE);
            action.setUserData("posX", 2);
            action.setUserData("posY", 2);
            kwd = proxy(IKwdFile.class, (method, args) -> switch (method) {
                case "getMap" -> source;
                case "getGameLevel" -> new GameLevel();
                case "getTerrain" -> (short) args[0] == 1 ? gateTile : (short) args[0] == 2 ? wall : floor;
                case "getTerrainList" -> List.of(floor, gateTile);
                case "getRoomByTerrain", "getRoomById" -> room;
                case "getDungeonHeart", "getTerrainBridge" -> null;
                case "getTriggers" -> Map.of(1, action);
                case "getThings" -> args[0] == Thing.HeroParty.class ? List.of(party)
                        : args[0] == Thing.Room.class && threeByOne ? List.of(fixed) : List.of();
                default -> throw new AssertionError(method);
            });
            level = proxy(ILevelInfo.class, (method, args) -> switch (method) {
                case "getPlayers" -> keepers;
                case "getPlayer" -> keepers.get((short) args[0]);
                case "getPaydayCycle" -> 0L;
                case "getLevelData" -> kwd;
                default -> throw new AssertionError(method);
            });
            map = new MapController(kwd, null, Map.of(), () -> 0, data, level);
            gate = map.getRoomControllerByCoordinates(new Point(1, 1));
        }
    }

    private static ArtResource art(String name) {
        ArtResource art = new ArtResource();
        art.setName(name);
        art.setFlags(EnumSet.noneOf(ArtResource.ArtResourceFlag.class));
        return art;
    }

    private interface Call { Object invoke(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.invoke(method.getName(), args)));
    }
}
