/*
 * Copyright (C) 2014-2025 OpenKeeper
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
package toniarts.openkeeper.game.state.loop;

import com.simsilica.es.EntityData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import toniarts.openkeeper.utils.SimulationGate;
import toniarts.openkeeper.game.controller.GameController;
import toniarts.openkeeper.game.controller.IGameController;
import toniarts.openkeeper.game.controller.IGameWorldController;
import toniarts.openkeeper.game.controller.ILevelInfo;
import toniarts.openkeeper.game.controller.IPlayerController;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.logic.*;
import toniarts.openkeeper.game.state.session.GameSessionServerService;
import toniarts.openkeeper.game.task.ITaskManager;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.tools.convert.map.Variable;
import toniarts.openkeeper.utils.GameLoop;

/**
 *
 * @author ArchDemon
 */
public final class GameLoopManager {

    private final List<GameLoop> loops = new ArrayList<>();
    private final SimulationGate simulationGate;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.CompletableFuture<Void> shutdown = new java.util.concurrent.CompletableFuture<>();

    private final GameSessionServerService gameService;

    private final IGameController gameController;
    private final PlayerCreatureSystem playerCreatureSystem;
    private final PlayerSpellbookSystem playerSpellbookSystem;
    private final ChickenAiSystem chickenAiSystem;
    private final CreatureAiSystem creatureAiSystem;
    private final ManaCalculatorLogic manaSystem;
    private final CreatureExperienceSystem experienceSystem;
    private final CreatureSpawnSystem creatureSpawnSystem;
    private final ChickenSpawnSystem chickenSpawnSystem;
    private final DecaySystem decaySystem;
    private final DeathSystem deathSystem;
    private final SlapSystem slapSystem;
    private final HealthSystem healthSystem;
    private final CreatureFallSystem fallSystem;
    private final CreatureViewSystem creatureViewSystem;
    private final MovementSystem movementSystem;
    private final DungeonHeartConstruction heartConstruction;
    private final CreatureTorturingSystem tortureSystem;

    /** Implemented system sections; not yet the complete simulation state. */
    public record SystemState(PlayerCreatureSystem.State creatures, PlayerSpellbookSystem.State spellbooks, ChickenAiSystem.SavedState chickens,
            List<toniarts.openkeeper.game.data.CreatureControlState> creatureControllers, CreatureAiSystem.SavedState creatureAi, toniarts.openkeeper.game.data.PartyRegistryState parties, toniarts.openkeeper.game.task.TaskIdentityAllocator.State taskAllocation,
            List<toniarts.openkeeper.game.save.TileTaskSnapshots.State> tileTasks, toniarts.openkeeper.game.save.RoomTaskSnapshots.Capture roomTasks, toniarts.openkeeper.game.save.EntityTaskSnapshots.Capture entityTasks, toniarts.openkeeper.game.save.TaskGraphSnapshots.State taskGraph, toniarts.openkeeper.game.save.TaskManagerSnapshots.State taskManager, toniarts.openkeeper.game.save.ManaSnapshots.State mana, toniarts.openkeeper.game.save.ExperienceSnapshots.State experience, toniarts.openkeeper.game.save.KeeperEconomySnapshots.State economy, toniarts.openkeeper.game.save.CreatureSpawnSnapshots.State recruitment, toniarts.openkeeper.game.save.ChickenSpawnSnapshots.State chickenSpawning, toniarts.openkeeper.game.save.LifetimeSnapshots.State lifetimes, toniarts.openkeeper.game.save.SlapSnapshots.State slaps, toniarts.openkeeper.game.save.HealthSnapshots.State health, toniarts.openkeeper.game.save.AnimationSnapshots.State animation, toniarts.openkeeper.game.save.LifetimeSnapshots.Clock creatureView, toniarts.openkeeper.game.save.MovementSnapshots.State movement, toniarts.openkeeper.game.save.PositionSnapshots.State positions) {
        public SystemState {creatureControllers = List.copyOf(creatureControllers);tileTasks = List.copyOf(tileTasks);}
    }

