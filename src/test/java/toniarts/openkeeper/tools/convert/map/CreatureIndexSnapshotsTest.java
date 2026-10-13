/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.IPlayerController;
import toniarts.openkeeper.game.controller.player.PlayerCreatureControl;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.logic.PlayerCreatureSystem;
import toniarts.openkeeper.game.save.*;
import static org.junit.jupiter.api.Assertions.*;

class CreatureIndexSnapshotsTest {
    @Test void pendingAdditionDeathAndOwnerSpeciesChangesResumeOnceInTheSameOrder() throws Exception {
        try (var f = new Fixture()) {
            var deleted = f.add((short) 3, (short) 2);var transferred = f.add((short) 3, (short) 2);var dead = f.add((short) 4, (short) 2);
            var system = new PlayerCreatureSystem(f.data, f.definition, f.players.values());
            try {
                f.data.removeEntity(deleted);f.data.removeComponent(dead, Health.class);
                f.data.setComponent(transferred, new Owner((short) 4, (short) 4));f.data.setComponent(transferred, creatureComponent((short) 1));
                f.add((short) 3, (short) 2);f.add((short) 4, (short) 2);
                var state = CreatureIndexSnapshots.decode(CreatureIndexSnapshots.encode(system.captureState()));
                var staged = GameEntityData.restore(EntitySnapshots.decode(EntitySnapshots.encode(f.data.capture())));
                try {
                    var players = restoredPlayers(f, staged);var beforeGroups = groups(players);byte[] before = EntitySnapshots.encode(staged.capture());
                    var restored = PlayerCreatureSystem.restoreState(staged, f.definition, players.values(), state);
                    try {
                        assertEquals(beforeGroups, groups(players));assertEquals(state, restored.captureState());
                        assertArrayEquals(before, EntitySnapshots.encode(staged.capture()));
                        system.processTick(.25f);restored.processTick(.25f);assertEquals(groups(f.players), groups(players));
                        assertEquals(system.captureState(), restored.captureState());
                        assertEquals(1, players.get((short) 3).getCreatureControl().getTypeCount());
                        assertEquals(1, players.get((short) 4).getCreatureControl().getTypeCount());
                        assertEquals(1, players.get((short) 4).getCreatureControl().getImpCount());
                        restored.processTick(.25f);assertEquals(groups(f.players), groups(players));
                        // A later ownership change must still use the newly observed owner.
                        f.data.setComponent(transferred, new Owner((short) 3, (short) 3));staged.setComponent(transferred, new Owner((short) 3, (short) 3));
                        system.processTick(.25f);restored.processTick(.25f);assertEquals(groups(f.players), groups(players));
                        assertEquals(system.captureState(), restored.captureState());
                    } finally {restored.stop();}
                } finally {staged.close();}
            } finally {system.stop();}
        }
    }

    @Test void snapshotBeforeFirstRestoredTickKeepsPendingWorkAndRejectsIncompletePlayerIndex() throws Exception {
        try (var f = new Fixture()) {
            var id = f.add((short) 3, (short) 2);var source = new PlayerCreatureSystem(f.data, f.definition, f.players.values());
            try {
                f.data.removeEntity(id);var state = source.captureState();
                var first = PlayerCreatureSystem.restoreState(f.data, f.definition, f.players.values(), state);
                try {
                    var second = PlayerCreatureSystem.restoreState(f.data, f.definition, f.players.values(), CreatureIndexSnapshots.decode(CreatureIndexSnapshots.encode(first.captureState())));
                    try {assertEquals(state, second.captureState());} finally {second.stop();}
                } finally {first.stop();}
                f.players.get((short) 3).getCreatureControl().onCreatureRemoved(id, f.creatures.get((short) 2));
                byte[] before = EntitySnapshots.encode(f.data.capture());
                assertThrows(IllegalArgumentException.class, () -> PlayerCreatureSystem.restoreState(f.data, f.definition, f.players.values(), state));
                assertArrayEquals(before, EntitySnapshots.encode(f.data.capture()));
            } finally {source.stop();}
        }
    }

