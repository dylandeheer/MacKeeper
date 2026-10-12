/*
 * Copyright (C) 2014-2015 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenKeeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenKeeper.  If not, see <http://www.gnu.org/licenses/>.
 */
package toniarts.openkeeper.game.map;

import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import toniarts.openkeeper.utils.Point;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import toniarts.openkeeper.game.component.MapTile;
import toniarts.openkeeper.game.component.Owner;
import toniarts.openkeeper.game.data.Keeper;
import static toniarts.openkeeper.game.map.MapTileController.setAttributesFromTerrain;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.tools.convert.map.Player;
import toniarts.openkeeper.tools.convert.map.Terrain;
import toniarts.openkeeper.tools.convert.map.Tile;

/**
 * This is a container for the map data
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class MapData implements IMapData {

    private final int width;
    private final int height;
    private final IMapTileController[][] tiles;

    public MapData(IKwdFile kwdFile, EntityData entityData, Collection<Keeper> players) {
        width = kwdFile.getMap().getWidth();
        height = kwdFile.getMap().getHeight();

        // Duplicate the map
        this.tiles = new IMapTileController[width][height];
        Map<Short, Keeper> playersById = players.stream().collect(Collectors.toMap(Keeper::getId, keeper -> keeper));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Tile tile = kwdFile.getMap().getTile(x, y);
                tiles[x][y] = createMapTile(entityData, tile, kwdFile, x, y, y * width + x, playersById);
            }
        }
    }

    private MapData(int width, int height) {
        this.width = width;this.height = height;
        tiles = new IMapTileController[width][height];
    }

    /** Bind a complete rectangular grid from saved components without creating or resetting tiles. */
    public static MapData restore(IKwdFile definition, EntityData data) {
        int width = definition.getMap().getWidth(), height = definition.getMap().getHeight();
        if (width <= 0 || height <= 0 || (long) width * height > 1_000_000) throw new IllegalArgumentException("Invalid saved map dimensions");
        MapData result = new MapData(width, height);
        var ids = data.findEntities(null, MapTile.class);
        if (ids.size() != width * height) throw new IllegalArgumentException("Saved map tile count differs");
        for (EntityId id : ids) {
            MapTile tile = data.getComponent(id, MapTile.class);
            Point point = tile.p;
            if (point == null || point.x < 0 || point.y < 0 || point.x >= width || point.y >= height
                    || tile.index != point.y * width + point.x || result.tiles[point.x][point.y] != null
                    || definition.getTerrain(tile.terrainId) == null
                    || data.getComponent(id, Owner.class) == null
                    || data.getComponent(id, toniarts.openkeeper.game.component.Health.class) == null
                    || data.getComponent(id, toniarts.openkeeper.game.component.Gold.class) == null
                    || data.getComponent(id, toniarts.openkeeper.game.component.Mana.class) == null) {
                throw new IllegalArgumentException("Invalid saved map tile: " + id);
            }
            result.tiles[point.x][point.y] = new MapTileController(id, data);
        }
        return result;
    }

    private static MapTileController createMapTile(EntityData entityData, Tile tile, IKwdFile kwdFile, int x, int y, int index,
            Map<Short, Keeper> playersById) {
        EntityId entityId = entityData.createEntity();

        // Create ALL components for the map tile, even things like Gold when it has none, helps to parse the map tile as whole in client
        // The actual map tile
        MapTile mapTileComponent = new MapTile();
        mapTileComponent.p = new Point(x, y);
        mapTileComponent.index = index;
        mapTileComponent.bridgeTerrainType = tile.getFlag();

        mapTileComponent.terrainId = setupTerrainOwner(kwdFile, tile, entityData, entityId, playersById);

        // The water/lava under the bridge is set only when there is an actual bridge, but we might as well set it here, it doesn't change
        Terrain terrain = kwdFile.getTerrain(mapTileComponent.terrainId);
        if (terrain.getFlags().contains(Terrain.TerrainFlag.LAVA)) {
            mapTileComponent.bridgeTerrainType = Tile.BridgeTerrainType.LAVA;
        } else if (terrain.getFlags().contains(Terrain.TerrainFlag.WATER)) {
            mapTileComponent.bridgeTerrainType = Tile.BridgeTerrainType.WATER;
        }

        // Set attributes
        setAttributesFromTerrain(entityData, entityId, mapTileComponent, terrain);

        return new MapTileController(entityId, entityData);
    }

    private static short setupTerrainOwner(IKwdFile kwdFile, Tile tile, EntityData entityData, EntityId entityId,
            Map<Short, Keeper> playersById) {
        short terrainId = tile.getTerrainId();
        short ownerId = tile.getPlayerId();

        // If the player is not participating to the game
        // - all the rooms will be neutral (except dungeon heart)
        // - ownable terrain (not room, will be destroyed, as in not owned)
        if (!playersById.containsKey(ownerId)) {
            Terrain terrain = kwdFile.getTerrain(terrainId);
            if (terrain.getFlags().contains(Terrain.TerrainFlag.ROOM)) {
                if (!kwdFile.getRoomByTerrain(terrainId).equals(kwdFile.getDungeonHeart())) {
                    ownerId = Player.NEUTRAL_PLAYER_ID;
                }
            } else if (terrain.getFlags().contains(Terrain.TerrainFlag.OWNABLE)) {
                ownerId = 0;
                terrainId = terrain.getDestroyedTypeTerrainId();
            }
        }

        // Owner
        Owner owner = new Owner(ownerId, ownerId);
        entityData.setComponent(entityId, owner);

        return terrainId;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    /**
     * Get the tile data at x & y
     *
     * @param x x coordinate
     * @param y y coordinate
     * @return the tile data
     */
    @Override
    public IMapTileController getTile(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return null;
        }
        return this.tiles[x][y];
    }

    @Override
    public void setTiles(List<IMapTileController> mapTiles) {
        for (IMapTileController mapTile : mapTiles) {
            tiles[mapTile.getX()][mapTile.getY()] = mapTile;
        }
    }

}
