/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.listener.*;
import toniarts.openkeeper.game.logic.PlayerSpellbookSystem;
import toniarts.openkeeper.game.save.*;
import static org.junit.jupiter.api.Assertions.*;

class SpellbookIndexSnapshotsTest {
    @Test void restoresWithoutReplayingUpgradeAndReconcilesEveryResearchTypeInOrder() throws Exception {
        try (var f = new Fixture()) {
            var stable = f.add((short) 3, ResearchableType.SPELL, (short) 2);
            var removed = f.add((short) 3, ResearchableType.SPELL, (short) 1);
            var transfer = f.add((short) 3, ResearchableType.ROOM, (short) 1);
            var door = f.add((short) 3, ResearchableType.DOOR, (short) 1);
            var trap = f.add((short) 4, ResearchableType.TRAP, (short) 1);
            var source = new PlayerSpellbookSystem(f.data, f.definition, new ArrayList<IPlayerController>(f.players.values()));
            try {
                assertTrue(f.players.get((short) 3).getSpellControl().getTypes().get(f.spells.get(1)).isDiscovered());
                assertFalse(f.players.get((short) 3).getSpellControl().getTypes().get(f.spells.get(1)).isUpgraded());
                f.data.removeComponent(removed, Stored.class);
                f.data.setComponent(transfer, new Owner((short) 4, (short) 4));
                f.data.removeEntity(door);f.data.setComponent(trap, new Stored(new EntityId(0), ObjectType.FOOD));
                f.add((short) 4, ResearchableType.SPELL, (short) 1);
                f.add((short) 4, ResearchableType.SPELL, (short) 1); // two arrivals grant the upgrade once
                try (var copy = new Copy(f, source)) {
                    assertTrue(copy.events.isEmpty());
                    compare(f, source, copy);
                    source.processTick(.25f);copy.system.processTick(.25f);compare(f, source, copy);
                    assertEquals(List.of("-3:SPELL:1:false:false", "-3:DOOR:1:false:false", "-4:TRAP:1:false:false",
                            "+4:SPELL:1:true:false", "+4:SPELL:1:true:true", "+4:ROOM:1:true:false", "-3:ROOM:1:false:false"), copy.events);
                    assertFalse(copy.players.get((short) 3).getSpellControl().getTypes().get(f.spells.get(1)).isUpgraded());
                    int n = copy.events.size();source.processTick(.25f);copy.system.processTick(.25f);compare(f, source, copy);assertEquals(n, copy.events.size());
                    f.data.removeEntity(stable);copy.data.removeEntity(stable);
                    source.processTick(.25f);copy.system.processTick(.25f);compare(f, source, copy);
                }
            } finally {source.stop();}
        }
    }

    @Test void removedQueryMemberUsesLastObservedOwnerAndTypeEvenAfterUnobservedEdits() throws Exception {
        try (var f = new Fixture()) {
            var id = f.add((short) 3, ResearchableType.SPELL, (short) 1);
            var source = new PlayerSpellbookSystem(f.data, f.definition, new ArrayList<IPlayerController>(f.players.values()));
            try {
                f.data.setComponents(id, new Owner((short) 4, (short) 4), new Spellbook(ResearchableType.DOOR, (short) 1));
                f.data.removeComponent(id, Stored.class);
                try (var copy = new Copy(f, source)) {
                    source.processTick(.25f);copy.system.processTick(.25f);compare(f, source, copy);
                    assertEquals(List.of("-3:SPELL:1:false:false"), copy.events);
                }
            } finally {source.stop();}
        }
    }

    @Test void typeOnlyChangesAndTransfersRetainTheLiveNotificationContract() throws Exception {
        try (var f = new Fixture()) {
            var id = f.add((short) 3, ResearchableType.SPELL, (short) 1);
            var source = new PlayerSpellbookSystem(f.data, f.definition, new ArrayList<IPlayerController>(f.players.values()));
            try {
                f.data.setComponent(id, new Spellbook(ResearchableType.DOOR, (short) 1));
                try (var copy = new Copy(f, source)) {
                    source.processTick(.25f);copy.system.processTick(.25f);compare(f, source, copy);assertTrue(copy.events.isEmpty());
                    assertEquals(ResearchableType.DOOR, copy.system.captureState().entries().getFirst().type());
                    f.data.setComponents(id, new Owner((short) 4, (short) 4), new Spellbook(ResearchableType.TRAP, (short) 1));
                    copy.data.setComponents(id, new Owner((short) 4, (short) 4), new Spellbook(ResearchableType.TRAP, (short) 1));
                    source.processTick(.25f);copy.system.processTick(.25f);compare(f, source, copy);
                    assertEquals(List.of("+4:TRAP:1:true:false", "-3:TRAP:1:false:false"), copy.events);
                }
            } finally {source.stop();}
        }
    }