    /**
     * Single use game states
     *
     * @param level the level to load
     * @param gameService
     * @param players player participating in this game, can be {@code null}
     */
    public GameLoopManager(IKwdFile level, GameSessionServerService gameService, List<Keeper> players) {
        this(level, gameService, players, new toniarts.openkeeper.game.random.SimulationRandom());
    }

    public GameLoopManager(IKwdFile level, GameSessionServerService gameService, List<Keeper> players,
            toniarts.openkeeper.game.random.SimulationRandom random) {
        this.gameService = gameService;
        simulationGate = sessionGate(gameService);
        final EntityData entityData = gameService.getEntityData();
        final Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings = level.getVariables();

        gameController = new GameController(level, players, gameService.getEntityData(), gameSettings, gameService, random);
        final ILevelInfo levelInfo = gameController.getLevelInfo();
        final IGameWorldController gameWorldController = gameController.getGameWorldController();
        final IEntityPositionLookup positionSystem = gameController.getEntityLookupService();
        final Map<Short, IPlayerController> playerControllers = gameController.getPlayerControllers();
        final ITaskManager taskManager = gameController.getTaskManager();

        // Game logic
        final GameLogicManager gameLogicThread = new GameLogicManager(
                gameWorldController.getMapController(),
                decaySystem = new DecaySystem(entityData),
                new WoodenBridgeDecaySystem(entityData, gameController.getGameTimer(), gameWorldController,
                        level, gameWorldController.getMapController(),
                        gameController.getLevelVariable(Variable.MiscVariable.MiscType.WOOD_BRIDGE_LIFE_ON_LAVA_SECONDS)),
                experienceSystem = new CreatureExperienceSystem(entityData, levelInfo.getLevelData(), gameSettings,
                        gameWorldController.getCreaturesController()),
                new CreatureMoodSystem(entityData, levelInfo.getLevelData(), playerControllers.values()),
                slapSystem = new SlapSystem(entityData, levelInfo.getLevelData(), playerControllers.values(), gameSettings),
                healthSystem = new HealthSystem(entityData, positionSystem, gameSettings, gameWorldController.getCreaturesController(),
                        levelInfo, playerControllers.values(), gameWorldController.getMapController(), gameWorldController),
                tortureSystem = new CreatureTorturingSystem(entityData, gameWorldController.getCreaturesController(),
                        gameWorldController.getMapController()),
                deathSystem = new DeathSystem(entityData, gameSettings, positionSystem),
                playerCreatureSystem = new PlayerCreatureSystem(entityData, levelInfo.getLevelData(), playerControllers.values()),
                playerSpellbookSystem = new PlayerSpellbookSystem(entityData, levelInfo.getLevelData(), playerControllers.values()),
                (IGameLogicUpdatable) gameController,
                creatureSpawnSystem = new CreatureSpawnSystem(gameWorldController.getCreaturesController(), playerControllers.values(),
                        gameSettings, levelInfo, gameWorldController.getMapController()),
                chickenSpawnSystem = new ChickenSpawnSystem(entityData, gameWorldController.getObjectsController(),
                        playerControllers.values(), gameSettings, levelInfo, gameWorldController.getMapController()),
                manaSystem = new ManaCalculatorLogic(playerControllers.values(), entityData),
                creatureAiSystem = new CreatureAiSystem(entityData, gameWorldController.getCreaturesController(), taskManager),
                chickenAiSystem = new ChickenAiSystem(entityData, gameWorldController.getObjectsController()),
                creatureViewSystem = new CreatureViewSystem(entityData),
                new DoorViewSystem(entityData, positionSystem),
                new LooseObjectSystem(entityData, gameWorldController.getMapController(), playerControllers, positionSystem, gameWorldController),
                new HaulingSystem(entityData),
                (IGameLogicUpdatable) taskManager);

        loops.add(new GameLoop(gameLogicThread, 1_000_000_000 / levelInfo.getLevelData().getGameLevel().getTicksPerSec(), "Logic", simulationGate));

        // Animation systems
        final GameLogicManager gameAnimationThread = new GameLogicManager(
                heartConstruction = new DungeonHeartConstruction(
                        entityData,
                        gameController.getLevelVariable(Variable.MiscVariable.MiscType.TIME_BEFORE_DUNGEON_HEART_CONSTRUCTION_BEGINS)),
                fallSystem = new CreatureFallSystem(entityData));
        loops.add(new GameLoop(gameAnimationThread, GameLoop.INTERVAL_FPS_60, "Animation", simulationGate));

        // Steering
        loops.add(new GameLoop(new GameLogicManager(movementSystem = new MovementSystem(entityData)), GameLoop.INTERVAL_FPS_60, "Steering", simulationGate));
    }