    @Test void codecRejectsEveryTruncationDuplicateAndInvalidIdentity() throws Exception {
        var state = new PlayerCreatureSystem.State(List.of(new PlayerCreatureSystem.Entry(new EntityId(0), (short) 3, (short) 2)));
        byte[] bytes = CreatureIndexSnapshots.encode(state);assertEquals(state, CreatureIndexSnapshots.decode(bytes));
        for (int n = 0; n < bytes.length; n++) {byte[] cut = Arrays.copyOf(bytes, n);assertThrows(IOException.class, () -> CreatureIndexSnapshots.decode(cut));}
        for (int offset : new int[]{0, 4, 8, 12}) {byte[] bad = bytes.clone();ByteBuffer.wrap(bad).putInt(offset, -1);assertThrows(IOException.class, () -> CreatureIndexSnapshots.decode(bad));}
        byte[] duplicate = Arrays.copyOf(bytes, 36);System.arraycopy(bytes, 12, duplicate, 24, 12);ByteBuffer.wrap(duplicate).putInt(8, 2);
        assertThrows(IOException.class, () -> CreatureIndexSnapshots.decode(duplicate));
        assertThrows(IOException.class, () -> CreatureIndexSnapshots.decode(Arrays.copyOf(bytes, bytes.length + 1)));
    }

    static Map<Short,List<PlayerControlState.Group>> groups(Map<Short,IPlayerController> players) {
        Map<Short,List<PlayerControlState.Group>> result = new TreeMap<>();players.forEach((id,p) -> result.put(id,p.getCreatureControl().captureGroups()));return result;
    }
    static Map<Short,IPlayerController> restoredPlayers(Fixture f, GameEntityData data) {
        Map<Short,IPlayerController> result = new TreeMap<>();
        f.players.forEach((id,p) -> {
            var keeper = Keeper.restoreState(p.getKeeper().captureState(), null);
            var control = new PlayerCreatureControl(keeper, f.creatures.get((short) 1), f.creatures.values());
            control.restoreGroups(List.of(), p.getCreatureControl().captureGroups(), n -> f.creatures.get((short) n), data);
            result.put(id, player(keeper, control));
        });return result;
    }
    static IPlayerController player(Keeper keeper, PlayerCreatureControl control) {
        return (IPlayerController) Proxy.newProxyInstance(IPlayerController.class.getClassLoader(), new Class<?>[]{IPlayerController.class},
                (p,m,a) -> switch(m.getName()) {case "getKeeper" -> keeper;case "getCreatureControl" -> control;default -> throw new AssertionError(m.getName());});
    }
    static CreatureComponent creatureComponent(short type) {var c = new CreatureComponent();c.creatureId = type;return c;}
    static class Fixture implements AutoCloseable {
        final GameEntityData data = new GameEntityData();final Map<Short,Creature> creatures = new HashMap<>();
        final IKwdFile definition;final Map<Short,IPlayerController> players = new TreeMap<>();
        Fixture() {
            for (short id = 1; id <= 2; id++) {var c = new Creature();c.setCreatureId(id);c.setOrderInEditor(id);creatures.put(id,c);}
            definition = (IKwdFile) Proxy.newProxyInstance(IKwdFile.class.getClassLoader(), new Class<?>[]{IKwdFile.class},
                    (p,m,a) -> {if (m.getName().equals("getCreature")) return creatures.get(a[0]);throw new AssertionError(m.getName());});
            for (short id = 3; id <= 4; id++) {var keeper = new Keeper(false,id);players.put(id,player(keeper,new PlayerCreatureControl(keeper,creatures.get((short) 1),creatures.values())));}
        }
        EntityId add(short owner, short type) {var id = data.createEntity();data.setComponents(id,creatureComponent(type),new Health(100,100),new Owner(owner,owner));return id;}
        public void close() {data.close();}
    }
}
