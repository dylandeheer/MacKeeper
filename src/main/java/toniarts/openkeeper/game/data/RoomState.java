/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.data;

import com.simsilica.es.EntityId;
import java.util.*;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.tools.convert.map.Thing;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.view.map.WallSection.WallDirection;

/** Detached room-controller bindings. Room/storage components remain in the ECS section. */
public record RoomState(EntityId entityId, short roomType, List<Point> coordinates,
        Thing.Room.Direction direction, short ownerId, boolean destroyed, int health, int maxHealth,
        List<Wall> walls, List<EntityId> floorFurniture, List<EntityId> wallFurniture, List<EntityId> pillars,
        Map<ObjectType, StorageState> storage, Point door) {
    public static final int MAX_ITEMS = 65_536;

    public record Wall(WallDirection direction, List<Point> coordinates) {
        public Wall { Objects.requireNonNull(direction);coordinates = points(coordinates); }
    }
    public record StorageState(EntityId entityId, Map<Point, List<EntityId>> objectsByCoordinate) {
        public StorageState {
            validId(entityId);
            if (objectsByCoordinate.size() > MAX_ITEMS) throw new IllegalArgumentException("Too many storage coordinates");
            Map<Point, List<EntityId>> copy = new LinkedHashMap<>();Set<EntityId> members = new HashSet<>();
            objectsByCoordinate.forEach((point, ids) -> {
                Objects.requireNonNull(point);var values = ids(ids);
                for (EntityId id : values) if (!members.add(id)) throw new IllegalArgumentException("Duplicate stored entity");
                copy.put(point, values);
            });
            if (members.size() > MAX_ITEMS) throw new IllegalArgumentException("Too many stored entities");
            objectsByCoordinate = Collections.unmodifiableMap(copy);
        }
    }
    public RoomState {
        validId(entityId);
        if (roomType < 0 || roomType > 255 || ownerId < 0 || ownerId > 255) throw new IllegalArgumentException("Invalid room type/owner");
        coordinates = points(coordinates);
        if (coordinates.isEmpty()) throw new IllegalArgumentException("Empty room");
        if (walls != null) {walls = List.copyOf(walls);if (walls.size() > MAX_ITEMS) throw new IllegalArgumentException("Too many walls");}
        floorFurniture = ids(floorFurniture);wallFurniture = ids(wallFurniture);pillars = ids(pillars);
        Set<EntityId> furniture = new HashSet<>();
        for (var group : List.of(floorFurniture, wallFurniture, pillars)) for (var id : group)
            if (!furniture.add(id) || id.equals(entityId)) throw new IllegalArgumentException("Duplicate room furniture");
        storage = Map.copyOf(storage);Set<EntityId> storageIds = new HashSet<>();
        for (var value : storage.values()) if (!storageIds.add(value.entityId()) || furniture.contains(value.entityId()) || entityId.equals(value.entityId()))
            throw new IllegalArgumentException("Duplicate room storage ID");
        if (door != null && !coordinates.contains(door)) throw new IllegalArgumentException("Room door outside room");
    }
    private static List<Point> points(List<Point> values) {
        values = List.copyOf(values);
        if (values.size() > MAX_ITEMS || new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException("Invalid room coordinates");
        for (Point p : values) if (p.x < 0 || p.y < 0) throw new IllegalArgumentException("Negative room coordinate");
        return values;
    }
    private static List<EntityId> ids(List<EntityId> values) {
        values = List.copyOf(values);
        if (values.size() > MAX_ITEMS || new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException("Invalid room entity list");
        values.forEach(RoomState::validId);return values;
    }
    private static void validId(EntityId value) {
        if (value == null || value.getId() < 0) throw new IllegalArgumentException("Invalid room entity ID");
    }
}