    /** Stage an independent server world; the service factory must bind exactly the supplied store. */
    public static GameLoopManager restore(IKwdFile definition, toniarts.openkeeper.game.save.SimulationState saved,
            Function<toniarts.openkeeper.game.save.GameEntityData, GameSessionServerService> serviceFactory) throws java.io.IOException {
        saved.validateLoopRates(definition.getGameLevel().getTicksPerSec());
        var data = toniarts.openkeeper.game.save.GameEntityData.restore(saved.entities());
        try {
            var service = java.util.Objects.requireNonNull(serviceFactory.apply(data));
            if (service.getEntityData() != data) throw new IllegalArgumentException("Restored service must use the staged store");
            return new GameLoopManager(definition, service, data, saved);
        } catch (RuntimeException | Error failure) {data.close();throw failure;}
    }

    private GameLoopManager(IKwdFile definition, GameSessionServerService service,
            toniarts.openkeeper.game.save.GameEntityData data, toniarts.openkeeper.game.save.SimulationState saved) {
        gameService = service;
        simulationGate = sessionGate(service);
        if (service instanceof toniarts.openkeeper.game.state.session.SimulationSession session) session.restoreTransitions(saved.transitions());
        else if (!saved.transitions().pendingPlayers().isEmpty()) throw new IllegalArgumentException("Saved transitions require a receiving session");
        var staged = new ArrayList<IGameLogicUpdatable>();
        try {
            var game = track(staged, GameController.restore(definition, data, service, saved));gameController = game;
            var level = game.getLevelInfo();var world = game.getGameWorldController();var map = world.getMapController();
            var positions = game.getEntityLookupService();var players = game.getPlayerControllers();
            var creatures = (toniarts.openkeeper.game.controller.CreaturesController)world.getCreaturesController();
            var objects = (toniarts.openkeeper.game.controller.ObjectsController)world.getObjectsController();
            var tasks = track(staged, (toniarts.openkeeper.game.task.TaskManager)game.getTaskManager());var state = saved.systems();
            decaySystem = track(staged, DecaySystem.restoreState(data, state.lifetimes().decay()));
            var bridge = track(staged, WoodenBridgeDecaySystem.restoreBindings(data, game.getGameTimer(), world));
            experienceSystem = track(staged, CreatureExperienceSystem.restoreState(data, definition, creatures, state.experience()));
            var mood = track(staged, new CreatureMoodSystem(data, definition, players.values()));
            slapSystem = track(staged, SlapSystem.restoreState(data, definition, players.values(), state.slaps()));
            healthSystem = track(staged, HealthSystem.restoreState(data, positions, creatures, level, players.values(), map, world, state.health()));
            tortureSystem = track(staged, CreatureTorturingSystem.restoreState(data, creatures, map, state.lifetimes().torture()));
            deathSystem = track(staged, DeathSystem.restoreState(data, positions, state.lifetimes().death()));
            playerCreatureSystem = track(staged, PlayerCreatureSystem.restoreState(data, definition, players.values(), state.creatures()));
            playerSpellbookSystem = track(staged, PlayerSpellbookSystem.restoreState(data, definition, players.values(), state.spellbooks()));
            creatureSpawnSystem = track(staged, CreatureSpawnSystem.restoreState(creatures, players.values(), definition, map, data, game::getRestoredRoom, state.recruitment()));
            chickenSpawnSystem = track(staged, ChickenSpawnSystem.restoreState(data, objects, players.values(), map, game::getRestoredRoom, state.chickenSpawning()));
            manaSystem = track(staged, ManaCalculatorLogic.restoreState(players.values(), data, state.mana()));
            creatureAiSystem = track(staged, CreatureAiSystem.restoreState(data, creatures, tasks, state.creatureAi()));
            chickenAiSystem = track(staged, ChickenAiSystem.restoreState(data, objects, state.chickens()));
            creatureViewSystem = track(staged, CreatureViewSystem.restoreState(data, state.creatureView()));
            var doors = track(staged, new DoorViewSystem(data, positions));
            var loose = track(staged, new LooseObjectSystem(data, map, players, positions, world));
            var hauling = track(staged, new HaulingSystem(data));
            var logic = new GameLogicManager(map, decaySystem, bridge, experienceSystem, mood, slapSystem, healthSystem,
                    tortureSystem, deathSystem, playerCreatureSystem, playerSpellbookSystem, game,
                    creatureSpawnSystem, chickenSpawnSystem, manaSystem, creatureAiSystem, chickenAiSystem,
                    creatureViewSystem, doors, loose, hauling, tasks);
            heartConstruction = track(staged, DungeonHeartConstruction.restoreState(data, state.animation().heart()));
            fallSystem = track(staged, CreatureFallSystem.restoreState(data, state.animation().fall()));
            movementSystem = track(staged, MovementSystem.restoreState(data, state.movement()));
            loops.add(new GameLoop(logic, saved.loops().get(0), "Logic", simulationGate));
            loops.add(new GameLoop(new GameLogicManager(heartConstruction, fallSystem), saved.loops().get(1), "Animation", simulationGate));
            loops.add(new GameLoop(new GameLogicManager(movementSystem), saved.loops().get(2), "Steering", simulationGate));
            game.finishRestoreBindings();
        } catch (RuntimeException | Error failure) {
            for (var system : staged.reversed()) {
                try {system.stop();} catch (RuntimeException | Error cleanup) {if (failure != cleanup) failure.addSuppressed(cleanup);}
            }
            throw failure;
        }
    }

