/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.data.RoomState;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.tools.convert.map.Thing;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.view.map.WallSection.WallDirection;
import static org.junit.jupiter.api.Assertions.*;

class RoomSnapshotsTest {
    @Test void allFieldsAndStorageIterationOrderSurviveRoundTrip() throws Exception {
        var source = fixture();byte[] bytes = RoomSnapshots.encode(List.of(source));
        var decoded = RoomSnapshots.decode(bytes);assertEquals(List.of(source), decoded);
        assertArrayEquals(bytes, RoomSnapshots.encode(decoded));
        assertEquals(List.of(new Point(2, 1), new Point(1, 1)),
                new ArrayList<>(decoded.getFirst().storage().get(ObjectType.GOLD).objectsByCoordinate().keySet()));
        assertEquals(List.of(new EntityId(8), new EntityId(7)), decoded.getFirst().floorFurniture());
        var minimal = new RoomState(new EntityId(0), (short) 0, List.of(new Point(0, 0)), null,
                (short) 0, false, 0, 0, null, List.of(), List.of(), List.of(), Map.of(), null);
        assertEquals(List.of(minimal), RoomSnapshots.decode(RoomSnapshots.encode(List.of(minimal))));
        assertEquals(List.of(), RoomSnapshots.decode(RoomSnapshots.encode(List.of())));
    }

    @Test void everyTruncationAndMalformedHeaderIsRejected() throws Exception {
        byte[] valid = RoomSnapshots.encode(List.of(fixture()));
        for (int n = 0; n < valid.length; n++) {
            byte[] cut = Arrays.copyOf(valid, n);assertThrows(IOException.class, () -> RoomSnapshots.decode(cut), "length=" + n);
        }
        for (int offset : new int[]{0, 4, 8}) {
            byte[] bad = valid.clone();ByteBuffer.wrap(bad).putInt(offset, -1);
            assertThrows(IOException.class, () -> RoomSnapshots.decode(bad));
        }
        assertThrows(IOException.class, () -> RoomSnapshots.decode(Arrays.copyOf(valid, valid.length + 1)));
        assertThrows(IOException.class, () -> RoomSnapshots.encode(List.of(fixture(), fixture())));
        byte[] duplicated = Arrays.copyOf(valid, 12 + (valid.length - 12) * 2);
        System.arraycopy(valid, 12, duplicated, valid.length, valid.length - 12);ByteBuffer.wrap(duplicated).putInt(8, 2);
        assertThrows(IOException.class, () -> RoomSnapshots.decode(duplicated));
    }

    @Test void stateCopiesNestedCollectionsAndRejectsAmbiguousBindings() {
        var coordinates = new ArrayList<>(List.of(new Point(1, 1)));
        var ids = new ArrayList<>(List.of(new EntityId(4)));
        Map<Point,List<EntityId>> indexed = new LinkedHashMap<>();indexed.put(coordinates.getFirst(), ids);
        var storage = new RoomState.StorageState(new EntityId(3), indexed);
        var walls = new ArrayList<>(List.of(new RoomState.Wall(WallDirection.values()[0], coordinates)));
        var state = new RoomState(new EntityId(0), (short) 1, coordinates, null, (short) 3, false, 7, 100,
                walls, List.of(new EntityId(2)), List.of(), List.of(), Map.of(ObjectType.GOLD, storage), null);
        coordinates.clear();ids.clear();indexed.clear();walls.clear();
        assertEquals(1, state.coordinates().size());assertEquals(1, state.walls().getFirst().coordinates().size());
        assertEquals(List.of(new EntityId(4)), storage.objectsByCoordinate().get(new Point(1, 1)));
        assertThrows(UnsupportedOperationException.class, () -> storage.objectsByCoordinate().clear());
        assertThrows(UnsupportedOperationException.class, () -> storage.objectsByCoordinate().values().iterator().next().clear());
        assertThrows(IllegalArgumentException.class, () -> new RoomState.StorageState(new EntityId(3),
                Map.of(new Point(0, 0), List.of(new EntityId(4)), new Point(1, 0), List.of(new EntityId(4)))));
        assertThrows(IllegalArgumentException.class, () -> new RoomState(new EntityId(0), (short) 1,
                List.of(new Point(1, 1)), null, (short) 3, false, 0, 0, null,
                List.of(new EntityId(2)), List.of(new EntityId(2)), List.of(), Map.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new RoomState(new EntityId(0), (short) 1,
                List.of(new Point(1, 1)), null, (short) 3, false, 0, 0, null,
                List.of(), List.of(), List.of(), Map.of(), new Point(2, 1)));
    }

    private static RoomState fixture() {
        var points = List.of(new Point(1, 1), new Point(2, 1));
        Map<Point,List<EntityId>> contents = new LinkedHashMap<>();
        contents.put(points.get(1), List.of(new EntityId(12), new EntityId(11)));
        contents.put(points.getFirst(), List.of(new EntityId(10)));
        return new RoomState(new EntityId(0), (short) 255, points, Thing.Room.Direction.values()[0],
                (short) 255, true, 7, 200, List.of(new RoomState.Wall(WallDirection.values()[0], points)),
                List.of(new EntityId(8), new EntityId(7)), List.of(new EntityId(6)), List.of(new EntityId(5)),
                Map.of(ObjectType.GOLD, new RoomState.StorageState(new EntityId(2), contents)), points.getFirst());
    }
}
