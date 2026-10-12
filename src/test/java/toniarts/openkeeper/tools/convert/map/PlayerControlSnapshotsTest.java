/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class PlayerControlSnapshotsTest {
    @Test void restoresLimitsPortalHeartOrderCountsAndResearchIdentityWithoutWrites() throws Exception {
        try (var f = new Fixture((short) 3)) {
            var state = f.player.captureState();var keeperState = f.keeper.captureState();
            byte[] entities = EntitySnapshots.encode(f.data.capture());byte[] bytes = PlayerControlSnapshots.encode(state);
            var keeper = Keeper.restoreState(KeeperSnapshots.decode(KeeperSnapshots.encode(keeperState)), null);
            var restored = PlayerController.restoreState(f.definition, keeper, f.data, PlayerControlSnapshots.decode(bytes), f.rooms::get);
            assertEquals(state, restored.captureState());assertArrayEquals(bytes, PlayerControlSnapshots.encode(restored.captureState()));
            assertEquals(keeperState, keeper.captureState());assertArrayEquals(entities, EntitySnapshots.encode(f.data.capture()));
            assertEquals(9876, keeper.getMaxMana());assertEquals(4321, restored.getGoldControl().getGoldMax());
            assertFalse(restored.getRoomControl().isPortalsOpen());assertEquals(f.heart, restored.getRoomControl().getDungeonHeart().getEntityId());
            assertEquals(2, restored.getCreatureControl().getTypeCount());assertEquals(1, restored.getCreatureControl().getImpCount());
            assertEquals(List.of((short) 3, (short) 2, (short) 1), restored.captureState().spells().entries().stream().map(e -> e.value().id()).toList());
            var values = restored.getSpellControl().getTypes();
            assertSame(keeper.getAvailableSpells().get(0), values.get(f.spells.get(0)));
            assertSame(keeper.getCurrentResearch(), values.get(f.spells.get(1)));
            assertNotSame(keeper.getAvailableSpells().get(2), values.get(f.spells.get(2)));
            restored.getSpellControl().setSpellDiscovered(f.spells.get(0), false);
            assertFalse(keeper.getAvailableSpells().get(0).isDiscovered());assertTrue(f.keeper.getAvailableSpells().get(0).isDiscovered());
            restored.getCreatureControl().onCreatureRemoved(f.deadCreature, f.creature);
            assertEquals(1, restored.getCreatureControl().getTypeCount());assertEquals(2, f.player.getCreatureControl().getTypeCount());
            assertEquals(state, f.player.captureState());
        }
    }

    @Test void repeatedCreatureNotificationsDoNotCorruptCounters() {
        try (var f = new Fixture((short) 3)) {
            var control = f.player.getCreatureControl();
            control.onCreatureAdded(f.deadCreature, f.creature);assertEquals(2, control.getTypeCount());
            control.onCreatureRemoved(f.deadCreature, f.creature);control.onCreatureRemoved(f.deadCreature, f.creature);
            assertEquals(1, control.getTypeCount());control.onCreatureRemoved(new EntityId(12345), f.creature);assertEquals(1, control.getTypeCount());
        }
    }

    @Test void heroesAndNeutralPlayersKeepTheirAbsentControls() throws Exception {
        for (short id : new short[]{Player.GOOD_PLAYER_ID, Player.NEUTRAL_PLAYER_ID}) try (var f = new Fixture(id)) {
            var state = PlayerControlSnapshots.decode(PlayerControlSnapshots.encode(f.player.captureState()));
            var keeper = Keeper.restoreState(f.keeper.captureState(), null);
            var copy = PlayerController.restoreState(f.definition, keeper, f.data, state, f.rooms::get);
            assertNull(copy.getHandControl());assertNull(copy.getManaControl());assertNull(copy.getResearchControl());
            assertEquals(state, copy.captureState());
        }
    }

    @Test void corruptSectionsAndWrongBindingsFailWithoutMutatingKeeperOrEntities() throws Exception {
        try (var f = new Fixture((short) 3)) {
            var state = f.player.captureState();byte[] valid = PlayerControlSnapshots.encode(state);
            for (int n = 0; n < valid.length; n++) {
                byte[] cut = Arrays.copyOf(valid, n);assertThrows(IOException.class, () -> PlayerControlSnapshots.decode(cut));
            }
            for (int offset : new int[]{0, 4, 10}) {
                byte[] bad = valid.clone();ByteBuffer.wrap(bad).putInt(offset, -1);assertThrows(IOException.class, () -> PlayerControlSnapshots.decode(bad));
            }
            assertThrows(IOException.class, () -> PlayerControlSnapshots.decode(Arrays.copyOf(valid, valid.length + 1)));
            var keeper = Keeper.restoreState(f.keeper.captureState(), null);var before = keeper.captureState();
            byte[] entities = EntitySnapshots.encode(f.data.capture());
            assertThrows(IllegalArgumentException.class, () -> PlayerController.restoreState(f.definition, keeper, f.data, state, id -> null));
            assertEquals(before, keeper.captureState());assertArrayEquals(entities, EntitySnapshots.encode(f.data.capture()));
            assertThrows(IllegalArgumentException.class, () -> PlayerController.restoreState(f.definition, new Keeper(false, (short) 4), f.data, state, f.rooms::get));
            assertThrows(IllegalArgumentException.class, () -> new PlayerControlState.Group((short) 1, List.of(f.heart, f.heart)));
            assertThrows(UnsupportedOperationException.class, () -> state.creatures().getFirst().entities().clear());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final GameEntityData data = new GameEntityData();final Keeper keeper;final PlayerController player;final IKwdFile definition;
        final Creature creature = creature(2), imp = creature(1);final List<KeeperSpell> spells = List.of(spell(1), spell(2), spell(3));
        final Map<EntityId,IRoomController> rooms = new LinkedHashMap<>();final EntityId heart, deadCreature;
        Fixture(short playerId) {
            keeper = new Keeper(false, playerId);
            var roomType = new Room();roomType.setRoomId((short) 1);roomType.setFlags(EnumSet.of(Room.RoomFlag.BUILDABLE));
            definition = (IKwdFile) Proxy.newProxyInstance(IKwdFile.class.getClassLoader(), new Class<?>[]{IKwdFile.class}, (p,m,a) -> switch (m.getName()) {
                case "getCreatureList" -> List.of(imp, creature);
                case "getCreature" -> ((Short) a[0]) == 1 ? imp : ((Short) a[0]) == 2 ? creature : null;
                case "getRooms" -> List.of(roomType);
                case "getRoomById" -> ((Integer) a[0]) == 1 ? roomType : null;
                case "getKeeperSpells" -> spells;
                case "getKeeperSpellById" -> spells.stream().filter(v -> v.getId() == (Integer) a[0]).findFirst().orElse(null);
                case "getDoors", "getTraps" -> List.of();
                default -> throw new AssertionError(m.getName());
            });
            var max = variable(Variable.MiscVariable.MiscType.MAXIMUM_MANA_THRESHOLD, 100);
            var hand = variable(Variable.MiscVariable.MiscType.MAX_NUMBER_OF_THINGS_IN_HAND, 7);
            player = new PlayerController(definition, keeper, imp, data, Map.of(max.getVariableId(), max, hand.getVariableId(), hand));
            keeper.setMaxMana(9876);keeper.setMana(1234);player.getGoldControl().setGoldMax(4321);
            for (int i = 2; i >= 0; i--) player.getSpellControl().setTypeAvailable(spells.get(i), true, true);
            var current = new PlayerSpell((short) 2, true);current.setResearch(12);
            player.getSpellControl().getTypes().put(spells.get(1), current);keeper.setCurrentResearch(current);
            player.getSpellControl().getTypes().put(spells.get(2), new PlayerSpell((short) 3, true));
            player.getCreatureControl().setTypeAvailable(creature, true);player.getCreatureControl().setTypeAvailable(imp, true);
            deadCreature = data.createEntity();data.removeEntity(deadCreature);
            player.getCreatureControl().onCreatureAdded(deadCreature, creature);
            player.getCreatureControl().onCreatureAdded(data.createEntity(), creature);
            player.getCreatureControl().onCreatureAdded(data.createEntity(), imp);
            heart = data.createEntity();addRoom(heart, roomType, playerId);addRoom(data.createEntity(), roomType, playerId);
            keeper.setDungeonHeartLocation(new Point(8, 8)); // Preserve independently stored keeper location.
            player.getRoomControl().setPortalsOpen(false);
        }
        void addRoom(EntityId id, Room type, short owner) {
            var instance = new toniarts.openkeeper.common.RoomInstance(type);instance.addCoordinate(new Point(1, 1));
            var room = (IRoomController) Proxy.newProxyInstance(IRoomController.class.getClassLoader(), new Class<?>[]{IRoomController.class}, (p,m,a) -> switch (m.getName()) {
                case "getEntityId" -> id;case "getRoom" -> type;case "getOwnerId" -> owner;
                case "isDungeonHeart" -> true;case "isDestroyed" -> false;case "getRoomInstance" -> instance;
                case "hashCode" -> id.hashCode();case "equals" -> p == a[0];
                default -> throw new AssertionError(m.getName());
            });
            rooms.put(id, room);player.getRoomControl().onBuild(room);
        }
        static Creature creature(int id) {var c = new Creature();c.setCreatureId((short) id);c.setOrderInEditor((short) id);return c;}
        static KeeperSpell spell(int id) {var s = new KeeperSpell();s.setKeeperSpellId((short) id);s.setOrderInEditor((short) id);return s;}
        static Variable.MiscVariable variable(Variable.MiscVariable.MiscType id, int value) {var v = new Variable.MiscVariable();v.setVariableId(id);v.setValue(value);return v;}
        public void close() {data.close();}
    }
}
