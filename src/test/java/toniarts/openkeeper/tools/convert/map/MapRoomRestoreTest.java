/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.room.AbstractRoomController;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.game.controller.room.storage.RoomGoldControl;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.map.MapData;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class MapRoomRestoreTest {
    @Test void mapBindsExistingTileIdsAndPreservesSelectionAndDamage() throws Exception {
        try (var f = new Fixture()) {
            byte[] before = EntitySnapshots.encode(f.data.capture());
            var map = MapData.restore(f.definition, f.data);
            assertEquals(f.tile1, map.getTile(0, 0).getEntityId());
            assertEquals(42, map.getTile(0, 0).getHealth());assertTrue(map.getTile(0, 0).isSelected((short) 3));
            assertNull(map.getTile(-1, 0));assertNull(map.getTile(2, 0));
            assertArrayEquals(before, EntitySnapshots.encode(f.data.capture()));
        }
    }

    @Test void malformedGridsFailWithoutChangingTheEntityStore() throws Exception {
        for (int fault = 0; fault < 7; fault++) try (var f = new Fixture()) {
            var tile = new MapTile(f.data.getComponent(f.tile2, MapTile.class));
            switch (fault) {
                case 0 -> f.data.removeEntity(f.tile2);
                case 1 -> {tile.p = new Point(0, 0);tile.index = 0;f.data.setComponent(f.tile2, tile);}
                case 2 -> {tile.p = new Point(2, 0);f.data.setComponent(f.tile2, tile);}
                case 3 -> {tile.index = 0;f.data.setComponent(f.tile2, tile);}
                case 4 -> {tile.terrainId = 99;f.data.setComponent(f.tile2, tile);}
                case 5 -> f.data.removeComponent(f.tile2, Health.class);
                case 6 -> {tile.p = null;f.data.setComponent(f.tile2, tile);}
            }
            byte[] before = EntitySnapshots.encode(f.data.capture());
            assertThrows(IllegalArgumentException.class, () -> MapData.restore(f.definition, f.data));
            assertArrayEquals(before, EntitySnapshots.encode(f.data.capture()));
        }
    }

    @Test void roomsRetainRemovedFurnitureAndOrderedGoldWithdrawal() throws Exception {
        try (var f = new Fixture()) {
            byte[] before = EntitySnapshots.encode(f.data.capture());
            var restored = f.restore(f.state());
            assertArrayEquals(before, EntitySnapshots.encode(f.data.capture()));
            assertEquals(List.of(f.state()), restored.captureRoomStates());
            RoomGoldControl gold = restored.getRoomController(f.room).getObjectControl(ObjectType.GOLD);
            assertEquals(0, gold.removeGold(30));
            assertEquals(50, f.data.getComponent(f.gold1, Gold.class).gold);
            assertEquals(40, f.data.getComponent(f.gold2, Gold.class).gold);
            assertEquals(90, gold.getCurrentCapacity());
            assertFalse(f.data.capture().entities().stream().anyMatch(e -> e.id() == f.furniture.getId()));
            assertTrue(f.data.wasAllocated(f.furniture));
        }
    }

    @Test void deletedForeignMissingAndDuplicateMembershipsAreRejectedWithoutWrites() throws Exception {
        for (int fault = 0; fault < 8; fault++) try (var f = new Fixture()) {
            RoomState state = f.state();
            switch (fault) {
                case 0 -> f.data.removeEntity(f.gold1);
                case 1 -> f.data.setComponent(f.gold1, new Stored(f.furniture, ObjectType.GOLD));
                case 2 -> f.data.removeComponent(f.gold1, Gold.class);
                case 3 -> f.data.setComponent(f.storage, new Storage(f.room, ObjectType.GOLD, 121, 200));
                case 4 -> state = f.state(Map.of(new Point(1, 0), List.of(f.gold2)));
                case 5 -> { // Consistent capacity still cannot conceal a missing indexed item.
                    f.data.setComponent(f.storage, new Storage(f.room, ObjectType.GOLD, 70, 200));
                    state = f.state(Map.of(new Point(1, 0), List.of(f.gold2)));
                }
                case 6 -> f.data.setComponent(f.data.createEntity(), new Storage(f.room, ObjectType.GOLD, 0, 200));
                case 7 -> {var tile = new MapTile(f.data.getComponent(f.tile2, MapTile.class));tile.room = f.furniture;f.data.setComponent(f.tile2, tile);}
            }
            byte[] before = EntitySnapshots.encode(f.data.capture());RoomState invalid = state;
            assertThrows(IllegalArgumentException.class, () -> f.restore(invalid), "fault=" + fault);
            assertArrayEquals(before, EntitySnapshots.encode(f.data.capture()));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final GameEntityData data = new GameEntityData();
        final EntityId room = data.createEntity(), storage = data.createEntity(), furniture = data.createEntity();
        final EntityId gold1 = data.createEntity(), gold2 = data.createEntity(), tile1, tile2;
        final IKwdFile definition;
        final Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> settings;
        Fixture() {
            var terrain = new Terrain();terrain.setTerrainId((short) 1);terrain.setFlags(EnumSet.of(Terrain.TerrainFlag.ROOM));
            var type = new Room();type.setName("Treasury");type.setRoomId((short) 99);type.setTerrainId((short) 1);
            type.setTileConstruction(Room.TileConstruction.NORMAL);type.setFlags(EnumSet.noneOf(Room.RoomFlag.class));
            type.setObjects(Collections.nCopies(6, (short) 0));
            var map = new GameMap(2, 1);
            definition = (IKwdFile) Proxy.newProxyInstance(IKwdFile.class.getClassLoader(), new Class<?>[]{IKwdFile.class}, (p,m,a) -> switch (m.getName()) {
                case "getMap" -> map;
                case "getTerrain" -> ((Short) a[0]) == 1 ? terrain : null;
                case "getRoomById" -> ((Integer) a[0]) == 99 ? type : null;
                default -> throw new AssertionError(m.getName());
            });
            var variable = new Variable.MiscVariable();variable.setVariableId(Variable.MiscVariable.MiscType.MAX_GOLD_PER_TREASURY_TILE);variable.setValue(100);
            settings = Map.of(variable.getVariableId(), variable);
            data.setComponents(room, new RoomComponent((short) 99, false, ObjectType.GOLD, new Point(1, 0)), new Owner((short) 3, (short) 3), new Health(42, 200));
            data.setComponent(storage, new Storage(room, ObjectType.GOLD, 120, 200));
            data.removeEntity(furniture); // A completed construction plug may remain in the furniture index.
            data.setComponents(gold1, new Gold(50, 100), new Stored(room, ObjectType.GOLD));
            data.setComponents(gold2, new Gold(70, 100), new Stored(room, ObjectType.GOLD));
            tile1 = tile(0);tile2 = tile(1);
        }
        EntityId tile(int x) {
            var id = data.createEntity();var tile = new MapTile(7, (short) 1, null, new Point(x, 0), x);
            tile.room = room;tile.selection = Map.of((short) 3, true);
            data.setComponents(id, tile, new Owner((short) 3, (short) 3), new Health(42, 100), new Gold(0, 0), new Mana(5));return id;
        }
        RoomState state() {
            Map<Point,List<EntityId>> contents = new LinkedHashMap<>();
            contents.put(new Point(1, 0), List.of(gold2));contents.put(new Point(0, 0), List.of(gold1));return state(contents);
        }
        RoomState state(Map<Point,List<EntityId>> contents) {
            return new RoomState(room, (short) 99, List.of(new Point(0, 0), new Point(1, 0)), null, (short) 3, false,
                    175, 200, null, List.of(furniture), List.of(), List.of(), Map.of(ObjectType.GOLD, new RoomState.StorageState(storage, contents)), null);
        }
        MapController restore(RoomState state) {
            var level = (ILevelInfo) Proxy.newProxyInstance(ILevelInfo.class.getClassLoader(), new Class<?>[]{ILevelInfo.class},
                    (p,m,a) -> {if (m.getName().equals("getPlayers")) return Map.of();throw new AssertionError(m.getName());});
            var objects = (IObjectsController) Proxy.newProxyInstance(IObjectsController.class.getClassLoader(), new Class<?>[]{IObjectsController.class},
                    (p,m,a) -> {throw new AssertionError("Unexpected object mutation: " + m.getName());});
            return MapController.restore(definition, objects, settings, new GameTimeController(), data, level, List.of(state));
        }
        public void close() {data.close();}
    }
}
