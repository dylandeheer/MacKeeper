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
    private final SimulationGate simulationGate = new SimulationGate();
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.CompletableFuture<Void> shutdown = new java.util.concurrent.CompletableFuture<>();

    private final GameSessionServerService gameService;

    private final IGameController gameController;
    private final PlayerCreatureSystem playerCreatureSystem;
    private final PlayerSpellbookSystem playerSpellbookSystem;
    private final ChickenAiSystem chickenAiSystem;
    private final CreatureAiSystem creatureAiSystem;

    /** Implemented system sections; not yet the complete simulation state. */
    public record SystemState(PlayerCreatureSystem.State creatures, PlayerSpellbookSystem.State spellbooks, ChickenAiSystem.SavedState chickens,
            List<toniarts.openkeeper.game.data.CreatureControlState> creatureControllers, CreatureAiSystem.SavedState creatureAi, toniarts.openkeeper.game.data.PartyRegistryState parties, toniarts.openkeeper.game.task.TaskIdentityAllocator.State taskAllocation,
            List<toniarts.openkeeper.game.save.TileTaskSnapshots.State> tileTasks) {
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
        this.gameService = gameService;
        final EntityData entityData = gameService.getEntityData();
        final Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings = level.getVariables();

        gameController = new GameController(level, players, gameService.getEntityData(), gameSettings, gameService);
        final ILevelInfo levelInfo = gameController.getLevelInfo();
        final IGameWorldController gameWorldController = gameController.getGameWorldController();
        final IEntityPositionLookup positionSystem = gameController.getEntityLookupService();
        final Map<Short, IPlayerController> playerControllers = gameController.getPlayerControllers();
        final ITaskManager taskManager = gameController.getTaskManager();

        // Game logic
        final GameLogicManager gameLogicThread = new GameLogicManager(
                gameWorldController.getMapController(),
                new DecaySystem(entityData),
                new WoodenBridgeDecaySystem(entityData, gameController.getGameTimer(), gameWorldController,
                        level, gameWorldController.getMapController(),
                        gameController.getLevelVariable(Variable.MiscVariable.MiscType.WOOD_BRIDGE_LIFE_ON_LAVA_SECONDS)),
                new CreatureExperienceSystem(entityData, levelInfo.getLevelData(), gameSettings,
                        gameWorldController.getCreaturesController()),
                new CreatureMoodSystem(entityData, levelInfo.getLevelData(), playerControllers.values()),
                new SlapSystem(entityData, levelInfo.getLevelData(), playerControllers.values(), gameSettings),
                new HealthSystem(entityData, positionSystem, gameSettings, gameWorldController.getCreaturesController(),
                        levelInfo, playerControllers.values(), gameWorldController.getMapController(), gameWorldController),
                new CreatureTorturingSystem(entityData, gameWorldController.getCreaturesController(),
                        gameWorldController.getMapController()),
                new DeathSystem(entityData, gameSettings, positionSystem),
                playerCreatureSystem = new PlayerCreatureSystem(entityData, levelInfo.getLevelData(), playerControllers.values()),
                playerSpellbookSystem = new PlayerSpellbookSystem(entityData, levelInfo.getLevelData(), playerControllers.values()),
                (IGameLogicUpdatable) gameController,
                new CreatureSpawnSystem(gameWorldController.getCreaturesController(), playerControllers.values(),
                        gameSettings, levelInfo, gameWorldController.getMapController()),
                new ChickenSpawnSystem(entityData, gameWorldController.getObjectsController(),
                        playerControllers.values(), gameSettings, levelInfo, gameWorldController.getMapController()),
                new ManaCalculatorLogic(playerControllers.values(), entityData),
                creatureAiSystem = new CreatureAiSystem(entityData, gameWorldController.getCreaturesController(), taskManager),
                chickenAiSystem = new ChickenAiSystem(entityData, gameWorldController.getObjectsController()),
                new CreatureViewSystem(entityData),
                new DoorViewSystem(entityData, positionSystem),
                new LooseObjectSystem(entityData, gameWorldController.getMapController(), playerControllers, positionSystem, gameWorldController),
                new HaulingSystem(entityData),
                (IGameLogicUpdatable) taskManager);

        loops.add(new GameLoop(gameLogicThread, 1_000_000_000 / levelInfo.getLevelData().getGameLevel().getTicksPerSec(), "Logic", simulationGate));

        // Animation systems
        final GameLogicManager gameAnimationThread = new GameLogicManager(
                new DungeonHeartConstruction(
                        entityData,
                        gameController.getLevelVariable(Variable.MiscVariable.MiscType.TIME_BEFORE_DUNGEON_HEART_CONSTRUCTION_BEGINS)),
                new CreatureFallSystem(entityData));
        loops.add(new GameLoop(gameAnimationThread, GameLoop.INTERVAL_FPS_60, "Animation", simulationGate));

        // Steering
        loops.add(new GameLoop(new GameLogicManager(new MovementSystem(entityData)), GameLoop.INTERVAL_FPS_60, "Steering", simulationGate));
    }

    /** Run a complete player command under the same capture boundary as all loops. */
    public void runPlayerCommand(Runnable command) {
        simulationGate.mutate(() -> {
            if (closed) throw new IllegalStateException("Game is closed");
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

    /** Capture implemented system sections together with controllers and ECS. */
    public <T> T captureSnapshotWithSystems(java.util.function.BiFunction<IGameController, SystemState, T> capture) {
        return captureSnapshot(game -> capture.apply(game, new SystemState(
                playerCreatureSystem.captureState(), playerSpellbookSystem.captureState(), chickenAiSystem.captureState(), ((toniarts.openkeeper.game.controller.CreaturesController) game.getGameWorldController().getCreaturesController()).captureControllerStates(new toniarts.openkeeper.game.save.TaskNodeReferences.Capture()), creatureAiSystem.captureState(), ((toniarts.openkeeper.game.controller.CreaturesController) game.getGameWorldController().getCreaturesController()).captureParties(),
                ((toniarts.openkeeper.game.task.TaskManager) game.getTaskManager()).captureAllocationState(), ((toniarts.openkeeper.game.task.TaskManager) game.getTaskManager()).captureTileTaskStates())));
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
                loops.forEach(GameLoop::stop);
                loops.clear();
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
