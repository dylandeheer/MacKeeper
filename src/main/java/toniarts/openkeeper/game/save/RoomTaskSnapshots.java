/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.data.TaskBaseState;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.task.*;
import toniarts.openkeeper.game.task.creature.*;
import toniarts.openkeeper.game.task.worker.CarryGoldToTreasuryTask;
import toniarts.openkeeper.utils.Point;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Room work nodes whose only controller edge is their room. Assignees bind in a later phase. */
public final class RoomTaskSnapshots {
    public enum Kind { CLAIM_LAIR, COLLECT_PAY, RESEARCH, TRAIN, CARRY_GOLD }
    public record State(Kind kind, TaskBaseState base, Point location, short player, EntityId room,
            boolean executed, int trainingMaxLevel, int trainingCost) {
        public State {
            Objects.requireNonNull(kind);Objects.requireNonNull(base);Objects.requireNonNull(location);Objects.requireNonNull(room);
            if (player < 0 || player > 255 || room.getId() < 0 || location.x < 0 || location.y < 0)
                throw new IllegalArgumentException("Invalid room task reference");
            if ((kind == Kind.RESEARCH || kind == Kind.TRAIN) && executed)
                throw new IllegalArgumentException("Continuous room task has a completion flag");
            if (trainingCost < 0) throw new IllegalArgumentException("Negative training cost");
            if (kind != Kind.TRAIN && (trainingMaxLevel != 0 || trainingCost != 0))
                throw new IllegalArgumentException("Unexpected training settings");
        }
    }
    /** Removed rooms are outside the map index but can still be task dependencies. */
    public record Capture(List<State> tasks, List<toniarts.openkeeper.game.data.RoomState> removedRooms) {
        public Capture {tasks = List.copyOf(tasks);removedRooms = List.copyOf(removedRooms);}
    }
    public static Capture captureRoots(Collection<Task> roots) {
        var unique = Collections.newSetFromMap(new IdentityHashMap<Task, Boolean>());unique.addAll(roots);
        var tasks = unique.stream().filter(RoomTaskSnapshots::supports).sorted(Comparator.comparingLong(Task::getId))
                .map(task -> (AbstractRoomTask) task).toList();
        var removed = new TreeMap<EntityId, IRoomController>();
        for (var task : tasks) if (task.getRoom().isRemoved()) {
            var old = removed.putIfAbsent(task.getRoom().getEntityId(), task.getRoom());
            if (old != null && old != task.getRoom()) throw new IllegalArgumentException("Aliased removed room controllers");
        }
        return new Capture(tasks.stream().map(RoomTaskSnapshots::capture).toList(), removed.values().stream()
                .map(room -> ((toniarts.openkeeper.game.controller.room.AbstractRoomController) room).captureState()).toList());
    }
    private static final int MAGIC = 0x4d4b5254; // MKRT
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Set<Class<?>> SUPPORTED = Set.of(ClaimLair.class, CollectPayTask.class, Research.class, Train.class, CarryGoldToTreasuryTask.class);
    public static boolean supports(Task task) { return SUPPORTED.contains(task.getClass()); }
    public static State capture(AbstractRoomTask task) {
        Kind kind;boolean executed = false;int max = 0, cost = 0;
        if (task instanceof ClaimLair lair) {kind = Kind.CLAIM_LAIR;executed = lair.hasExecuted();}
        else if (task instanceof CollectPayTask pay) {kind = Kind.COLLECT_PAY;executed = pay.hasExecuted();}
        else if (task instanceof Research) kind = Kind.RESEARCH;
        else if (task instanceof Train train) {kind = Kind.TRAIN;max = train.getTrainingMaxLevel();cost = train.getTrainingCost();}
        else if (task instanceof CarryGoldToTreasuryTask gold) {kind = Kind.CARRY_GOLD;executed = gold.hasExecuted();}
        else throw new IllegalArgumentException("Unsupported room task implementation");
        return new State(kind, task.captureBaseState(), task.getTaskLocation(), task.getPlayerId(), task.getRoom().getEntityId(), executed, max, cost);
    }
    /** The resolver must share room instances with map roots and other tasks, including removed rooms. */
    public static AbstractRoomTask restore(State state, TaskIdentityAllocator ids, INavigationService navigation, IMapController map,
            Function<EntityId, IRoomController> rooms, TaskManager manager, IGameWorldController world, EntityData data,
            Function<Short, IPlayerController> players, IObjectsController objects) {
        var room = rooms.apply(state.room());
        if (room == null || !state.room().equals(room.getEntityId())) throw new IllegalArgumentException("Missing/mismatched task room");
        var saved = state.base();var point = state.location();short player = state.player();
        return switch (state.kind()) {
            case CLAIM_LAIR -> new ClaimLair(ids, saved, navigation, map, point, player, room, manager, state.executed());
            case COLLECT_PAY -> new CollectPayTask(ids, saved, navigation, map, point, player, room, world, data, state.executed());
            case RESEARCH -> new Research(ids, saved, navigation, map, point, player, room, manager,
                    Objects.requireNonNull(players.apply(player), "Missing research player").getResearchControl(), objects);
            case TRAIN -> new Train(ids, saved, navigation, map, point, player, room, manager, world,
                    Objects.requireNonNull(players.apply(player), "Missing training player"), state.trainingMaxLevel(), state.trainingCost());
            case CARRY_GOLD -> new CarryGoldToTreasuryTask(ids, saved, navigation, map, point, player, room, world, state.executed());
        };
    }
    public static byte[] encode(State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);enumeration(Kind.class).write(out, state.kind());
            var identity = state.base().identity();out.writeLong(identity.id());out.writeLong(identity.created().getEpochSecond());out.writeInt(identity.created().getNano());
            POINT.write(out, state.location());SHORT.write(out, state.player());ENTITY_ID.write(out, state.room());
            out.writeBoolean(state.executed());out.writeInt(state.trainingMaxLevel());out.writeInt(state.trainingCost());
            out.writeInt(state.base().assignees().size());
            for (var entry : new TreeMap<>(state.base().assignees()).entrySet()) {ENTITY_ID.write(out, entry.getKey());FLOAT.write(out, entry.getValue());}
        }
        count(bytes.size(), MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported room task section");
            var kind = enumeration(Kind.class).read(in);long id = in.readLong(), seconds = in.readLong();int nano = in.readInt();
            if (nano < 0 || nano >= 1_000_000_000) throw new IOException("Invalid task timestamp");
            var identity = new TaskIdentityAllocator.Identity(id, Instant.ofEpochSecond(seconds, nano));
            var point = POINT.read(in);short player = SHORT.read(in);var room = ENTITY_ID.read(in);
            boolean executed = BOOLEAN.read(in);int max = in.readInt(), cost = in.readInt();
            int n = count(in.readInt(), 65_536);var assignees = new TreeMap<EntityId, Float>();
            for (int i = 0; i < n; i++) if (assignees.put(ENTITY_ID.read(in), FLOAT.read(in)) != null) throw new IOException("Duplicate task assignee");
            if (in.read() != -1) throw new IOException("Trailing room task data");
            return new State(kind, new TaskBaseState(identity, assignees), point, player, room, executed, max, cost);
        } catch (IllegalArgumentException | NullPointerException | java.time.DateTimeException e) {throw new IOException("Invalid room task section", e);}
    }
    private RoomTaskSnapshots() { }
}
