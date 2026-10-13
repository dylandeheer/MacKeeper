/*
 * Copyright (C) 2014-2016 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenKeeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenKeeper.  If not, see <http://www.gnu.org/licenses/>.
 */
package toniarts.openkeeper.game.task;

import toniarts.openkeeper.game.task.creature.CollectPayTask;

import com.badlogic.gdx.ai.pfa.GraphPath;
import com.jme3.math.Vector2f;
import com.simsilica.es.Entity;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import com.simsilica.es.EntitySet;
import com.simsilica.es.filter.FieldFilter;
import toniarts.openkeeper.utils.Point;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import toniarts.openkeeper.game.controller.creature.WorkContinuation;
import java.util.stream.Collectors;
import toniarts.openkeeper.game.component.CreatureComponent;
import toniarts.openkeeper.game.component.Death;
import toniarts.openkeeper.game.component.Food;
import toniarts.openkeeper.game.component.Health;
import toniarts.openkeeper.game.component.ObjectComponent;
import toniarts.openkeeper.game.component.Owner;
import toniarts.openkeeper.game.component.Placeable;
import toniarts.openkeeper.game.component.Position;
import toniarts.openkeeper.game.component.Stored;
import toniarts.openkeeper.game.component.TaskComponent;
import toniarts.openkeeper.game.component.Unconscious;
import toniarts.openkeeper.game.controller.ICreaturesController;
import toniarts.openkeeper.game.controller.IGameWorldController;
import toniarts.openkeeper.game.controller.ILevelInfo;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.controller.IObjectsController;
import toniarts.openkeeper.game.controller.IPlayerController;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.controller.entity.EntityController;
import toniarts.openkeeper.game.controller.object.IObjectController;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.listener.MapListener;
import toniarts.openkeeper.game.listener.PlayerActionListener;
import toniarts.openkeeper.game.listener.RoomListener;
import toniarts.openkeeper.game.logic.IEntityPositionLookup;
import toniarts.openkeeper.game.logic.IGameLogicUpdatable;
import toniarts.openkeeper.game.map.IMapDataInformation;
import toniarts.openkeeper.game.map.IMapTileController;
import toniarts.openkeeper.game.map.IMapTileInformation;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.task.creature.ClaimLair;
import toniarts.openkeeper.game.task.creature.GoToEat;
import toniarts.openkeeper.game.task.creature.GoToSleep;
import toniarts.openkeeper.game.task.creature.Research;
import toniarts.openkeeper.game.task.creature.Train;
import toniarts.openkeeper.game.task.objective.AbstractObjectiveTask;
import toniarts.openkeeper.game.task.objective.KillPlayer;
import toniarts.openkeeper.game.task.objective.SendToActionPoint;
import toniarts.openkeeper.game.task.worker.CaptureEnemyCreatureTask;
import toniarts.openkeeper.game.task.worker.CarryEnemyCreatureToPrison;
import toniarts.openkeeper.game.task.worker.CarryGoldToTreasuryTask;
import toniarts.openkeeper.game.task.worker.CarryObjectToStorageTask;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.task.worker.ClaimRoomTask;
import toniarts.openkeeper.game.task.worker.ClaimTileTask;
import toniarts.openkeeper.game.task.worker.ClaimWallTileTask;
import toniarts.openkeeper.game.task.worker.DigTileTask;
import toniarts.openkeeper.game.task.worker.FetchObjectTask;
import toniarts.openkeeper.game.task.worker.RepairWallTileTask;
import toniarts.openkeeper.game.task.worker.RescueCreatureTask;
import toniarts.openkeeper.tools.convert.map.Creature;
import toniarts.openkeeper.tools.convert.map.Player;
import toniarts.openkeeper.tools.convert.map.Room;
import toniarts.openkeeper.tools.convert.map.Terrain;
import toniarts.openkeeper.tools.convert.map.Thing;
import toniarts.openkeeper.tools.convert.map.Variable;
import toniarts.openkeeper.utils.Utils;
import toniarts.openkeeper.utils.WorldUtils;

