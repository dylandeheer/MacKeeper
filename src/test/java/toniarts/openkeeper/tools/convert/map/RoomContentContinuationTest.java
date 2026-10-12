/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.room.AbstractRoomController;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.game.controller.room.storage.*;
import toniarts.openkeeper.game.data.RoomState;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class RoomContentContinuationTest {
    @Test void libraryPickupFreesCapacityAndRoundTripsWithRemainingBooks() throws Exception {
        try (var f = new Fixture("Library", ObjectType.SPELL_BOOK)) {
            RoomSpellBookControl books = f.room.getObjectControl(ObjectType.SPELL_BOOK);
            assertNull(books.addItem(f.item, f.point));assertEquals(1, books.getCurrentCapacity());
            assertRoundTrip(f);
            books.removeItem(f.item);assertEquals(0, books.getCurrentCapacity());
            assertNull(f.data.getComponent(f.item, Stored.class));
            books.removeItem(f.item);assertEquals(0, books.getCurrentCapacity());
            assertRoundTrip(f);
            assertNull(books.addItem(f.item, f.point));assertEquals(1, books.getCurrentCapacity());
            assertRoundTrip(f);
            assertDoesNotThrow(books::destroy);assertNull(f.data.getComponent(f.item, Stored.class));
            assertNotNull(f.data.getComponent(f.item, ObjectComponent.class));
        }
    }

    @Test void hatcheryRestoresSpawnClockAndReleasesFoodBeforeRemovingStorage() throws Exception {
        try (var f = new Fixture("Hatchery", ObjectType.FOOD)) {
            RoomFoodControl food = f.room.getObjectControl(ObjectType.FOOD);
            assertEquals(91.25, f.data.getComponent(f.room.getEntityId(), ChickenGenerator.class).lastSpawnTime);
            food.addItem(f.item, f.point);assertEquals(1, food.getCurrentCapacity());assertRoundTrip(f);
            assertDoesNotThrow(food::destroy);assertNull(f.data.getComponent(f.item, Stored.class));
            assertNotNull(f.data.getComponent(f.item, ObjectComponent.class));
        }
    }

    static void assertRoundTrip(Fixture f) throws Exception {
        byte[] before = EntitySnapshots.encode(f.data.capture());var state = f.room.captureState();
        var staged = GameEntityData.restore(EntitySnapshots.decode(before));
        try {
            var copy = (AbstractRoomController) RoomControllerFactory.restoreRoom(staged, f.definition,
                    RoomSnapshots.decode(RoomSnapshots.encode(List.of(state))).getFirst(), null, Map.of(), new GameTimeController());
            assertArrayEquals(before, EntitySnapshots.encode(staged.capture()));
            assertEquals(state, copy.captureState());
        } finally {staged.close();}
    }

    static final class Fixture implements AutoCloseable {
        final GameEntityData data = new GameEntityData();final Point point = new Point(1, 1);
        final IKwdFile definition;final AbstractRoomController room;final EntityId item;
        Fixture(String name, ObjectType type) {
            var roomType = new Room();roomType.setName(name);roomType.setRoomId((short) 99);
            roomType.setTileConstruction(Room.TileConstruction.NORMAL);roomType.setFlags(EnumSet.noneOf(Room.RoomFlag.class));
            var object = new GameObject();object.setFlags(EnumSet.noneOf(GameObject.ObjectFlag.class));
            definition = (IKwdFile) Proxy.newProxyInstance(IKwdFile.class.getClassLoader(), new Class<?>[]{IKwdFile.class},
                    (p,m,a) -> switch (m.getName()) {case "getRoomById" -> roomType;case "getObject" -> object;default -> throw new AssertionError(m.getName());});
            var id = data.createEntity();var furniture = data.createEntity();var storage = data.createEntity();item = data.createEntity();
            data.setComponents(id, new RoomComponent((short) 99, false, type == ObjectType.FOOD ? type : ObjectType.RESEARCHER, point),
                    new Owner((short) 3, (short) 3), new Health(100, 100));
            if (type == ObjectType.FOOD) data.setComponent(id, new ChickenGenerator(91.25));
            data.setComponents(furniture, new ObjectComponent((short) 1, null), new Position(0, new Vector3f(1, 0, 1)));
            data.setComponents(item, new ObjectComponent((short) 2, type), new Position(0, new Vector3f(1, .5f, 1)));
            data.setComponent(storage, new Storage(id, type, 0, 2));
            var storageStates = new EnumMap<ObjectType,RoomState.StorageState>(ObjectType.class);
            storageStates.put(type, new RoomState.StorageState(storage, Map.of()));
            if (type == ObjectType.SPELL_BOOK) {
                var research = data.createEntity();data.setComponent(research, new Storage(id, ObjectType.RESEARCHER, 0, 1));
                storageStates.put(ObjectType.RESEARCHER, new RoomState.StorageState(research, Map.of()));
            }
            var state = new RoomState(id, (short) 99, List.of(point), null, (short) 3, false, 100, 100, null,
                    List.of(furniture), List.of(), List.of(), storageStates, null);
            room = (AbstractRoomController) RoomControllerFactory.restoreRoom(data, definition, state, null, Map.of(), new GameTimeController());
        }
        public void close() {data.close();}
    }
}
