/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.data.RoomState;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.tools.convert.map.Thing;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.view.map.WallSection.WallDirection;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Versioned room-controller section, encoded separately from ECS components. */
public final class RoomSnapshots {
    static final int MAGIC = 0x4d4b5253; // MKRS
    static final int VERSION = 1;
    static final int MAX_BYTES = 64 * 1024 * 1024;

    public static byte[] encode(List<RoomState> rooms) throws IOException {
        count(rooms.size(), RoomState.MAX_ITEMS);
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new OutputStream() {
            int size;
            public void write(int b) throws IOException { reserve(1);bytes.write(b); }
            public void write(byte[] b, int off, int len) throws IOException { reserve(len);bytes.write(b, off, len); }
            void reserve(int length) throws IOException {
                if (length > MAX_BYTES - size) throw new IOException("Room section exceeds size limit");size += length;
            }
        })) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(rooms.size());
            Set<com.simsilica.es.EntityId> ids = new HashSet<>();
            for (var room : rooms) {
                if (!ids.add(room.entityId())) throw new IOException("Duplicate room ID");
                ENTITY_ID.write(out, room.entityId());out.writeShort(room.roomType());list(POINT).write(out, room.coordinates());
                nullable(enumeration(Thing.Room.Direction.class)).write(out, room.direction());out.writeShort(room.ownerId());
                out.writeBoolean(room.destroyed());out.writeInt(room.health());out.writeInt(room.maxHealth());
                out.writeBoolean(room.walls() != null);
                if (room.walls() != null) {
                    out.writeInt(room.walls().size());
                    for (var wall : room.walls()) {enumeration(WallDirection.class).write(out, wall.direction());list(POINT).write(out, wall.coordinates());}
                }
                list(ENTITY_ID).write(out, room.floorFurniture());list(ENTITY_ID).write(out, room.wallFurniture());list(ENTITY_ID).write(out, room.pillars());
                out.writeInt(room.storage().size());
                for (var entry : new TreeMap<>(room.storage()).entrySet()) {
                    enumeration(ObjectType.class).write(out, entry.getKey());var storage = entry.getValue();ENTITY_ID.write(out, storage.entityId());
                    out.writeInt(storage.objectsByCoordinate().size());
                    // Coordinate and member order can affect withdrawals; keep it.
                    for (var contents : storage.objectsByCoordinate().entrySet()) {POINT.write(out, contents.getKey());list(ENTITY_ID).write(out, contents.getValue());}
                }
                nullable(POINT).write(out, room.door());
            }
        }
        return bytes.toByteArray();
    }

    public static List<RoomState> decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a MacKeeper room snapshot");
            if (in.readInt() != VERSION) throw new IOException("Unsupported room snapshot version");
            int roomCount = count(in.readInt(), RoomState.MAX_ITEMS);List<RoomState> rooms = new ArrayList<>();
            Set<com.simsilica.es.EntityId> ids = new HashSet<>();
            for (int i = 0; i < roomCount; i++) {
                var entity = ENTITY_ID.read(in);if (!ids.add(entity)) throw new IOException("Duplicate room ID");
                short type = in.readShort();var points = list(POINT).read(in);
                var direction = nullable(enumeration(Thing.Room.Direction.class)).read(in);short owner = in.readShort();
                boolean destroyed = BOOLEAN.read(in);int health = in.readInt(), max = in.readInt();
                List<RoomState.Wall> walls = null;
                if (BOOLEAN.read(in)) {
                    int n = count(in.readInt(), RoomState.MAX_ITEMS);walls = new ArrayList<>();
                    for (int j = 0; j < n; j++) walls.add(new RoomState.Wall(enumeration(WallDirection.class).read(in), list(POINT).read(in)));
                }
                var floor = list(ENTITY_ID).read(in);var wall = list(ENTITY_ID).read(in);var pillars = list(ENTITY_ID).read(in);
                int n = count(in.readInt(), ObjectType.values().length);Map<ObjectType, RoomState.StorageState> storage = new EnumMap<>(ObjectType.class);
                for (int j = 0; j < n; j++) {
                    var objectType = enumeration(ObjectType.class).read(in);var storageId = ENTITY_ID.read(in);
                    int locations = count(in.readInt(), RoomState.MAX_ITEMS);Map<Point, List<com.simsilica.es.EntityId>> objects = new LinkedHashMap<>();
                    for (int k = 0; k < locations; k++) {
                        if (objects.putIfAbsent(POINT.read(in), list(ENTITY_ID).read(in)) != null) throw new IOException("Duplicate storage coordinate");
                    }
                    if (storage.putIfAbsent(objectType, new RoomState.StorageState(storageId, objects)) != null) throw new IOException("Duplicate room storage type");
                }
                rooms.add(new RoomState(entity, type, points, direction, owner, destroyed, health, max, walls, floor, wall, pillars, storage, nullable(POINT).read(in)));
            }
            if (in.read() != -1) throw new IOException("Trailing room snapshot data");
            return List.copyOf(rooms);
        } catch (IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid room snapshot", e);}
    }
    private RoomSnapshots() { }
}