/**
 * Task manager for several players. Can assign creatures to different tasks.
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class TaskManager implements ITaskManager, IGameLogicUpdatable {

    private static final Logger logger = System.getLogger(TaskManager.class.getName());

    private final IMapController mapController;
    private final IGameWorldController gameWorldController;
    private final IObjectsController objectsController;
    private final ICreaturesController creaturesController;
    private final INavigationService navigationService;
    private final ILevelInfo levelInfo;
    private final IEntityPositionLookup entityPositionLookup;
    private final Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings;
    private final EntityData entityData;
    private final TaskIdentityAllocator taskIds;
    private boolean staged;
    private boolean restoredBound, closed;
    private TaskManagerSnapshots.State pendingQueries;
    private MapListener mapListener;
    private PlayerActionListener actionListener;
    private final Map<Short, RoomListener> roomListeners = new java.util.TreeMap<>();

    public TaskIdentityAllocator.State captureAllocationState() {return taskIds.captureState();}

    /** Implemented tile nodes in manager roots, captured only under the simulation boundary.
     * Objective subgraphs, detached retry nodes and other task kinds are not yet persisted here. */
    public List<toniarts.openkeeper.game.save.TileTaskSnapshots.State> captureTileTaskStates() {
        var roots = Collections.newSetFromMap(new java.util.IdentityHashMap<Task, Boolean>());
        roots.addAll(tasksByIds.values());taskQueues.values().forEach(roots::addAll);
        roomTasks.values().forEach(entries -> roots.addAll(entries.values()));
        return roots.stream().filter(toniarts.openkeeper.game.save.TileTaskSnapshots::supports)
                .sorted(Comparator.comparingLong(Task::getId))
                .map(task -> toniarts.openkeeper.game.save.TileTaskSnapshots.capture((AbstractTileTask) task)).toList();
    }
    public toniarts.openkeeper.game.save.RoomTaskSnapshots.Capture captureRoomTaskStates(Collection<Task> detachedNodes) {
        var roots = new ArrayList<Task>(detachedNodes);
        roots.addAll(tasksByIds.values());taskQueues.values().forEach(roots::addAll);
        roomTasks.values().forEach(entries -> roots.addAll(entries.values()));
        return toniarts.openkeeper.game.save.RoomTaskSnapshots.captureRoots(roots);
    }

    public toniarts.openkeeper.game.save.EntityTaskSnapshots.Capture captureEntityTaskStates(Collection<Task> detachedNodes) {
        var roots = new ArrayList<Task>(detachedNodes);
        roots.addAll(tasksByIds.values());taskQueues.values().forEach(roots::addAll);
        roomTasks.values().forEach(entries -> roots.addAll(entries.values()));
        return toniarts.openkeeper.game.save.EntityTaskSnapshots.captureRoots(roots);
    }

    public toniarts.openkeeper.game.save.TaskGraphSnapshots.State captureTaskGraph(toniarts.openkeeper.game.save.TaskNodeReferences.Capture references) {
        var roots = Collections.newSetFromMap(new java.util.IdentityHashMap<Task, Boolean>());
        roots.addAll(tasksByIds.values());taskQueues.values().forEach(roots::addAll);
        roomTasks.values().forEach(entries -> roots.addAll(entries.values()));
        return toniarts.openkeeper.game.save.TaskGraphSnapshots.capture(references,
                roots.stream().sorted(Comparator.comparingLong(Task::getId)).toList(), taskIds.captureState());
    }

    /** Capture ownership before traversing the graph, using the same fresh creature-reference index. */
    public TaskManagerSnapshots.State captureState(TaskNodeReferences.Capture references) {
        if (closed) throw new IllegalStateException("Task manager is stopped");
        var registered = new TreeMap<Long, TaskNodeReferences.Reference>();
        new TreeMap<>(tasksByIds).forEach((id, task) -> registered.put(id, references.reference(task)));
        var queues = taskQueues.entrySet().stream().map(entry -> new TaskManagerSnapshots.Queue(entry.getKey(),
                entry.getValue().stream().map(references::reference).toList())).toList();
        var reservations = new ArrayList<TaskManagerSnapshots.Reservation>();
        roomTasks.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().getEntityId())).forEach(entry ->
            entry.getValue().entrySet().stream().sorted(Comparator.<Entry<Point, AbstractCapacityCriticalRoomTask>>comparingInt(e -> e.getKey().y)
                    .thenComparingInt(e -> e.getKey().x)).forEach(e -> reservations.add(new TaskManagerSnapshots.Reservation(
                            entry.getKey().getEntityId(), e.getKey(), references.reference(e.getValue())))));
        var workers = unemployedCreatures.entrySet().stream().map(e -> new TaskManagerSnapshots.Worker(e.getKey().getEntityId(), e.getValue())).toList();
        return new TaskManagerSnapshots.State(queues, registered, tasksIdsByEntities, reservations, workers,
                captureQuery(taskEntities, pendingQueries == null ? null : pendingQueries.tasks()),
                captureQuery(unconsciousEntities, pendingQueries == null ? null : pendingQueries.unconscious()),
                captureQuery(corpseEntities, pendingQueries == null ? null : pendingQueries.corpses()),
                captureQuery(freeObjectEntities, pendingQueries == null ? null : pendingQueries.freeObjects()));
    }

    private static TaskManagerSnapshots.Query captureQuery(SnapshotEntitySet query, TaskManagerSnapshots.Query saved) {
        var observed = saved == null ? query.getEntityIds().stream().sorted().toList() : saved.observed();
        var dirty = new java.util.TreeSet<>(query.pendingChanges(new HashSet<>(observed)));
        if (saved != null) dirty.addAll(saved.dirty());
        return new TaskManagerSnapshots.Query(observed, List.copyOf(dirty));
    }

    /** Bind validated ownership after graph/creature edges, without starting listeners or applying query events. */
    public void bindRestoredState(TaskManagerSnapshots.State saved, TaskGraphSnapshots.Restored graph,
            java.util.function.Function<EntityId, ICreatureController> creatures,
            java.util.function.Function<EntityId, IRoomController> rooms) {
        if (!staged || restoredBound || closed) throw new IllegalStateException("Task manager cannot be rebound");
        if (!tasksByIds.isEmpty() || !tasksIdsByEntities.isEmpty() || !roomTasks.isEmpty() || !unemployedCreatures.isEmpty()
                || taskQueues.values().stream().anyMatch(q -> !q.isEmpty())) throw new IllegalStateException("Staged manager was modified");
        graph.requireBound();graph.requireOwner(this);
        var data = (GameEntityData) entityData;
        for (var query : List.of(saved.tasks(), saved.unconscious(), saved.corpses(), saved.freeObjects()))
            for (var id : query.observed()) if (!data.wasAllocated(id)) throw new IllegalArgumentException("Unallocated task query entity");
        var queues = new TreeMap<Short, Set<Task>>();
        for (var queue : saved.queues()) {
            Set<Task> nodes = new java.util.LinkedHashSet<>();
            for (var ref : queue.tasks()) {
                Task task = graph.bindings().resolve(ref), owned = task;
                while (owned instanceof toniarts.openkeeper.game.task.objective.ObjectiveTaskDecorator wrapper) owned = wrapper.getWrappedTask();
                if (owned instanceof AbstractTileTask tile && tile.getPlayerId() != queue.player())
                    throw new IllegalArgumentException("Queued task belongs to a different player");
                if (!nodes.add(task)) throw new IllegalArgumentException("Equivalent queued tasks");
            }
            queues.put(queue.player(), nodes);
        }
        if (!queues.keySet().equals(taskQueues.keySet())) throw new IllegalArgumentException("Task queue players differ");
        var registered = new HashMap<Long, Task>();
        saved.registered().forEach((id, ref) -> registered.put(id, graph.bindings().resolve(ref)));
        var reservations = new HashMap<IRoomController, Map<Point, AbstractCapacityCriticalRoomTask>>();
        for (var reservation : saved.reservations()) {
            var task = graph.bindings().resolve(reservation.task());var room = rooms.apply(reservation.room());
            if (!(task instanceof AbstractCapacityCriticalRoomTask capacity) || room == null
                    || room != capacity.getRoom() || !reservation.room().equals(room.getEntityId())
                    || !reservation.point().equals(capacity.getTaskLocation()) || capacity.getTaskManager() != this)
                throw new IllegalArgumentException("Room reservation/task binding differs");
            reservations.computeIfAbsent(room, ignored -> new HashMap<>()).put(reservation.point(), capacity);
        }
        var workers = new TreeMap<ICreatureController, WorkContinuation>(Comparator.comparing(ICreatureController::getEntityId));
        for (var worker : saved.workers()) {
            var creature = creatures.apply(worker.creature());
            if (!data.wasAllocated(worker.creature()) || creature == null || !worker.creature().equals(creature.getEntityId()))
                throw new IllegalArgumentException("Missing/mismatched unemployed creature");
            workers.put(creature, worker.continuation());
        }
        taskQueues.clear();taskQueues.putAll(queues);tasksByIds.putAll(registered);tasksIdsByEntities.putAll(saved.entityTasks());
        roomTasks.putAll(reservations);unemployedCreatures.putAll(workers);pendingQueries = saved;restoredBound = true;
    }

    /** Reconcile saved observations against current components in the ordinary phase order exactly once. */
    private void reconcileQueries() {
        var saved = pendingQueries;
        var dirty = captureQuery(taskEntities, saved.tasks()).dirty();taskEntities.applyChanges();
        processDeletedTaskIds(removed(taskEntities, saved.tasks()));
        processAddedTasks(added(taskEntities, saved.tasks()));
        var observed = new HashSet<>(saved.tasks().observed());var changed = new HashSet<>(dirty);
        processChangedTasks(taskEntities.stream().filter(e -> observed.contains(e.getId()) && changed.contains(e.getId())).toList());
        unconsciousEntities.applyChanges();
        processDeletedUnconsciousEntities(removed(unconsciousEntities, saved.unconscious()));
        processAddedUnconsciousEntities(added(unconsciousEntities, saved.unconscious()));
        corpseEntities.applyChanges();
        processDeletedCorpseEntities(removed(corpseEntities, saved.corpses()));
        processAddedCorpseEntities(added(corpseEntities, saved.corpses()));
        freeObjectEntities.applyChanges();
        processDeletedFreeObjectEntities(removed(freeObjectEntities, saved.freeObjects()));
        processAddedFreeObjectEntities(added(freeObjectEntities, saved.freeObjects()));
        pendingQueries = null;
    }
    private static List<EntityId> removed(EntitySet current, TaskManagerSnapshots.Query saved) {
        var ids = current.getEntityIds();return saved.observed().stream().filter(id -> !ids.contains(id)).sorted().toList();
    }
    private static List<Entity> added(EntitySet current, TaskManagerSnapshots.Query saved) {
        var ids = new HashSet<>(saved.observed());return current.stream().filter(e -> !ids.contains(e.getId())).toList();
    }

    private final SnapshotEntitySet taskEntities;
    private final SnapshotEntitySet unconsciousEntities;
    private final SnapshotEntitySet corpseEntities;
    private final SnapshotEntitySet freeObjectEntities;
    private final Map<Short, Set<Task>> taskQueues;
    private final Map<Long, Task> tasksByIds = new HashMap<>();
    private final Map<EntityId, Long> tasksIdsByEntities = new HashMap<>();
    private final Map<Short, IPlayerController> playerControllers;
    private final Map<IRoomController, Map<Point, AbstractCapacityCriticalRoomTask>> roomTasks = new HashMap<>();
    private final Map<ICreatureController, WorkContinuation> unemployedCreatures = new TreeMap<>(Comparator.comparing(ICreatureController::getEntityId));

    public TaskManager(EntityData entityData, IGameWorldController gameWorldController, IMapController mapController,
            IObjectsController objectsController, ICreaturesController creaturesController, INavigationService navigationService,
            Collection<IPlayerController> players, ILevelInfo levelInfo, IEntityPositionLookup entityPositionLookup,
            Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings) {
        this(entityData, gameWorldController, mapController, objectsController, creaturesController, navigationService,
                players, levelInfo, entityPositionLookup, gameSettings, new TaskIdentityAllocator(), false);
    }

    /** Inert owner for restored task shells. Bind ownership after the graph, then activate with start(). */
    public static TaskManager stageRestore(EntityData data, IGameWorldController world, IMapController map,
            IObjectsController objects, ICreaturesController creatures, INavigationService navigation,
            Collection<IPlayerController> players, ILevelInfo level, IEntityPositionLookup positions,
            Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> settings, TaskIdentityAllocator ids) {
        return new TaskManager(data, world, map, objects, creatures, navigation, players, level, positions, settings, ids, true);
    }

    private TaskManager(EntityData entityData, IGameWorldController gameWorldController, IMapController mapController,
            IObjectsController objectsController, ICreaturesController creaturesController, INavigationService navigationService,
            Collection<IPlayerController> players, ILevelInfo levelInfo, IEntityPositionLookup entityPositionLookup,
            Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings, TaskIdentityAllocator ids, boolean staged) {
        this.taskIds = java.util.Objects.requireNonNull(ids);this.staged = staged;
        this.entityData = entityData;
        this.mapController = mapController;
        this.gameWorldController = gameWorldController;
        this.objectsController = objectsController;
        this.creaturesController = creaturesController;
        this.navigationService = navigationService;
        this.levelInfo = levelInfo;
        this.entityPositionLookup = entityPositionLookup;
        this.gameSettings = gameSettings;

        // Set the players
        // Create a queue for each managed player (everybody except Good & Neutral)
        taskQueues = new TreeMap<>();
        playerControllers = HashMap.newHashMap(players.size());
        for (IPlayerController playerController : players) {
            Keeper keeper = playerController.getKeeper();

            playerControllers.put(keeper.getId(), playerController);

            if (keeper.getId() != Player.GOOD_PLAYER_ID && keeper.getId() != Player.NEUTRAL_PLAYER_ID) {
                taskQueues.put(keeper.getId(), new java.util.LinkedHashSet<>());
            }
        }

        // Scan the initial tasks
        if (!staged) scanInitialTasks();

        // Add task listeners
        if (!staged) addListeners(players);

        // Listen to entities having a task, for cleanup purposes
        taskEntities = new SnapshotEntitySet(entityData, TaskComponent.class);
        if (!staged) processAddedTasks(taskEntities);

        // Listen to rescue/capture missions
        unconsciousEntities = new SnapshotEntitySet(entityData, CreatureComponent.class, Health.class, Owner.class, Unconscious.class);
        if (!staged) processAddedUnconsciousEntities(unconsciousEntities);

        // Listen to corpse robbing missions
        corpseEntities = new SnapshotEntitySet(entityData, CreatureComponent.class, Death.class);
        if (!staged) processAddedCorpseEntities(corpseEntities);

        // Listen to object picking up missions
        freeObjectEntities = new SnapshotEntitySet(entityData, ObjectComponent.class, Position.class, Placeable.class);
        if (!staged) processAddedFreeObjectEntities(freeObjectEntities);
    }

    @Override
    public void start() {
        if (closed) throw new IllegalStateException("Task manager is stopped");
        if (staged) {
            if (!restoredBound) throw new IllegalStateException("Task manager restoration is not complete");
            try {addListeners(playerControllers.values());staged = false;}
            catch (RuntimeException | Error failure) {stop();throw failure;}
        }
    }

    @Override
    public void stop() {
        if (closed) return;
        closed = true;
        if (mapListener != null) mapController.removeListener(mapListener);
        roomListeners.forEach((player, listener) -> mapController.removeListener(player, listener));
        if (actionListener != null) gameWorldController.removeListener(actionListener);
        taskEntities.release();
        unconsciousEntities.release();
        corpseEntities.release();
        freeObjectEntities.release();
    }

    @Override
    public void processTick(float tpf) {
        if (staged || closed) throw new IllegalStateException("Task manager is not active");
        if (pendingQueries != null) {reconcileQueries();return;}
        if (taskEntities.applyChanges()) {
            processDeletedTaskIds(taskEntities.getRemovedEntities().stream().map(Entity::getId).toList());
            processAddedTasks(taskEntities.getAddedEntities());
            processChangedTasks(taskEntities.getChangedEntities());
        }
        if (unconsciousEntities.applyChanges()) {
            processDeletedUnconsciousEntities(unconsciousEntities.getRemovedEntities().stream().map(Entity::getId).toList());
            processAddedUnconsciousEntities(unconsciousEntities.getAddedEntities());
        }
        if (corpseEntities.applyChanges()) {
            processDeletedCorpseEntities(corpseEntities.getRemovedEntities().stream().map(Entity::getId).toList());
            processAddedCorpseEntities(corpseEntities.getAddedEntities());
        }
        if (freeObjectEntities.applyChanges()) {
            processDeletedFreeObjectEntities(freeObjectEntities.getRemovedEntities().stream().map(Entity::getId).toList());
            processAddedFreeObjectEntities(freeObjectEntities.getAddedEntities());
        }
    }

    private void processAddedTasks(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            long taskId = entity.get(TaskComponent.class).taskId;
            tasksIdsByEntities.put(entity.getId(), taskId);
        }
    }

    private void processDeletedTaskIds(Collection<EntityId> entities) {
        for (EntityId id : entities.stream().sorted().toList()) {
            Long taskId = tasksIdsByEntities.remove(id);
            Task task = tasksByIds.get(taskId);
            if (task != null) {
                task.unassign(creaturesController.createController(id));
                if (task.getAssigneeCount() == 0 && task.isRemovable()) {
                    //tasksByIds.remove(taskId);
                }
            }
        }
    }

    private void processChangedTasks(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            long taskId = entity.get(TaskComponent.class).taskId;
            Long oldTaskId = tasksIdsByEntities.put(entity.getId(), taskId);
            if (oldTaskId == null || taskId == oldTaskId) {
                continue;
            }

            Task task = tasksByIds.get(oldTaskId);
            if (task != null) {
                task.unassign(creaturesController.createController(entity.getId()));
                if (task.getAssigneeCount() == 0 && task.isRemovable()) {
                    //tasksByIds.remove(taskId);
                }
            }
        }
    }

    private void processAddedUnconsciousEntities(Collection<Entity> entities) {

        // Add rescue mission for the own troops and capture for the enemy
        for (Entity entity : entities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            Owner owner = entity.get(Owner.class);
            for (Entry<Short, Set<Task>> entry : taskQueues.entrySet()) {

                Task task = null;
                if (entry.getKey() == owner.ownerId) {

                    // Rescue
                    task = new RescueCreatureTask(taskIds, this, navigationService, mapController, creaturesController.createController(entity.getId()), entry.getKey());
                } else if (playerControllers.get(entry.getKey()).getKeeper().isEnemy(owner.ownerId)) {

                    // Capture
                    task = new CaptureEnemyCreatureTask(taskIds, navigationService, mapController, creaturesController.createController(entity.getId()), entry.getKey(), this);
                }

                if (task == null) {
                    continue;
                }

                entry.getValue().add(task);
                tasksByIds.put(task.getId(), task);
            }
        }
    }

    private void processDeletedUnconsciousEntities(Collection<EntityId> entities) {
        // TODO:
    }

    private void processAddedCorpseEntities(Collection<Entity> entities) {
        // TODO:
    }

    private void processDeletedCorpseEntities(Collection<EntityId> entities) {
        // TODO:
    }

    private void processAddedFreeObjectEntities(Collection<Entity> entities) {

        // Add fetch object missions for changed objects
        for (Entity entity : entities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            Point p = WorldUtils.vectorToPoint(entity.get(Position.class).position);
            IMapTileInformation tile = mapController.getMapData().getTile(p);
            short playerId = tile.getOwnerId();
            if (!taskQueues.containsKey(playerId)) {
                continue;
            }
            createFetchObjectTask(entity, playerId);
        }
    }

    private void processDeletedFreeObjectEntities(Collection<EntityId> entities) {
        // TODO:
    }

    private void addListeners(Collection<IPlayerController> players) {

        // We want to be notified on tile changes, we are event based, not constantly scanning type
        mapListener = new MapListener() {

            @Override
            public void onTilesChange(List<Point> updatedTiles) {
                for (Point tile : updatedTiles) {
                    scanTerrainTasks(tile, true, true);
                    scanFetchObjectTasks(tile);
                }
            }

            @Override
            public void onTileFlash(List<Point> points, boolean enabled, short keeperId) {
                // Not interested
            }

            @Override
            public void onTilesReveal(List<Point> points, boolean explore, short keeperId) {
                // Not interested
            }

            @Override
            public void onFogOfWarDisabled(short keeperId) {
                // Not interested
            }

            @Override
            public void onFogOfWarReset(short keeperId) {
                // Not interested
            }

        };
        this.mapController.addListener(mapListener);

        // Bridges! They open up new opportunities in new lands
        // Here we got capturing
        for (IPlayerController player : players) {
            RoomListener listener = new RoomListener() {

                @Override
                public void onBuild(IRoomController room) {

                }

                @Override
                public void onCaptured(IRoomController room) {
                    scanBridgeSurroundings(room);
                }

                @Override
                public void onCapturedByEnemy(IRoomController room) {

                }

                @Override
                public void onSold(IRoomController room) {

                }

                private void scanBridgeSurroundings(IRoomController room) {
                    if (room.getRoom().getFlags().contains(Room.RoomFlag.PLACEABLE_ON_LAVA) || room.getRoom().getFlags().contains(Room.RoomFlag.PLACEABLE_ON_WATER)) {

                        // Just gather the adjacent tiles to the brigde
                        Set<Point> roomPoints = new HashSet<>(room.getRoomInstance().getCoordinates());
                        Set<Point> adjacentPoints = new HashSet<>();
                        for (Point p : roomPoints) {
                            for (Point adjacentPoint : WorldUtils.getSurroundingTiles(mapController.getMapData(), p, false)) {
                                if (!roomPoints.contains(adjacentPoint)) {
                                    adjacentPoints.add(adjacentPoint);
                                }
                            }

                            // Scan all the adjacent tiles for tasks
                            for (Point adjacentPoint : adjacentPoints) {
                                scanTerrainTasks(adjacentPoint, false, true);
                            }
                        }
                    }
                }
            };
            roomListeners.put(player.getKeeper().getId(), listener);
            this.mapController.addListener(player.getKeeper().getId(), listener);
        }

        // Bridges! They open up new opportunities in new lands
        actionListener = new PlayerActionListener() {

            @Override
            public void onBuild(short keeperId, List<Point> tiles) {
                scanBridgeSurroundings(tiles);
            }

            @Override
            public void onSold(short keeperId, List<Point> tiles) {
                scanBridgeSurroundings(tiles);
                for (Point tile : tiles) {
                    scanFetchObjectTasks(tile);
                }
            }

            private void scanBridgeSurroundings(List<Point> tiles) {

                // Check first to see if we are on land or water
                Terrain terrain = mapController.getTerrain(mapController.getMapData().getTile(tiles.get(0)));
                if (terrain.getFlags().contains(Terrain.TerrainFlag.ROOM)) {
                    Room room = levelInfo.getLevelData().getRoomByTerrain(terrain.getTerrainId());
                    if (!(room.getFlags().contains(Room.RoomFlag.PLACEABLE_ON_LAVA) || room.getFlags().contains(Room.RoomFlag.PLACEABLE_ON_WATER))) {
                        return;
                    }
                } else if (!(levelInfo.getLevelData().getMap().getLava().equals(terrain) || levelInfo.getLevelData().getMap().getWater().equals(terrain))) {
                    return;
                }

                // Just gather the adjacent tiles to the brigde
                Set<Point> roomPoints = new HashSet<>(tiles);
                Set<Point> adjacentPoints = new HashSet<>();
                for (Point tile : roomPoints) {
                    for (Point adjacentPoint : WorldUtils.getSurroundingTiles(mapController.getMapData(), tile, false)) {
                        if (adjacentPoint != null) {
                            if (!roomPoints.contains(adjacentPoint)) {
                                adjacentPoints.add(adjacentPoint);
                            }
                        }
                    }

                    // Scan all the adjacent tiles for tasks
                    for (Point mapTile : adjacentPoints) {
                        scanTerrainTasks(mapTile, false, true);
                    }
                }
            }

        };
        this.gameWorldController.addListener(actionListener);
    }

    private void scanInitialTasks() {
        IMapDataInformation<IMapTileController> mapData = mapController.getMapData();
        for (int y = 0; y < mapData.getHeight(); y++) {
            for (int x = 0; x < mapData.getWidth(); x++) {
                scanTerrainTasks(mapData.getTile(x, y), false, false);
            }
        }
    }

    private void scanTerrainTasks(final Point tile, final boolean checkNeighbours, final boolean deleteObsolete) {
        scanTerrainTasks(mapController.getMapData().getTile(tile), tile, checkNeighbours, deleteObsolete);
    }

    private void scanTerrainTasks(final IMapTileController mapTile, final boolean checkNeighbours, final boolean deleteObsolete) {
        scanTerrainTasks(mapTile, mapTile.getLocation(), checkNeighbours, deleteObsolete);
    }

    private void scanTerrainTasks(final IMapTileController mapTile, final Point tile, final boolean checkNeighbours, final boolean deleteObsolete) {
        Terrain terrain = null;
        if (mapTile != null) {
            terrain = mapController.getTerrain(mapTile);
        }

        for (Entry<Short, Set<Task>> entry : taskQueues.entrySet()) {

            // Scan existing tasks that are they valid, should be only one tile task per tile?
            if (deleteObsolete) {
                removeObsoleteTileTasks(entry.getValue());
            }

            if (mapTile == null) {
                continue;
            }

            // Perhaps we should have a store for these, since only one of such per player can exist, would save IDs
            createTerrainTask(entry.getKey(), terrain, mapTile, tile);
        }

        // See the neighbours
        if (checkNeighbours) {
            for (Point p : WorldUtils.getSurroundingTiles(mapController.getMapData(), tile, false)) {
                scanTerrainTasks(p, false, false);
            }
        }
    }

    private void removeObsoleteTileTasks(Set<Task> tasks) {
        Iterator<Task> iter = tasks.iterator();
        while (iter.hasNext()) {
            Task task = iter.next();
            if (task instanceof AbstractTileTask tileTask && tileTask.isRemovable()) {
                iter.remove();
                if (tileTask.getAssigneeCount() == 0) {
                    //tasksByIds.remove(task.getId());
                }
            }
        }
    }

    private void createTerrainTask(short playerId, Terrain terrain, IMapTileController mapTile, Point tile) {

        // Dig
        if (mapController.isSelected(mapTile, playerId)) {
            addTask(playerId, new DigTileTask(taskIds, navigationService, mapController, tile, playerId));
        } // Claim wall
        else if (mapController.isClaimableWall(mapTile, terrain, playerId)) {
            addTask(playerId, new ClaimWallTileTask(taskIds, navigationService, mapController, tile, playerId));
        } // Claim
        else if (mapController.isClaimableTile(mapTile, terrain, playerId)) {
            addTask(playerId, new ClaimTileTask(taskIds, navigationService, mapController, tile, playerId));
        } // Repair wall
        else if (mapController.isRepairableWall(mapTile, terrain, playerId)) {
            addTask(playerId, new RepairWallTileTask(taskIds, navigationService, mapController, tile, playerId));
        } // Claim room
        else if (mapController.isClaimableRoom(mapTile, terrain, playerId)) {
            addTask(playerId, new ClaimRoomTask(taskIds, navigationService, mapController, tile, playerId));
        }
    }

    private void scanFetchObjectTasks(Point tile) {

        // Add the tasks to tile owner and only if the object is not already in storage
        short playerId = mapController.getMapData().getTile(tile).getOwnerId();
        if (!taskQueues.containsKey(playerId)) {
            return;
        }
        for (EntityId entityId : entityPositionLookup.getEntitiesInLocation(tile)) {
            Entity entity = entityData.getEntity(entityId, ObjectComponent.class, Placeable.class, Position.class);
            createFetchObjectTask(entity, playerId);
        }
    }

    private void createFetchObjectTask(Entity entity, short playerId) {
        ObjectComponent objectComponent = entity.get(ObjectComponent.class);
        if (objectComponent != null && objectComponent.objectType != null && entityData.getComponent(entity.getId(), Stored.class) == null) {
            addTask(playerId, getObjectTask(objectsController.createController(entity.getId()), playerId));
        }
    }

    public void addTask(short playerId, Task task) {
        Set<Task> tasks = taskQueues.get(playerId);
        if (!tasks.contains(task)) {
            tasks.add(task);
            tasksByIds.put(task.getId(), task);
            logger.log(Level.INFO, "Added task {0} for player {1}!", new Object[]{task, playerId});
        } else {
            logger.log(Level.WARNING, "Already a task {0} for player {1}!", new Object[]{task, playerId});
        }
    }

    @Override
    public boolean assignPaydayTask(ICreatureController creature) {
        Point current = creature.getCreatureCoordinates();
        List<IRoomController> rooms = new ArrayList<>(mapController.getRoomsByFunction(ObjectType.GOLD, creature.getOwnerId()));
        // A sorted list retains equally distant rooms; a distance-keyed map silently discards them.
        rooms.sort(java.util.Comparator.comparingInt(room -> getShortestDistance(current, room.getRoomInstance().getCoordinates().toArray(new Point[0]))));
        for (IRoomController room : rooms) {
            if (room.isRemoved() || room.isDestroyed() || room.getOwnerId() != creature.getOwnerId()
                    || gameWorldController.getGoldAvailableForPayday(creature.getOwnerId(), room) <= 0) continue;
            for (Point target : room.getRoomInstance().getCoordinates()) {
                if (!room.isTileAccessible(null, target)) continue;
                if (!current.equals(target) && navigationService.findPath(current, target, creature) == null) continue;
                Task task = new CollectPayTask(taskIds, navigationService, mapController,
                        target, creature.getOwnerId(), room, gameWorldController, entityData);
                if (!task.isValid(creature)) continue;
                task.assign(creature, true);
                tasksByIds.put(task.getId(), task);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean assignGoldToTreasuryTask(ICreatureController creature) {

        // See if the creature's player lacks of gold
        IPlayerController player = playerControllers.get(creature.getOwnerId());
        if (!player.getGoldControl().isFullCapacity()) {
            return assignClosestRoomTask(creature, ObjectType.GOLD, null);
        }
        return false;
    }

    @Override
    public boolean assignClosestRoomTask(ICreatureController creature, ObjectType objectType, EntityId target) {
        return assignClosestRoomTask(creature, objectType, target, true);
    }

    /**
     * Assigns closest room task to a given creature of requested type or check
     * for validity
     *
     * @param creature the creature to assign to
     * @param objectType the type of room service
     * @param assign whether to actually assign the creature to the task or just
     * test
     * @return true if the task was assigned
     */
    private boolean assignClosestRoomTask(ICreatureController creature, ObjectType objectType, EntityId targetEntity, boolean assign) {
        Point currentPosition = creature.getCreatureCoordinates();

        // Get all the rooms of the given type
        List<IRoomController> rooms = mapController.getRoomsByFunction(objectType, creature.getOwnerId());
        Map<Integer, IRoomController> distancesToRooms = new TreeMap<>();
        for (IRoomController room : rooms) {
            if (!room.isFullCapacity()) {
                distancesToRooms.put(getShortestDistance(currentPosition, room.getRoomInstance().getCoordinates().toArray(new Point[0])), room
                );
            }
        }

        // See that are they really accessible starting from the least distance one
        for (IRoomController room : distancesToRooms.values()) {

            // FIXME: if we are to have more capacity than one per tile, we need to refactor
            // The whole rooms are always accessible, take a random point from the room like DK II seems to do
            List<Point> coordinates = new ArrayList<>(room.getObjectControl(objectType).getAvailableCoordinates());
            Iterator<Point> iter = coordinates.iterator();
            Map<Point, AbstractCapacityCriticalRoomTask> taskPoints = roomTasks.get(room);
            while (iter.hasNext()) {
                Point p = iter.next();
                if (!room.isTileAccessible(null, p) || (taskPoints != null && taskPoints.containsKey(p))) {
                    iter.remove();
                }
            }

            // Assign
            if (!coordinates.isEmpty()) {
                Point target = Utils.getRandomItem(coordinates, objectsController.getRandom());
                GraphPath<IMapTileInformation> path = navigationService.findPath(creature.getCreatureCoordinates(), target, creature);
                if (path != null || target == creature.getCreatureCoordinates()) {

                    // Assign the task
                    Task task = getRoomTask(objectType, target, targetEntity, creature, room);

                    // See if really assign
                    if (!assign) {
                        return task.isValid(creature);
                    }

                    if (task instanceof AbstractCapacityCriticalRoomTask abstractCapacityCriticalRoomTask) {
                        if (taskPoints == null) {
                            taskPoints = new HashMap<>();
                        }
                        taskPoints.put(target, abstractCapacityCriticalRoomTask);
                        roomTasks.put(room, taskPoints);
                    }
                    task.assign(creature, true);
                    tasksByIds.put(task.getId(), task);

                    return true;
                }
            }
        }

        return false;
    }

    private static Integer getShortestDistance(Point currentPosition, Point... coordinates) {
        int distance = Integer.MAX_VALUE;
        for (Point p : coordinates) {
            // TODO: do we need to do this diagonally?
            distance = Math.min(distance, WorldUtils.calculateDistance(currentPosition, p));
            if (distance == 0) {
                break;
            }
        }
        return distance;
    }

    private AbstractTask getRoomTask(ObjectType objectType, Point target, EntityId targetEntity, ICreatureController creature, IRoomController room) {
        switch (objectType) {
            case GOLD -> {
                return new CarryGoldToTreasuryTask(taskIds, navigationService, mapController, target, creature.getOwnerId(), room, gameWorldController);
            }
            case LAIR -> {
                return new ClaimLair(taskIds, navigationService, mapController, target, creature.getOwnerId(), room, this);
            }
            case RESEARCHER -> {
                return new Research(taskIds, navigationService, mapController, target, creature.getOwnerId(), room, this, playerControllers.get(creature.getOwnerId()).getResearchControl(), objectsController);
            }
            case PRISONER -> {
                return new CarryEnemyCreatureToPrison(taskIds, navigationService, mapController, target, creature.getOwnerId(), room, this, creaturesController.createController(targetEntity));
            }
            case SPECIAL, SPELL_BOOK -> {
                return new CarryObjectToStorageTask(taskIds, navigationService, mapController, target, creature.getOwnerId(), room, this, objectsController.createController(targetEntity));
            }
            case TRAINEE -> {
                return new Train(taskIds, navigationService, mapController, target, creature.getOwnerId(), room, this, gameWorldController, gameSettings, playerControllers.get(creature.getOwnerId()));
            }
            default -> {
                logger.log(Level.DEBUG, "No task defined for " + objectType);
                return null;
            }
        }
    }

    protected void removeRoomTask(AbstractCapacityCriticalRoomTask task) {
        Map<Point, AbstractCapacityCriticalRoomTask> taskPoints = roomTasks.get(task.getRoom());
        if (taskPoints != null) {
            taskPoints.remove(task.getTaskLocation());
            if (taskPoints.isEmpty()) {
                roomTasks.remove(task.getRoom());
                if (task.getAssigneeCount() == 0) {
                    //tasksByIds.remove(task.getId(), task);
                }
            }
        }
    }

    @Override
    public boolean assignObjectiveTask(ICreatureController creature, Thing.HeroParty.Objective objective) {
        AbstractObjectiveTask task = null;
        switch (objective) {
            case SEND_TO_ACTION_POINT: {
                task = new SendToActionPoint(taskIds, navigationService, mapController, levelInfo.getActionPoint(creature.getObjectiveTargetActionPointId()), creature.getOwnerId());
                break;
            }
            case KILL_PLAYER: {
                Short targetPlayerId = creature.getObjectiveTargetPlayerId();
                if (targetPlayerId == null) return false;
                Keeper targetPlayer = levelInfo.getPlayer(targetPlayerId);
                if (targetPlayer == null || targetPlayer.isDestroyed() || targetPlayer.getDungeonHeartLocation() == null) {
                    return false;
                }
                task = new KillPlayer(taskIds, navigationService, mapController, levelInfo, targetPlayerId, creature);
                break;
            }
        }

        // Assign
        if (task != null && task.getCurrentTask() != null) {
            task.getCurrentTask().assign(creature, true);
            tasksByIds.put(task.getId(), task);
            return true;
        }
        return false;
    }

    private AbstractTask getObjectTask(IObjectController gameObject, short playerId) {
        return new FetchObjectTask(taskIds, this, navigationService, mapController, gameObject, playerId);
    }

    @Override
    public boolean isTaskAvailable(ICreatureController creature, Creature.JobType jobType) {
        return assignTask(creature, jobType, false);
    }

    @Override
    public boolean assignTask(ICreatureController creature, Creature.JobType jobType) {
        return assignTask(creature, jobType, true);
    }

    private boolean assignTask(ICreatureController creature, Creature.JobType jobType, boolean assign) {
        switch (jobType) {
            case RESEARCH: {
                return assignClosestRoomTask(creature, ObjectType.RESEARCHER, null, assign);
            }
            case TRAIN: {
                return assignClosestRoomTask(creature, ObjectType.TRAINEE, null, assign);
            }
            default:
                return false;
        }
    }

    @Override
    public boolean assignSleepTask(ICreatureController creature) {
        GoToSleep task = new GoToSleep(taskIds, navigationService, mapController, creature);
        if (task.isReachable(creature)) {
            task.assign(creature, true);
            tasksByIds.put(task.getId(), task);
            return true;
        }
        return false;
    }

    @Override
    public boolean assignEatTask(ICreatureController creature) {

        // The creatures in the original don't seem to make anykind of reservations on the food items, I've seen them "fight over food", first come first serve
        // Hmm, is this a good way to find the food, performance-wise...?
        List<EntityId> foods = new ArrayList<>(entityData.findEntities(new FieldFilter(Owner.class, "ownerId", creature.getOwnerId()), Food.class, Owner.class, Position.class));
        if (foods.isEmpty()) {
            return false; // No food available
        }

        // Sort by manhattan distance in relation to the creature
        Collections.sort(foods, new Comparator<EntityId>() {

            private final Point currentLocation = creature.getCreatureCoordinates();

            @Override
            public int compare(EntityId entityId, EntityId entityId1) {
                int result = Integer.compare(WorldUtils.calculateDistance(currentLocation, entityPositionLookup.getEntityLocation(entityId).getLocation()), WorldUtils.calculateDistance(currentLocation, entityPositionLookup.getEntityLocation(entityId1).getLocation()));
                return result;
            }

        });

        // Pick closest we can actually access
        for (EntityId food : foods) {
            Point target = entityPositionLookup.getEntityLocation(food).getLocation();
            if (target == creature.getCreatureCoordinates() || navigationService.findPath(creature.getCreatureCoordinates(), target, creature) != null) {
                GoToEat task = new GoToEat(taskIds, navigationService, mapController, entityPositionLookup, food, entityData, creature);
                task.assign(creature, true);
                tasksByIds.put(task.getId(), task);
                return true;
            }
        }

        return false;
    }

    @Override
    public Task getTaskById(long taskId) {
        Task task = tasksByIds.get(taskId);

        // For nested task, return the actual task
        if (task != null && task instanceof AbstractObjectiveTask nestedTask) {
            return nestedTask.getCurrentTask();
        }

        return task;
    }

    private IMapTileInformation getEntityPosition(Entity entity) {
        IMapTileInformation mapTile = entityPositionLookup.getEntityLocation(entity.getId());

        // If the entity is new, it might not be in the registry, rather unfortunatety problem...
        if (mapTile == null) {
            mapTile = mapController.getMapData().getTile(WorldUtils.vectorToPoint(EntityController.getPosition(entityData, entity.getId())));
        }

        return mapTile;
    }

    @Override
    public void addToUnemployedWorkerQueue(ICreatureController creature, WorkContinuation workResult) {
        if (staged || closed) throw new IllegalStateException("Task manager is not active");
        unemployedCreatures.put(java.util.Objects.requireNonNull(creature), java.util.Objects.requireNonNull(workResult));
    }

    @Override
    public void processUnemployedWorkerQueue() {
        if (staged || closed) throw new IllegalStateException("Task manager is not active");
        if (unemployedCreatures.isEmpty()) {
            return;
        }

        unemployedCreatures
                .entrySet()
                .stream()
                .collect(Collectors.groupingBy((creature) -> creature.getKey().getOwnerId(), TreeMap::new, Collectors.toList()))
                .entrySet()
                .forEach((creaturesByOwnerId) -> {
                    processUnemployedWorkers(taskQueues.getOrDefault(creaturesByOwnerId.getKey(), Collections.emptySet()), creaturesByOwnerId.getValue());
                });
        unemployedCreatures.clear();
    }

    private void processUnemployedWorkers(Set<Task> tasks, List<Entry<ICreatureController, WorkContinuation>> workers) {
        Set<Task> taskQueue = new java.util.LinkedHashSet<>(tasks);
        Map<ICreatureController, WorkContinuation> unemployedWorkers = workers
                .stream()
                .collect(Collectors.toMap((entry) -> entry.getKey(), (entry) -> entry.getValue(), (a, b) -> b, java.util.LinkedHashMap::new));

        // Process each available task for each available worker
        List<TaskWorkerPriority> priorizedTasks = new LinkedList<>();
        for (Entry<ICreatureController, WorkContinuation> workerEntry : unemployedWorkers.entrySet()) {
            ICreatureController creature = workerEntry.getKey();
            WorkContinuation workResult = workerEntry.getValue();
            final Point currentLocation = creature.getCreatureCoordinates();

            // Give points by distance and priority, no need to know can we do the task as this point as it might be a heavy check
            for (Task task : taskQueue) {
                Vector2f target = task.getTarget(creature);
                if (target == null) {

                    // Can't reach
                    continue;
                }

                int points = WorldUtils.calculateDistance(currentLocation, WorldUtils.vectorToPoint(target)) + task.getPriority();
                priorizedTasks.add(new TaskWorkerPriority(task, creature, points, workResult));
            }
        }

        // Go through the tasks and assign workers
        while (!unemployedWorkers.isEmpty() && !priorizedTasks.isEmpty()) {

            // With each iteration we sort to fill the jobs as even as possible (assignee count changes)
            Collections.sort(priorizedTasks, (o1, o2) -> sortByTaskStatusAndDistance(o1, o2));

            Iterator<TaskWorkerPriority> iter = priorizedTasks.iterator();
            while (iter.hasNext()) {
                TaskWorkerPriority taskWorkerPriority = iter.next();

                // See if the task is full or the worker is not unemployed anymore or that the task can't actually be handled by this creature
                if (!unemployedWorkers.containsKey(taskWorkerPriority.creature)
                        || taskWorkerPriority.task.isFull()
                        || !taskWorkerPriority.task.canAssign(taskWorkerPriority.creature)) {
                    iter.remove();
                    continue;
                }

                // Assign task
                taskWorkerPriority.task.assign(taskWorkerPriority.creature, true);
                taskWorkerPriority.workResult.complete(taskWorkerPriority.creature, true);

                // Prune list and re-sort tasks
                unemployedWorkers.remove(taskWorkerPriority.creature);
                iter.remove();
                break;
            }
        }

        // Finally anybody without assigment will get feedback
        unemployedWorkers.forEach((t, u) -> {
            u.complete(t, false);
        });
    }

    private int sortByTaskStatusAndDistance(TaskWorkerPriority o1, TaskWorkerPriority o2) {

        // Fill in jobs that have no workers first
        int result = Integer.compare(o1.task.getAssigneeCount(), o2.task.getAssigneeCount());
        if (result != 0) {
            return result;
        }

        // Closest creature gets the job
        result = Integer.compare(o1.points, o2.points);
        if (result != 0) {
            return result;
        }

        // If the same, compare by date added
        result = o1.task.getTaskCreated().compareTo(o2.task.getTaskCreated());
        if (result != 0) return result;
        result = Long.compare(o1.task.getId(), o2.task.getId());
        return result != 0 ? result : o1.creature.getEntityId().compareTo(o2.creature.getEntityId());
    }

    private static record TaskWorkerPriority(Task task, ICreatureController creature, int points, WorkContinuation workResult) {

    }

}
