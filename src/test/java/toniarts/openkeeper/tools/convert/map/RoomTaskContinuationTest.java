/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import java.io.IOException;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.controller.player.PlayerResearchControl;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.controller.room.storage.IRoomObjectControl;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.task.*;
import toniarts.openkeeper.game.task.creature.*;
import toniarts.openkeeper.game.task.worker.CarryGoldToTreasuryTask;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class RoomTaskContinuationTest {
    static final Instant TIME = Instant.parse("2026-01-02T03:04:05.123456789Z");
    static final Point POINT = new Point(2, 3);
    @Test void allFiveTypesPreserveIdentityProgressCompletionAndFutureEffectsAcrossRepeatedLoads() throws Exception {
        for (var kind : RoomTaskSnapshots.Kind.values()) try (var source = new World();var copy = new World()) {
            var ids = new TaskIdentityAllocator(new TaskIdentityAllocator.State(0), () -> TIME);
            var original = source.create(kind, ids);original.assign(source.creature, true);
            // Preserve partially accumulated work for continuous tasks and unexecuted one-shot tasks.
            if (kind == RoomTaskSnapshots.Kind.RESEARCH || kind == RoomTaskSnapshots.Kind.TRAIN) original.executeTask(source.creature, 1.25f);
            copy.copyValues(source);
            var savedIds = new TaskIdentityAllocator(ids.captureState(), () -> {throw new AssertionError("Clock sampled on restore");});
            for (float time : new float[]{1.5f, 2.5f, 3.25f, 4.1f}) {
                var saved = RoomTaskSnapshots.decode(RoomTaskSnapshots.encode(RoomTaskSnapshots.capture(original)));
                var restored = RoomTaskSnapshots.restore(saved, savedIds, null, null, id -> copy.room, copy.manager,
                        copy.world, copy.data, player -> copy.player, null);
                assertSame(copy.room, restored.getRoom());assertEquals(original.getId(), restored.getId());assertEquals(TIME, restored.getTaskCreated());
                assertThrows(IllegalStateException.class, restored::captureBaseState);
                restored.bindRestoredAssignees(id -> copy.creature);assertEquals(0, copy.assignments);
                assertEquals(saved, RoomTaskSnapshots.capture(restored));assertEquals(original.isValid(source.creature), restored.isValid(copy.creature));
                if (original.isValid(source.creature)) original.executeTask(source.creature, time);
                if (restored.isValid(copy.creature)) restored.executeTask(copy.creature, time);
                assertEquals(source.values(), copy.values(), kind.name());
                assertEquals(RoomTaskSnapshots.capture(original), RoomTaskSnapshots.capture(restored));
                assertEquals(ids.captureState(), savedIds.captureState());
                restored.unassign(copy.creature);assertEquals(0, restored.getAssigneeCount());
            }
            if (kind == RoomTaskSnapshots.Kind.CLAIM_LAIR) assertEquals(1, copy.lairs);
            if (kind == RoomTaskSnapshots.Kind.COLLECT_PAY) assertEquals(1, copy.payments);
            if (kind == RoomTaskSnapshots.Kind.CARRY_GOLD) assertEquals(1, copy.deposits);
            if (kind == RoomTaskSnapshots.Kind.TRAIN) {assertTrue(copy.charged > 3);assertEquals(0, copy.charged % 3);}
            if (kind == RoomTaskSnapshots.Kind.RESEARCH) assertTrue(copy.research.getResearch() > 13);
        }
    }
    @Test void completedWageTaskDoesNotCollectAgainEvenWhenSalaryIsStillOwed() throws Exception {
        try (var w = new World()) {
            var ids = new TaskIdentityAllocator();var task = w.create(RoomTaskSnapshots.Kind.COLLECT_PAY, ids);
            task.assign(w.creature, true);task.executeTask(w.creature, 1);assertEquals(1, w.payments);
            assertTrue(w.data.getComponent(w.creatureId, CreatureSalary.class).owed > 0);
            var restored = RoomTaskSnapshots.restore(RoomTaskSnapshots.capture(task), ids, null, null, id -> w.room,
                    w.manager, w.world, w.data, id -> w.player, null);
            restored.bindRestoredAssignees(id -> w.creature);restored.executeTask(w.creature, 2);assertEquals(1, w.payments);
        }
    }
    @Test void researchRejectsRemovedCapturedDestroyedAndFullRoomsBeforeAccessingMissingStorage() {
        try (var w = new World()) {
            var task = w.create(RoomTaskSnapshots.Kind.RESEARCH, new TaskIdentityAllocator());assertTrue(task.isValid(w.creature));
            w.removed = true;assertFalse(task.isValid(w.creature));
            w.removed = false;w.owner = 4;assertFalse(task.isValid(w.creature));
            w.owner = 3;w.destroyed = true;assertFalse(task.isValid(w.creature));
            w.destroyed = false;w.full = true;assertFalse(task.isValid(w.creature));
        }
    }
    @Test void stagingManagerNeverScansOrReplaysEventsAndCannotRunBeforeGraphBinding() throws Exception {
        try (var w = new World()) {
            var ids = new TaskIdentityAllocator(new TaskIdentityAllocator.State(9), () -> {throw new AssertionError("Allocated during stage");});
            var failMap = proxy(IMapController.class, (p,m,a) -> {throw new AssertionError("Map callback: " + m.getName());});
            byte[] before = EntitySnapshots.encode(w.data.capture());
            var manager = TaskManager.stageRestore(w.data, w.world, failMap, null, null, null, List.of(w.player), null, null, Map.of(), ids);
            try {
                assertEquals(ids.captureState(), manager.captureAllocationState());
                assertTrue(manager.captureRoomTaskStates(List.of()).tasks().isEmpty());
                assertTrue(manager.captureTileTaskStates().isEmpty());
                assertThrows(IllegalStateException.class, manager::start);
                assertThrows(IllegalStateException.class, () -> manager.processTick(.1f));
                assertArrayEquals(before, EntitySnapshots.encode(w.data.capture()));
            } finally {manager.stop();}
        }
    }
    @Test void strictRoomTaskCodecAndReferencesRejectMalformedSnapshots() throws Exception {
        try (var w = new World()) {
            var ids = new TaskIdentityAllocator();var task = w.create(RoomTaskSnapshots.Kind.TRAIN, ids);task.assign(w.creature, true);
            var state = RoomTaskSnapshots.capture(task);byte[] bytes = RoomTaskSnapshots.encode(state);
            assertEquals(state, RoomTaskSnapshots.decode(bytes));
            for (int i = 0; i < bytes.length; i++) {var cut = Arrays.copyOf(bytes, i);assertThrows(IOException.class, () -> RoomTaskSnapshots.decode(cut));}
            for (int offset : new int[]{0, 4, 8}) {var bad = bytes.clone();java.nio.ByteBuffer.wrap(bad).putInt(offset, -1);assertThrows(IOException.class, () -> RoomTaskSnapshots.decode(bad));}
            var negativeCost = bytes.clone();java.nio.ByteBuffer.wrap(negativeCost).putInt(60, -1);assertThrows(IOException.class, () -> RoomTaskSnapshots.decode(negativeCost));
            var nonfinite = bytes.clone();java.nio.ByteBuffer.wrap(nonfinite).putFloat(76, Float.NaN);assertThrows(IOException.class, () -> RoomTaskSnapshots.decode(nonfinite));
            var invalidBool = bytes.clone();invalidBool[55] = 2;assertThrows(IOException.class, () -> RoomTaskSnapshots.decode(invalidBool));
            assertThrows(IOException.class, () -> RoomTaskSnapshots.decode(Arrays.copyOf(bytes, bytes.length + 1)));
            assertThrows(IOException.class, () -> RoomTaskSnapshots.decode(new byte[1024 * 1024 + 1]));
            assertThrows(IllegalArgumentException.class, () -> RoomTaskSnapshots.restore(state, ids, null, null, id -> null, w.manager, w.world, w.data, id -> w.player, null));
            assertThrows(IllegalArgumentException.class, () -> new RoomTaskSnapshots.State(RoomTaskSnapshots.Kind.TRAIN, state.base(), POINT, (short)3, state.room(), false, 8, -1));
            assertThrows(IllegalArgumentException.class, () -> new RoomTaskSnapshots.State(RoomTaskSnapshots.Kind.RESEARCH, state.base(), POINT, (short)3, state.room(), true, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new RoomTaskSnapshots.State(RoomTaskSnapshots.Kind.CLAIM_LAIR, state.base(), POINT, (short)3, state.room(), false, 1, 0));
            assertThrows(IllegalArgumentException.class, () -> new RoomTaskSnapshots.State(state.kind(), state.base(), POINT, (short)256, state.room(), false, 8, 3));
        }
    }
    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    static final class World implements AutoCloseable {
        final GameEntityData data = new GameEntityData();final EntityId creatureId = data.createEntity(), roomId = data.createEntity();
        final Keeper keeper = new Keeper(false, (short)3);
        final ResearchableEntity research = new ResearchableEntity((short)9, ResearchableType.ROOM);
        final ICreatureController creature;final IRoomController room;final IPlayerController player;final IGameWorldController world;
        final PlayerResearchControl researchControl;final TaskManager manager;
        int assignments, payments, charged, deposits, lairs, purse = 20;boolean removed, destroyed, full;short owner = 3;
        World() {
            var creatureData = new CreatureComponent();creatureData.pay = 10;
            data.setComponents(creatureId, creatureData, new Owner((short)3, (short)3), new Health(100,100), new CreatureSalary((short)3,100,0,0));
            keeper.setGold(1000);keeper.setCurrentResearch(research);
            var definition = new Room();definition.setResearchTime(10000);
            var kwd = proxy(IKwdFile.class, (p,m,a) -> {if (m.getName().equals("getRoomById")) return definition;throw new AssertionError(m.getName());});
            researchControl = new PlayerResearchControl(keeper, kwd);
            creature = proxy(ICreatureController.class, (p,m,a) -> switch (m.getName()) {
                case "getEntityId" -> creatureId;case "hashCode" -> 0;case "equals" -> p == a[0];case "isWorker" -> false;
                case "getOwnerId" -> (short)3;case "getLevel" -> 2;case "getResearchPerSecond" -> 13;case "getGold" -> purse;
                case "setAssignedTask" -> {assignments++;yield null;}
                case "setCreatureLair" -> {assertEquals(creatureId, a[0]);yield null;}
                case "substractGold" -> {purse -= (Integer)a[0];yield null;}
                default -> throw new AssertionError(m.getName());
            });
            var storage = proxy(IRoomObjectControl.class, (p,m,a) -> switch (m.getName()) {
                case "isFullCapacity" -> full;case "addItem" -> {lairs++;yield creatureId;}default -> throw new AssertionError(m.getName());
            });
            room = proxy(IRoomController.class, (p,m,a) -> switch (m.getName()) {
                case "getEntityId" -> roomId;case "isRemoved" -> removed;case "isDestroyed" -> destroyed;case "getOwnerId" -> owner;
                case "hashCode" -> 1;case "equals" -> p == a[0];case "hasObjectControl" -> true;
                case "getObjectControl" -> {assertFalse(removed, "Removed storage accessed");yield storage;}default -> throw new AssertionError(m.getName());
            });
            player = proxy(IPlayerController.class, (p,m,a) -> switch (m.getName()) {
                case "getKeeper" -> keeper;case "getResearchControl" -> researchControl;default -> throw new AssertionError(m.getName());
            });
            world = proxy(IGameWorldController.class, (p,m,a) -> switch (m.getName()) {
                case "getGoldAvailableForPayday" -> 100;
                case "collectWages" -> {payments++;yield 10;}
                case "substractGold" -> {charged += (Integer)a[0];yield a[0];}
                case "addGold" -> {deposits++;yield 0;}
                default -> throw new AssertionError(m.getName());
            });
            manager = TaskManager.stageRestore(data, world, null, null, null, null, List.of(player), null, null, Map.of(), new TaskIdentityAllocator());
        }
        AbstractRoomTask create(RoomTaskSnapshots.Kind kind, TaskIdentityAllocator ids) {
            return switch (kind) {
                case CLAIM_LAIR -> new ClaimLair(ids, null, null, POINT, (short)3, room, manager);
                case COLLECT_PAY -> new CollectPayTask(ids, null, null, POINT, (short)3, room, world, data);
                case RESEARCH -> new Research(ids, null, null, POINT, (short)3, room, manager, researchControl, null);
                case TRAIN -> {
                    var max = new Variable.MiscVariable();max.setVariableId(Variable.MiscVariable.MiscType.TRAINING_ROOM_MAX_EXPERIENCE_LEVEL);max.setValue(8);
                    var cost = new Variable.MiscVariable();cost.setVariableId(Variable.MiscVariable.MiscType.MODIFY_PLAYER_GOLD_WHILE_TRAINING_PER_SECOND);cost.setValue(-3);
                    yield new Train(ids, null, null, POINT, (short)3, room, manager, world, Map.of(max.getVariableId(),max,cost.getVariableId(),cost), player);
                }
                case CARRY_GOLD -> new CarryGoldToTreasuryTask(ids, null, null, POINT, (short)3, room, world);
            };
        }
        List<Integer> values() {return List.of(payments, charged, deposits, lairs, purse, research.getResearch());}
        void copyValues(World other) {payments=other.payments;charged=other.charged;deposits=other.deposits;lairs=other.lairs;purse=other.purse;research.setResearch(other.research.getResearch());}
        public void close() {manager.stop();data.close();}
    }
}
