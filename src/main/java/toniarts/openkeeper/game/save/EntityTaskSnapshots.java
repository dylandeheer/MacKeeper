/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.object.*;
import toniarts.openkeeper.game.controller.room.*;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.logic.IEntityPositionLookup;
import toniarts.openkeeper.game.task.*;
import toniarts.openkeeper.game.task.creature.*;
import toniarts.openkeeper.game.task.worker.*;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.utils.Point;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Executable transport/needs nodes. Creature targets bind after all task and creature shells exist. */
public final class EntityTaskSnapshots {
    public enum Kind { SLEEP, EAT, RESCUE, CAPTURE_ENEMY, CARRY_TO_LAIR, CARRY_TO_PRISON, FETCH_OBJECT, CARRY_OBJECT }
    public record State(Kind kind, TaskBaseState base, Point location, short player, EntityId target,
            ObjectControlState object, EntityId room, EntityId food, boolean executed) {
        public State {
            Objects.requireNonNull(kind);Objects.requireNonNull(base);validId(target);
            if (player < 0 || player > 255) throw new IllegalArgumentException("Invalid task player");
            if (kind == Kind.EAT) {
                if (location != null || player != 0) throw new IllegalArgumentException("Unexpected food-task tile values");
                validId(food);
            } else {
                if (location == null || location.x < 0 || location.y < 0 || food != null) throw new IllegalArgumentException("Invalid entity-task tile/food values");
            }
            boolean objectTask = kind == Kind.FETCH_OBJECT || kind == Kind.CARRY_OBJECT;
            if (objectTask != (object != null) || (object != null && !target.equals(object.entityId())))
                throw new IllegalArgumentException("Invalid object-task definition");
            boolean roomTask = kind == Kind.CARRY_TO_PRISON || kind == Kind.CARRY_OBJECT;
            if (roomTask != (room != null)) throw new IllegalArgumentException("Invalid entity-task room reference");
            if (room != null) validId(room);
            if ((kind == Kind.RESCUE || kind == Kind.CAPTURE_ENEMY || kind == Kind.FETCH_OBJECT) && executed)
                throw new IllegalArgumentException("Unexpected entity-task completion flag");
        }
    }
    public record Capture(List<State> tasks, List<RoomState> removedRooms) {
        public Capture {tasks = List.copyOf(tasks);removedRooms = List.copyOf(removedRooms);}
    }
    private static final Set<Class<?>> SUPPORTED = Set.of(GoToSleep.class, GoToEat.class, RescueCreatureTask.class,
            CaptureEnemyCreatureTask.class, CarryCreatureToLairTask.class, CarryEnemyCreatureToPrison.class,
            FetchObjectTask.class, CarryObjectToStorageTask.class);
    private static final int MAGIC = 0x4d4b4554; // MKET
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 1024 * 1024;
    private static void validId(EntityId id) {if (id == null || id.getId() < 0) throw new IllegalArgumentException("Invalid task entity reference");}
    public static boolean supports(Task task) {return SUPPORTED.contains(task.getClass());}
    public static Capture captureRoots(Collection<Task> roots) {
        var unique = Collections.newSetFromMap(new IdentityHashMap<Task, Boolean>());unique.addAll(roots);
        var tasks = unique.stream().filter(EntityTaskSnapshots::supports).sorted(Comparator.comparingLong(Task::getId))
                .map(task -> (AbstractTask) task).toList();
        var removed = new TreeMap<EntityId, IRoomController>();
        for (var task : tasks) if (task instanceof AbstractRoomTask roomTask && roomTask.getRoom().isRemoved()) {
            var old = removed.putIfAbsent(roomTask.getRoom().getEntityId(), roomTask.getRoom());
            if (old != null && old != roomTask.getRoom()) throw new IllegalArgumentException("Aliased removed room controllers");
        }
        return new Capture(tasks.stream().map(EntityTaskSnapshots::capture).toList(), removed.values().stream()
                .map(room -> ((AbstractRoomController) room).captureState()).toList());
    }
    public static State capture(AbstractTask task) {
        Kind kind;boolean executed = false;EntityId food = null;ObjectControlState object = null;
        if (task instanceof GoToSleep sleep) {kind = Kind.SLEEP;executed = sleep.hasExecuted();}
        else if (task instanceof GoToEat eat) {kind = Kind.EAT;executed = eat.hasExecuted();food = eat.getFoodTarget();}
        else if (task instanceof RescueCreatureTask) kind = Kind.RESCUE;
        else if (task instanceof CaptureEnemyCreatureTask) kind = Kind.CAPTURE_ENEMY;
        else if (task instanceof CarryCreatureToLairTask lair) {kind = Kind.CARRY_TO_LAIR;executed = lair.hasExecuted();}
        else if (task instanceof CarryEnemyCreatureToPrison prison) {kind = Kind.CARRY_TO_PRISON;executed = prison.hasExecuted();}
        else if (task instanceof FetchObjectTask fetch) {kind = Kind.FETCH_OBJECT;object = ((ObjectController) fetch.getObjectController()).captureState();}
        else if (task instanceof CarryObjectToStorageTask carry) {kind = Kind.CARRY_OBJECT;executed = carry.hasExecuted();object = ((ObjectController) carry.getObjectController()).captureState();}
        else throw new IllegalArgumentException("Unsupported entity-task implementation");
        EntityId target = object != null ? object.entityId() : ((CreatureTargetTask) task).creatureTarget().get().getEntityId();
        return new State(kind, task.captureBaseState(), task instanceof AbstractTileTask tile ? tile.captureTileLocation() : null,
                task instanceof AbstractTileTask tile ? tile.getPlayerId() : (short)0, target, object,
                task instanceof AbstractRoomTask room ? room.getRoom().getEntityId() : null, food, executed);
    }
    /** Withhold shells from execution and hash-based queues until target/assignee linking finishes. */
    public static AbstractTask restore(State state, TaskIdentityAllocator ids, INavigationService navigation, IMapController map,
            Function<EntityId, IRoomController> rooms, TaskManager manager, IEntityPositionLookup positions,
            GameEntityData data, IKwdFile definition, IObjectsController objects) {
        if (!data.wasAllocated(state.target()) || (state.food() != null && !data.wasAllocated(state.food())))
            throw new IllegalArgumentException("Unallocated entity-task target");
        IRoomController room = state.room() == null ? null : rooms.apply(state.room());
        if (state.room() != null && (room == null || !state.room().equals(room.getEntityId())))
            throw new IllegalArgumentException("Missing/mismatched entity-task room");
        var object = state.object() == null ? null : ObjectController.restoreState(state.object(), data, definition, objects, map);
        var saved = state.base();var p = state.location();short player = state.player();var target = state.target();boolean done = state.executed();
        return switch (state.kind()) {
            case SLEEP -> new GoToSleep(ids, saved, navigation, map, p, player, target, done);
            case EAT -> new GoToEat(ids, saved, navigation, map, p, player, target, positions, state.food(), data, done);
            case RESCUE -> new RescueCreatureTask(ids, saved, navigation, map, p, player, target, manager);
            case CAPTURE_ENEMY -> new CaptureEnemyCreatureTask(ids, saved, navigation, map, p, player, target, manager);
            case CARRY_TO_LAIR -> new CarryCreatureToLairTask(ids, saved, navigation, map, p, player, target, done);
            case CARRY_TO_PRISON -> new CarryEnemyCreatureToPrison(ids, saved, navigation, map, p, player, target, room, manager, done);
            case FETCH_OBJECT -> new FetchObjectTask(ids, saved, navigation, map, p, player, object, manager);
            case CARRY_OBJECT -> new CarryObjectToStorageTask(ids, saved, navigation, map, p, player, object, room, manager, done);
        };
    }
    public static byte[] encode(State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);enumeration(Kind.class).write(out, state.kind());
            var identity = state.base().identity();out.writeLong(identity.id());out.writeLong(identity.created().getEpochSecond());out.writeInt(identity.created().getNano());
            nullable(POINT).write(out, state.location());out.writeShort(state.player());ENTITY_ID.write(out, state.target());
            nullable(SHORT).write(out, state.object() == null ? null : state.object().definition());
            nullable(ENTITY_ID).write(out, state.room());nullable(ENTITY_ID).write(out, state.food());out.writeBoolean(state.executed());
            out.writeInt(state.base().assignees().size());
            for (var entry : new TreeMap<>(state.base().assignees()).entrySet()) {ENTITY_ID.write(out, entry.getKey());FLOAT.write(out, entry.getValue());}
        }
        count(bytes.size(), MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported entity-task section");
            var kind = enumeration(Kind.class).read(in);long id = in.readLong(), seconds = in.readLong();int nano = in.readInt();
            if (nano < 0 || nano >= 1_000_000_000) throw new IOException("Invalid task timestamp");
            var identity = new TaskIdentityAllocator.Identity(id, Instant.ofEpochSecond(seconds, nano));
            var point = nullable(POINT).read(in);short player = in.readShort();var target = ENTITY_ID.read(in);var object = nullable(SHORT).read(in);
            var room = nullable(ENTITY_ID).read(in);var food = nullable(ENTITY_ID).read(in);boolean executed = BOOLEAN.read(in);
            int n = count(in.readInt(), 65_536);var assignees = new TreeMap<EntityId, Float>();
            for (int i = 0; i < n; i++) if (assignees.put(ENTITY_ID.read(in), FLOAT.read(in)) != null) throw new IOException("Duplicate task assignee");
            if (in.read() != -1) throw new IOException("Trailing entity-task data");
            return new State(kind, new TaskBaseState(identity, assignees), point, player, target,
                    object == null ? null : new ObjectControlState(target, object), room, food, executed);
        } catch (IllegalArgumentException | NullPointerException | java.time.DateTimeException e) {throw new IOException("Invalid entity-task section", e);}
    }
    private EntityTaskSnapshots() { }
}