    private static <T extends IGameLogicUpdatable> T track(List<IGameLogicUpdatable> staged, T system) {
        staged.add(system);return system;
    }

    private static SimulationGate sessionGate(GameSessionServerService service) {
        return service instanceof toniarts.openkeeper.game.state.session.SimulationSession session
                ? session.getTransitions().gate() : new SimulationGate();
    }

    public toniarts.openkeeper.game.state.session.SessionTransitions.State captureTransitions() {
        return gameService instanceof toniarts.openkeeper.game.state.session.SimulationSession session
                ? session.getTransitions().capture() : new toniarts.openkeeper.game.state.session.SessionTransitions.State(java.util.Set.of());
    }

    public EntityData getEntityData() { return gameService.getEntityData(); }

    /** Run a complete player command under the same capture boundary as all loops. */
    private boolean heldForReplacement;

    public void runPlayerCommand(Runnable command) {
        simulationGate.mutate(() -> {
            if (closed) throw new IllegalStateException("Game is closed");
            if (heldForReplacement) throw new IllegalStateException("Game is held for saved-session replacement");
            command.run();
        });
    }

    /**
     * Capture detached state once all simulation systems have started. No loop
     * or player command can mutate the world inside this callback. Encode and
     * write the returned snapshot after leaving this boundary.
     */
    public <T> T captureSnapshot(Function<IGameController, T> capture) {
        return simulationGate.snapshot(() -> {
            if (closed || loops.stream().anyMatch(loop -> !loop.isInitialized())) {
                throw new IllegalStateException("Game is not ready for a snapshot");
            }
            return capture.apply(gameController);
        });
    }

    /** Capture the last-observed creature index under the same boundary as its player controls and ECS. */
    public <T> T captureSnapshot(java.util.function.BiFunction<IGameController, PlayerCreatureSystem.State, T> capture) {
        return captureSnapshot(game -> capture.apply(game, playerCreatureSystem.captureState()));
    }

    /** Call only from the exclusive snapshot callback; all phases use its common timestamp. */
    public List<toniarts.openkeeper.utils.GameLoopClock.State> captureLoopPhases() {
        return loops.stream().map(GameLoop::capturePhase).toList();
    }