    @Test void captureBeforeFirstTickRetainsTombstoneAndRejectsInvalidBindingsWithoutWrites() throws Exception {
        try (var f = new Fixture()) {
            var id = f.add((short) 3, ResearchableType.SPELL, (short) 1);
            var source = new PlayerSpellbookSystem(f.data, f.definition, new ArrayList<IPlayerController>(f.players.values()));
            try {
                f.data.removeEntity(id);
                try (var first = new Copy(f, source)) {
                    var state = SpellbookIndexSnapshots.decode(SpellbookIndexSnapshots.encode(first.system.captureState()));
                    var second = PlayerSpellbookSystem.restoreState(first.data, f.definition, new ArrayList<IPlayerController>(first.players.values()), state);
                    try {assertEquals(source.captureState(), second.captureState());assertTrue(first.events.isEmpty());}
                    finally {second.stop();}
                    byte[] before = EntitySnapshots.encode(first.data.capture());var playersBefore = states(first.players);
                    for (var bad : List.of(new PlayerSpellbookSystem.Entry(new EntityId(9999), (short) 3, ResearchableType.SPELL, (short) 1),
                            new PlayerSpellbookSystem.Entry(id, (short) 3, ResearchableType.SPELL, (short) 255)))
                        assertThrows(IllegalArgumentException.class, () -> PlayerSpellbookSystem.restoreState(first.data, f.definition, new ArrayList<IPlayerController>(first.players.values()), new PlayerSpellbookSystem.State(List.of(bad))));
                    var repeated = List.<IPlayerController>of(first.players.get((short) 3), first.players.get((short) 3));
                    assertThrows(IllegalArgumentException.class, () -> PlayerSpellbookSystem.restoreState(first.data, f.definition, repeated, state));
                    assertArrayEquals(before, EntitySnapshots.encode(first.data.capture()));assertEquals(playersBefore, states(first.players));
                }
            } finally {source.stop();}
        }
    }

    @Test void ignoredOwnersStayObservedAcrossRestoreAndCanTransferToAResearchingKeeper() throws Exception {
        try (var f = new Fixture()) {
            var id = f.add(Player.NEUTRAL_PLAYER_ID, ResearchableType.SPELL, (short) 1);
            var source = new PlayerSpellbookSystem(f.data, f.definition, new ArrayList<IPlayerController>(f.players.values()));
            try {
                f.data.setComponent(id, new Owner((short) 3, (short) 3));
                try (var copy = new Copy(f, source)) {
                    source.processTick(.25f);copy.system.processTick(.25f);compare(f, source, copy);
                    assertEquals(List.of("+3:SPELL:1:true:false"), copy.events);
                }
            } finally {source.stop();}
        }
    }