    /** Capture implemented system sections together with controllers and ECS. */
    public <T> T captureSnapshotWithSystems(java.util.function.BiFunction<IGameController, SystemState, T> capture) {
        return captureSnapshot(game -> {
            var references = new toniarts.openkeeper.game.save.TaskNodeReferences.Capture();
            var creatures = (toniarts.openkeeper.game.controller.CreaturesController) game.getGameWorldController().getCreaturesController();
            var controllers = creatures.captureControllerStates(references);
            var tasks = (toniarts.openkeeper.game.task.TaskManager) game.getTaskManager();
            var managerState = tasks.captureState(references);
            return capture.apply(game, new SystemState(playerCreatureSystem.captureState(), playerSpellbookSystem.captureState(),
                    chickenAiSystem.captureState(), controllers, creatureAiSystem.captureState(), creatures.captureParties(),
                    tasks.captureAllocationState(), tasks.captureTileTaskStates(), tasks.captureRoomTaskStates(references.nodes()), tasks.captureEntityTaskStates(references.nodes()), tasks.captureTaskGraph(references), managerState, manaSystem.captureState(), experienceSystem.captureState(), ((GameController) game).captureEconomyState(), creatureSpawnSystem.captureState(), chickenSpawnSystem.captureState(), new toniarts.openkeeper.game.save.LifetimeSnapshots.State(decaySystem.captureTime(), tortureSystem.captureTime(), deathSystem.captureState()), slapSystem.captureState(), healthSystem.captureState(), new toniarts.openkeeper.game.save.AnimationSnapshots.State(fallSystem.captureTime(), heartConstruction.captureState()), creatureViewSystem.captureTime(), movementSystem.captureState(), ((PositionSystem) game.getEntityLookupService()).captureState()));
        });
    }

    /** Called inside the joined snapshot boundary; release preserves each loop's original pause state. */
    public Runnable holdAtSnapshot() {
        var phases = captureLoopPhases();
        if (heldForReplacement) throw new IllegalStateException("Game already held for replacement");
        loops.forEach(GameLoop::holdAtSnapshot);heldForReplacement = true;
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!released.compareAndSet(false, true)) return;
            simulationGate.mutate(() -> {
                if (closed) return;
                heldForReplacement = false;
                for (int i = 0; i < loops.size(); i++) if (!phases.get(i).paused()) loops.get(i).resume();
            });
        };
    }

    public void pause() {
        runPlayerCommand(() -> {
            loops.forEach(GameLoop::pause);
            gameService.setGamePaused(true);
        });
    }

    public void resume() {
        runPlayerCommand(() -> {
            loops.forEach(GameLoop::resume);
            gameService.setGamePaused(false);
        });
    }

    public void start() {
        runPlayerCommand(() -> {
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("Game has already started");
            loops.forEach(GameLoop::start);
        });
    }

    public void stop() {
        // Close admission under the boundary, then join outside it so workers
        // can acquire the update lock for their cleanup.
        boolean closeOwner = simulationGate.snapshot(() -> {
            if (closed) return false;
            closed = true;
            return true;
        });
        if (closeOwner) {
            try {
                loops.forEach(GameLoop::requestStop);
                Throwable cleanupFailure = null;
                for (var loop : loops) {
                    try {loop.stop();} catch (RuntimeException | Error cleanup) {
                        if (cleanupFailure == null) cleanupFailure = cleanup;else if (cleanupFailure != cleanup) cleanupFailure.addSuppressed(cleanup);
                    }
                }
                loops.clear();
                if (cleanupFailure instanceof Error error) throw error;
                if (cleanupFailure instanceof RuntimeException exception) throw exception;
                shutdown.complete(null);
            } catch (RuntimeException | Error failure) {
                shutdown.completeExceptionally(failure);
                throw failure;
            }
        } else {
            // Every caller must wait for cleanup, even when another thread
            // closed admission. join() preserves the caller's interrupt flag.
            shutdown.join();
        }
    }

    public IGameController getGameController() {
        return gameController;
    }

}