    @Test void codecBoundsNamesIdentitiesDuplicatesAndEveryTruncationAreStrict() throws Exception {
        var entry = new PlayerSpellbookSystem.Entry(new EntityId(0), (short) 3, ResearchableType.SPELL, (short) 1);
        var mutable = new ArrayList<>(List.of(entry));var state = new PlayerSpellbookSystem.State(mutable);mutable.clear();
        byte[] bytes = SpellbookIndexSnapshots.encode(state);assertEquals(state, SpellbookIndexSnapshots.decode(bytes));
        assertThrows(UnsupportedOperationException.class, () -> state.entries().clear());
        for (int n = 0; n < bytes.length; n++) {byte[] cut = Arrays.copyOf(bytes, n);assertThrows(IOException.class, () -> SpellbookIndexSnapshots.decode(cut));}
        for (int offset : new int[]{0, 4, 8, 12, 22}) {byte[] bad = bytes.clone();ByteBuffer.wrap(bad).putInt(offset, -1);assertThrows(IOException.class, () -> SpellbookIndexSnapshots.decode(bad));}
        for (int offset : new int[]{20, bytes.length - 2}) {byte[] bad = bytes.clone();ByteBuffer.wrap(bad).putShort(offset, (short) 256);assertThrows(IOException.class, () -> SpellbookIndexSnapshots.decode(bad));}
        byte[] unknown = bytes.clone();unknown[26] = 'X';assertThrows(IOException.class, () -> SpellbookIndexSnapshots.decode(unknown));
        byte[] duplicate = Arrays.copyOf(bytes, 2 * bytes.length - 12);System.arraycopy(bytes, 12, duplicate, bytes.length, bytes.length - 12);ByteBuffer.wrap(duplicate).putInt(8, 2);
        assertThrows(IOException.class, () -> SpellbookIndexSnapshots.decode(duplicate));
        assertThrows(IOException.class, () -> SpellbookIndexSnapshots.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        assertThrows(IOException.class, () -> SpellbookIndexSnapshots.decode(new byte[12 + 65_536 * 21 + 1]));
    }

    static Map<Short,PlayerControlState> states(Map<Short,PlayerController> players) {var result = new TreeMap<Short,PlayerControlState>();players.forEach((id,p) -> result.put(id,p.captureState()));return result;}
    static void compare(Fixture f, PlayerSpellbookSystem source, Copy copy) throws Exception {
        assertEquals(states(f.players), states(copy.players));assertEquals(source.captureState(), copy.system.captureState());assertEquals(f.events, copy.events);
        assertArrayEquals(EntitySnapshots.encode(f.data.capture()), EntitySnapshots.encode(copy.data.capture()));
        for (var id : f.players.keySet()) assertEquals(f.players.get(id).getKeeper().captureState(), copy.players.get(id).getKeeper().captureState());
    }
    static void listen(PlayerController player, List<String> events) {
        var listener = (PlayerListener) Proxy.newProxyInstance(PlayerListener.class.getClassLoader(), new Class<?>[]{PlayerListener.class}, (p,m,a) -> {
            if (m.getName().equals("onEntityAdded") || m.getName().equals("onEntityRemoved")) {
                var v = (ResearchableEntity) a[1];events.add((m.getName().equals("onEntityAdded") ? "+" : "-") + a[0] + ":" + v.getResearchableType() + ":" + v.getId() + ":" + v.isDiscovered() + ":" + v.isUpgraded());
            } else throw new AssertionError(m.getName());return null;
        });player.addListener(listener);
    }
    static final class Copy implements AutoCloseable {
        final GameEntityData data;final Map<Short,PlayerController> players = new TreeMap<>();final List<String> events = new ArrayList<>();final PlayerSpellbookSystem system;
        Copy(Fixture f, PlayerSpellbookSystem source) throws Exception {
            data = GameEntityData.restore(EntitySnapshots.decode(EntitySnapshots.encode(f.data.capture())));
            for (var p : f.players.values()) {
                var keeper = Keeper.restoreState(KeeperSnapshots.decode(KeeperSnapshots.encode(p.getKeeper().captureState())), null);
                var copy = PlayerController.restoreState(f.definition, keeper, data, PlayerControlSnapshots.decode(PlayerControlSnapshots.encode(p.captureState())), id -> null);
                players.put(keeper.getId(),copy);listen(copy, events);
            }
            f.events.clear();system = PlayerSpellbookSystem.restoreState(data, f.definition, new ArrayList<IPlayerController>(players.values()), SpellbookIndexSnapshots.decode(SpellbookIndexSnapshots.encode(source.captureState())));
        }
        public void close() {system.stop();data.close();}
    }
    static final class Fixture implements AutoCloseable {
        final GameEntityData data = new GameEntityData();final Map<Short,PlayerController> players = new TreeMap<>();final List<String> events = new ArrayList<>();
        final List<KeeperSpell> spells = new ArrayList<>();final Room room = new Room();final Door door = new Door();final Trap trap = new Trap();final IKwdFile definition;
        Fixture() {
            room.setRoomId((short) 1);room.setFlags(EnumSet.of(Room.RoomFlag.BUILDABLE));door.setDoorId((short) 1);trap.setTrapId((short) 1);
            for (short id = 1; id <= 2; id++) {var spell = new KeeperSpell();spell.setKeeperSpellId(id);spell.setOrderInEditor(id);spells.add(spell);}
            definition = (IKwdFile) Proxy.newProxyInstance(IKwdFile.class.getClassLoader(), new Class<?>[]{IKwdFile.class}, (p,m,a) -> switch (m.getName()) {
                case "getCreatureList" -> List.of();case "getRooms" -> List.of(room);case "getDoors" -> List.of(door);case "getTraps" -> List.of(trap);case "getKeeperSpells" -> spells;
                case "getRoomById" -> (int) a[0] == 1 ? room : null;case "getDoorById" -> (int) a[0] == 1 ? door : null;case "getTrapById" -> (int) a[0] == 1 ? trap : null;
                case "getKeeperSpellById" -> spells.stream().filter(v -> v.getId() == (int) a[0]).findFirst().orElse(null);
                default -> throw new AssertionError(m.getName());
            });
            var max = variable(Variable.MiscVariable.MiscType.MAXIMUM_MANA_THRESHOLD, 100);var hand = variable(Variable.MiscVariable.MiscType.MAX_NUMBER_OF_THINGS_IN_HAND, 7);
            for (short id : new short[]{Player.NEUTRAL_PLAYER_ID, 3, 4}) {
                var player = new PlayerController(definition, new Keeper(false,id), null, data, Map.of(max.getVariableId(),max,hand.getVariableId(),hand));
                for (var spell : spells) player.getSpellControl().setTypeAvailable(spell,true,false);
                player.getRoomControl().setTypeAvailable(room,true,false);player.getDoorControl().setTypeAvailable(door,true,false);player.getTrapControl().setTypeAvailable(trap,true,false);
                players.put(id,player);listen(player,events);
            }
        }
        EntityId add(short owner, ResearchableType type, short typeId) {var id = data.createEntity();data.setComponents(id,new Spellbook(type,typeId),new Owner(owner,owner),new Stored(new EntityId(0),ObjectType.SPELL_BOOK));return id;}
        static Variable.MiscVariable variable(Variable.MiscVariable.MiscType id, int value) {var v = new Variable.MiscVariable();v.setVariableId(id);v.setValue(value);return v;}
        public void close() {data.close();}
    }
}
