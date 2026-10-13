/*
 * Copyright (C) 2014-2017 OpenKeeper
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
package toniarts.openkeeper.game.controller;

import com.badlogic.gdx.ai.GdxAI;
import com.jme3.util.SafeArrayList;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import toniarts.openkeeper.game.controller.player.PlayerResearchControl;
import toniarts.openkeeper.game.data.ActionPoint;
import toniarts.openkeeper.game.data.GameResult;
import toniarts.openkeeper.game.data.GameTimer;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.logic.*;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.navigation.NavigationService;
import toniarts.openkeeper.game.state.session.PlayerService;
import toniarts.openkeeper.game.task.ITaskManager;
import toniarts.openkeeper.game.task.TaskManager;
import toniarts.openkeeper.game.trigger.TriggerControl;
import toniarts.openkeeper.game.trigger.actionpoint.ActionPointTriggerLogicController;
import toniarts.openkeeper.game.trigger.creature.CreatureTriggerLogicController;
import toniarts.openkeeper.game.trigger.door.DoorTriggerLogicController;
import toniarts.openkeeper.game.trigger.object.ObjectTriggerLogicController;
import toniarts.openkeeper.game.trigger.party.PartyTriggerLogicController;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerLogicController;
import toniarts.openkeeper.tools.convert.map.KeeperSpell;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.tools.convert.map.Player;
import toniarts.openkeeper.tools.convert.map.Thing;
import toniarts.openkeeper.tools.convert.map.Variable;

/**
 * The game controller, runs the game simulation itself
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class GameController implements IGameLogicUpdatable, IGameController {

    private static final Logger logger = System.getLogger(GameController.class.getName());

    private final LevelInfo levelInfo;
    private final toniarts.openkeeper.game.random.SimulationRandom random;

    public toniarts.openkeeper.game.random.SimulationRandom getRandom() { return random; }

    private final Map<Short, IPlayerController> playerControllers = HashMap.newHashMap(6);
    private final Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings;
    private final EntityData entityData;
    private final PlayerService playerService;

    private TriggerControl triggerControl = null;
    private final List<IGameLogicUpdatable> controllers = new ArrayList<>();

    private GameWorldController gameWorldController;
    private PaydaySystem paydaySystem;
    private toniarts.openkeeper.game.logic.keeper.KeeperEconomySystem economySystem;
    private GameTimeController gameTimer;
    private INavigationService navigationService;
    private PositionSystem positionSystem;

    private GameResultController gameResults;
    private TaskManager taskManager;
    private toniarts.openkeeper.game.trigger.TriggerRegistries restoredTriggers;
    private List<toniarts.openkeeper.game.controller.creature.ICreatureController> restorePins = List.of();
    private final Map<EntityId, toniarts.openkeeper.game.controller.room.IRoomController> removedRoomBindings = new HashMap<>();

    /**
     * Single use game states
     *
     * @param level the level to load
     * @param players player participating in this game, can be {@code null}
     * @param entityData
     * @param gameSettings
     * @param playerService
     */
    public GameController(IKwdFile level, List<Keeper> players, EntityData entityData,
            Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings,
            PlayerService playerService) {
        this(level, players, entityData, gameSettings, playerService, new toniarts.openkeeper.game.random.SimulationRandom());
    }

    public GameController(IKwdFile level, List<Keeper> players, EntityData entityData,
            Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings, PlayerService playerService,
            toniarts.openkeeper.game.random.SimulationRandom random) {
        this.random = java.util.Objects.requireNonNull(random);
        levelInfo = new LevelInfo(level);
        this.entityData = entityData;
        this.gameSettings = gameSettings;
        this.playerService = playerService;
        if (players != null) {
            for (Keeper keeper : players) {
                levelInfo.players.put(keeper.getId(), keeper);
            }
        }
        createNewGame();
    }

    /** Build real dependencies without invoking new-game setup or running simulation ticks. */
    public static GameController restore(IKwdFile definition, toniarts.openkeeper.game.save.GameEntityData data,
            PlayerService service, toniarts.openkeeper.game.save.SimulationState saved) {
        return new GameController(definition, data, service, saved);
    }

    private GameController(IKwdFile definition, toniarts.openkeeper.game.save.GameEntityData data,
            PlayerService service, toniarts.openkeeper.game.save.SimulationState saved) {
        entityData = data;gameSettings = definition.getVariables();playerService = service;
        random = toniarts.openkeeper.game.random.SimulationRandom.restore(saved.random());
        var keepers = saved.keepers().stream().map(state -> Keeper.restoreState(state, definition.getPlayers().get(state.id()))).toList();
        levelInfo = (LevelInfo) restoreLevelInfo(definition, saved.level().level(), keepers);
        gameTimer = new GameTimeController(saved.level().clock());
        gameResults = GameResultController.restoreState(saved.results(), gameTimer, service, levelInfo.players.keySet());
        LevelObjectives.validate(data, levelInfo.players.keySet());
        try {
            gameWorldController = GameWorldController.restoreBindings(this, levelInfo, data, playerControllers, gameTimer, saved);
            var map = gameWorldController.getMapController();var objects = gameWorldController.getObjectsController();
            var creatures = (CreaturesController) gameWorldController.getCreaturesController();
            for (var state : saved.players()) playerControllers.put(state.playerId(), PlayerController.restoreState(
                    definition, levelInfo.players.get(state.playerId()), data, state, map::getRoomController));
            gameWorldController.bindPlayerRoomListeners();
            positionSystem = PositionSystem.restoreState(map, data, creatures, gameWorldController.getDoorsController(), objects, saved.systems().positions());
            gameWorldController.setEntityPositionLookup(positionSystem);
            navigationService = new NavigationService(map, positionSystem, random);
            var roomStates = new ArrayList<toniarts.openkeeper.game.data.RoomState>(saved.systems().taskGraph().removedRooms());
            roomStates.addAll(saved.systems().recruitment().removedRooms());roomStates.addAll(saved.systems().chickenSpawning().removedRooms());
            for (var state : roomStates) {
                if (map.getRoomController(state.entityId()) != null) throw new IllegalArgumentException("Removed room remains active");
                var previous = removedRoomBindings.get(state.entityId());
                if (previous != null) {
                    if (!state.equals(((toniarts.openkeeper.game.controller.room.AbstractRoomController)previous).captureState()))
                        throw new IllegalArgumentException("Conflicting removed room state");
                } else removedRoomBindings.put(state.entityId(), RoomControllerFactory.restoreRemovedRoom(
                        data, definition, state, objects, gameSettings, gameTimer));
            }
            var allocation = toniarts.openkeeper.game.task.TaskIdentityAllocator.forSimulation(saved.systems().taskAllocation(), gameTimer);
            taskManager = TaskManager.stageRestore(data, gameWorldController, map, objects, creatures, navigationService,
                    playerControllers.values(), levelInfo, positionSystem, gameSettings, allocation);
            var graph = toniarts.openkeeper.game.save.TaskGraphSnapshots.restoreShells(saved.systems().taskGraph(),
                    new toniarts.openkeeper.game.save.TaskGraphSnapshots.Context(allocation, data, definition, navigationService,
                            map, this::getRestoredRoom, taskManager, gameWorldController, positionSystem, objects,
                            playerControllers::get, levelInfo));
            restorePins = creatures.restoreCreatureControllers(saved.systems().creatureControllers(), graph.bindings());
            graph.bindCreatures(creatures::getBoundController);
            taskManager.bindRestoredState(saved.systems().taskManager(), graph, creatures::getBoundController, this::getRestoredRoom);
            creatures.restoreParties(saved.systems().parties());
            paydaySystem = PaydaySystem.restoreState(data, definition, gameTimer, gameSettings, new PaydaySystem.State(levelInfo.paydayCycle));
            restoredTriggers = toniarts.openkeeper.game.trigger.TriggerRegistries.restore(saved.triggers(), this, levelInfo, gameTimer,
                    map, creatures, objects, gameWorldController.getDoorsController(), positionSystem, service, data);
            controllers.add(restoredTriggers);
            economySystem = toniarts.openkeeper.game.logic.keeper.KeeperEconomySystem.restoreState(gameWorldController, definition,
                    playerControllers.values(), data, saved.systems().economy());
            controllers.add(economySystem);controllers.add(gameTimer);controllers.add(positionSystem);
        } catch (RuntimeException | Error failure) {
            for (var system : new IGameLogicUpdatable[]{restoredTriggers, economySystem, paydaySystem,
                    (IGameLogicUpdatable)taskManager, positionSystem}) {
                if (system != null) try {system.stop();} catch (RuntimeException | Error cleanup) {if (failure != cleanup) failure.addSuppressed(cleanup);}
            }
            restorePins = List.of();throw failure;
        }
    }

    public toniarts.openkeeper.game.controller.room.IRoomController getRestoredRoom(EntityId id) {
        var removed = removedRoomBindings.get(id);
        return removed != null ? removed : gameWorldController.getMapController().getRoomController(id);
    }

    /** All task, party, trigger and AI owners must have bound before these temporary pins are released. */
    public void finishRestoreBindings() { restorePins = List.of(); }

    public void createNewGame() {
        levelInfo.load();
        // The players
        setupPlayers();

        gameTimer = new GameTimeController();
        gameResults = new GameResultController(gameTimer, playerService, levelInfo.players.keySet());
        // The world
        gameWorldController = new GameWorldController(this, levelInfo, entityData, gameSettings, playerControllers, gameTimer);

        positionSystem = new PositionSystem(gameWorldController.getMapController(), entityData,
                gameWorldController.getCreaturesController(), gameWorldController.getDoorsController(),
                gameWorldController.getObjectsController());
        gameWorldController.setEntityPositionLookup(positionSystem);

        // Navigation
        navigationService = new NavigationService(gameWorldController.getMapController(), positionSystem, random);

        // Initialize tasks
        taskManager = new TaskManager(entityData, gameWorldController, gameWorldController.getMapController(),
                gameWorldController.getObjectsController(), gameWorldController.getCreaturesController(),
                navigationService, playerControllers.values(), levelInfo, positionSystem, gameSettings,
                toniarts.openkeeper.game.task.TaskIdentityAllocator.forSimulation(new toniarts.openkeeper.game.task.TaskIdentityAllocator.State(0), gameTimer));

        paydaySystem = new PaydaySystem(entityData, levelInfo.kwdFile, gameTimer, gameSettings);

        // The triggers
        controllers.add(new PartyTriggerLogicController(this, levelInfo, gameTimer,
                gameWorldController.getMapController(), gameWorldController.getCreaturesController()));
        controllers.add(new CreatureTriggerLogicController(this, levelInfo, gameTimer,
                gameWorldController.getMapController(), gameWorldController.getCreaturesController(),
                playerService, entityData));
        controllers.add(new ObjectTriggerLogicController(this, levelInfo, gameTimer,
                gameWorldController.getMapController(), gameWorldController.getCreaturesController(),
                playerService, entityData, gameWorldController.getObjectsController()));
        controllers.add(new DoorTriggerLogicController(this, levelInfo, gameTimer,
                gameWorldController.getMapController(), gameWorldController.getCreaturesController(),
                playerService, entityData, gameWorldController.getDoorsController()));
        controllers.add(new ActionPointTriggerLogicController(this, levelInfo, gameTimer,
                gameWorldController.getMapController(), gameWorldController.getCreaturesController(),
                positionSystem));
        controllers.add(new PlayerTriggerLogicController(this, levelInfo, gameTimer,
                gameWorldController.getMapController(), gameWorldController.getCreaturesController(),
                playerService));
        economySystem = new toniarts.openkeeper.game.logic.keeper.KeeperEconomySystem(
                gameWorldController, levelInfo.kwdFile, playerControllers.values(), entityData);
        controllers.add(economySystem);
        controllers.add(gameTimer);
        controllers.add(positionSystem);

        int triggerId = levelInfo.kwdFile.getGameLevel().getTriggerId();
        if (triggerId != 0) {
            triggerControl = new TriggerControl(this, levelInfo, gameTimer, gameWorldController.getMapController(), gameWorldController.getCreaturesController(), triggerId);
        }

    }

    public Map<toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Kind, toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State> captureTriggerRegistries() {
        if (restoredTriggers != null) return restoredTriggers.captureState();
        var result = new java.util.EnumMap<toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Kind, toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State>(toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Kind.class);
        result.put(toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Kind.LEVEL,
                new toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State(toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Kind.LEVEL,
                        triggerControl == null ? List.of() : List.of(toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Entry.level(triggerControl.captureState())), List.of()));
        for (var controller : controllers) if (controller instanceof toniarts.openkeeper.game.trigger.TriggerStateSource source) {
            var state = source.captureRegistry();result.put(state.kind(), state);
        }
        return Map.copyOf(result);
    }

    /** Trees only; registry owner references still require a separate snapshot. */
    public Map<String, List<toniarts.openkeeper.game.save.TriggerSnapshots.State>> captureTriggerTrees() {
        if (restoredTriggers != null) return restoredTriggers.captureTrees();
        var result = new java.util.TreeMap<String, List<toniarts.openkeeper.game.save.TriggerSnapshots.State>>();
        result.put("level", triggerControl == null ? List.of() : List.of(triggerControl.captureState()));
        for (var controller : controllers) if (controller instanceof toniarts.openkeeper.game.trigger.TriggerStateSource source)
            result.put(controller.getClass().getSimpleName(), source.captureTrees());
        return Map.copyOf(result);
    }

    public toniarts.openkeeper.game.save.KeeperEconomySnapshots.State captureEconomyState() {
        return economySystem.captureState();
    }

    private void setupPlayers() {
        // Setup players
        boolean addMissingPlayers = levelInfo.players.isEmpty(); // Add all if none is given (campaign...)
        for (Map.Entry<Short, Player> entry : levelInfo.kwdFile.getPlayers().entrySet()) {
            Keeper keeper = null;
            if (levelInfo.players.containsKey(entry.getKey())) {
                keeper = levelInfo.players.get(entry.getKey());
                keeper.setPlayer(entry.getValue());
            } else if (addMissingPlayers || entry.getKey() < Player.KEEPER1_ID) {
                keeper = new Keeper(entry.getValue());
                levelInfo.players.put(entry.getKey(), keeper);
            }

            // Init
            if (keeper != null) {
                PlayerController playerController = new PlayerController(levelInfo.kwdFile, keeper, levelInfo.kwdFile.getImp(), entityData, gameSettings);
                playerControllers.put(entry.getKey(), playerController);

                // Spells are all available for research unless otherwise stated
                for (KeeperSpell spell : levelInfo.kwdFile.getKeeperSpells()) {
                    if (spell.getBonusRTime() != 0) {
                        playerController.getSpellControl().setTypeAvailable(spell, true, false);
                    }
                }
            }
        }

        // Set the alliances
        for (Variable.PlayerAlliance playerAlliance : levelInfo.kwdFile.getPlayerAlliances()) {
            short player1 = (short) playerAlliance.getPlayerIdOne();
            short player2 = (short) playerAlliance.getPlayerIdTwo();
            createAlliance(player1, player2);
        }

        // Set player availabilities
        // TODO: the player customized game settings
        for (Variable.Availability availability : levelInfo.kwdFile.getAvailabilities()) {
            if (availability.getPlayerId() == 0) {

                // All players
                for (Keeper player : levelInfo.players.values()) {
                    setAvailability(player, availability);
                }
            } else {
                Keeper player = levelInfo.players.get((short) availability.getPlayerId());

                // Not all the players are participating...
                if (player != null) {
                    setAvailability(player, availability);
                }
            }
        }

        // Initialize the player research
        for (IPlayerController playerController : playerControllers.values()) {
            PlayerResearchControl researchControl = playerController.getResearchControl();
            if (researchControl != null) {
                researchControl.initialize();
            }
        }
    }

    private void setAvailability(Keeper player, Variable.Availability availability) {
        IPlayerController playerController = playerControllers.get(player.getId());
        boolean available = availability.getValue() == Variable.Availability.AvailabilityValue.AVAILABLE || availability.getValue() == Variable.Availability.AvailabilityValue.RESEARCHABLE;
        boolean discovered = availability.getValue() == Variable.Availability.AvailabilityValue.AVAILABLE;
        switch (availability.getType()) {
            case CREATURE: {
                playerController.getCreatureControl().setTypeAvailable(levelInfo.kwdFile.getCreature((short) availability.getTypeId()), available);
                break;
            }
            case ROOM: {
                playerController.getRoomControl().setTypeAvailable(levelInfo.kwdFile.getRoomById((short) availability.getTypeId()), available, discovered);
                break;
            }
            case SPELL: {
                playerController.getSpellControl().setTypeAvailable(levelInfo.kwdFile.getKeeperSpellById(availability.getTypeId()), available, discovered);
                break;
            }
            case DOOR: {
                playerController.getDoorControl().setTypeAvailable(levelInfo.kwdFile.getDoorById((short) availability.getTypeId()), available, discovered);
                break;
            }
            case TRAP: {
                playerController.getTrapControl().setTypeAvailable(levelInfo.kwdFile.getTrapById((short) availability.getTypeId()), available, discovered);
                break;
            }
        }
    }

    @Override
    public IPlayerController getPlayerController(short playerId) {
        return playerControllers.get(playerId);
    }

    @Override
    public Map<Short, IPlayerController> getPlayerControllers() {
        return playerControllers;
    }

    @Override
    public void processTick(float tpf) {

        // Update time for AI
        GdxAI.getTimepiece().update(tpf);

        // Time limit is a special timer, it just ticks towards 0 if it is set
        if (levelInfo.timeLimit != null) {
            levelInfo.timeLimit -= tpf;

            // We rest on zero
            if (levelInfo.timeLimit < 0) {
                levelInfo.timeLimit = 0f;
            }
        }

        // Advance game timers
        for (GameTimer timer : levelInfo.timers.getArray()) {
            timer.update(tpf);
        }

        LevelObjectives.reconcile(entityData, levelInfo.players);
        paydaySystem.processTick(tpf);
        levelInfo.paydayCycle = paydaySystem.getCycle();
        if (triggerControl != null) {
            triggerControl.update(tpf);
        }

        controllers.stream().forEach(controller -> controller.processTick(tpf));

        if (levelInfo.timeLimit != null && levelInfo.timeLimit <= 0) {
            //TODO:
            throw new RuntimeException("Level time limit exceeded!");
        }
    }

    @Override
    public void endGame(short playerId, boolean win) {
        gameResults.endGame(playerId, win);
    }

    @Override public int countDoors(short playerId, short typeId, int flags) {
        return DefenseCounts.doors(entityData, playerId, typeId, flags);
    }

    @Override public int countTraps(short playerId, short typeId, int flags) {
        return DefenseCounts.traps(entityData, levelInfo.getLevelData(), playerId, typeId, flags);
    }

    @Override public void markPlayerObjective(short playerId) {
        if (levelInfo.players.containsKey(playerId)) LevelObjectives.addPlayer(entityData, playerId);
    }

    @Override public void markCreatureObjective(EntityId creatureId, toniarts.openkeeper.game.data.ObjectiveType objective) {
        if (creatureId == null) return;
        if (objective == null) LevelObjectives.removeCreature(entityData, creatureId);
        else if (entityData.getComponent(creatureId, toniarts.openkeeper.game.component.CreatureComponent.class) != null)
            LevelObjectives.addCreature(entityData, creatureId, objective);
    }

    public toniarts.openkeeper.game.save.ResultSnapshots.State captureResults() {
        return gameResults.captureState();
    }

    @Override
    public ITaskManager getTaskManager() {
        return taskManager;
    }

    /**
     * Get level variable value
     *
     * @param variable the variable type
     * @return variable value
     */
    @Override
    public float getLevelVariable(Variable.MiscVariable.MiscType variable) {
        // TODO: player is able to change these, so need a wrapper and store these to GameState
        return levelInfo.kwdFile.getVariables().get(variable).getValue();
    }

    public <T> T getContoller(Class<T> clazz) {
        return (T) controllers.stream()
                .filter(controller -> controller.getClass().isInstance(clazz))
                .findFirst().orElseThrow(() -> new IndexOutOfBoundsException(
                "Game contoller have not child of class " + clazz));
    }

    /**
     * Creates alliance between two players
     *
     * @param playerOneId player ID 1
     * @param playerTwoId player ID 2
     */
    @Override
    public void createAlliance(short playerOneId, short playerTwoId) {
        if (levelInfo.players.containsKey(playerOneId)) {
            levelInfo.players.get(playerOneId).createAlliance(playerTwoId);
        }
        if (levelInfo.players.containsKey(playerTwoId)) {
            levelInfo.players.get(playerTwoId).createAlliance(playerOneId);
        }
    }

    /**
     * Breaks alliance between two players
     *
     * @param playerOneId player ID 1
     * @param playerTwoId player ID 2
     */
    @Override
    public void breakAlliance(short playerOneId, short playerTwoId) {
        if (levelInfo.players.containsKey(playerOneId)) {
            levelInfo.players.get(playerOneId).breakAlliance(playerTwoId);
        }
        if (levelInfo.players.containsKey(playerTwoId)) {
            levelInfo.players.get(playerTwoId).breakAlliance(playerOneId);
        }
    }

    @Override
    public GameResult getGameResult() {
        return getGameResult(Player.KEEPER1_ID);
    }

    @Override
    public GameResult getGameResult(short playerId) {
        return gameResults.getResult(playerId);
    }

    @Override
    public void setPossession(EntityId target, short playerId) {
        levelInfo.players.get(playerId).setPossession(target != null);
        playerService.setPossession(target, playerId);
    }

    @Override
    public IGameWorldController getGameWorldController() {
        return gameWorldController;
    }

    @Override
    public void start() {
        controllers.stream().forEach(IGameLogicUpdatable::start);
    }

    @Override
    public void stop() {
        var owned = new ArrayList<IGameLogicUpdatable>(controllers);
        owned.add(paydaySystem);
        try {new toniarts.openkeeper.game.logic.GameLogicManager(owned.toArray(IGameLogicUpdatable[]::new)).stop();}
        finally {controllers.clear();}
    }

    @Override
    public INavigationService getNavigationService() {
        return navigationService;
    }

    @Override
    public IEntityPositionLookup getEntityLookupService() {
        return positionSystem;
    }

    @Override
    public ILevelInfo getLevelInfo() {
        return levelInfo;
    }

    @Override
    public IGameTimer getGameTimer() {
        return gameTimer;
    }

    /** Staged level metadata; this does not create or replace a running world. */
    public static ILevelInfo restoreLevelInfo(IKwdFile definition,
            toniarts.openkeeper.game.data.LevelState state, Collection<Keeper> keepers) {
        java.util.Objects.requireNonNull(definition);java.util.Objects.requireNonNull(state);
        LevelInfo result = new LevelInfo(definition);
        for (Keeper keeper : keepers) {
            if (result.players.putIfAbsent(keeper.getId(), keeper) != null) {
                throw new IllegalArgumentException("Duplicate restored keeper");
            }
        }
        result.loadActionPoints();
        result.flags.addAll(state.flags());
        for (var timer : state.timers()) result.timers.add(GameTimer.restoreState(timer));
        result.levelScore = state.score();result.timeLimit = state.timeLimit();result.paydayCycle = state.paydayCycle();
        return result;
    }

    private static class LevelInfo implements ILevelInfo {

        private static final int LEVEL_TIMER_MAX_COUNT = 16;
        private static final int LEVEL_FLAG_MAX_COUNT = 128;

        private final IKwdFile kwdFile;
        private long paydayCycle;

        @Override public long getPaydayCycle() { return paydayCycle; }

        private int levelScore = 0;
        private Float timeLimit = null;

        private final SortedMap<Short, Keeper> players = new TreeMap<>();
        private final List<Integer> flags = new ArrayList<>(LEVEL_FLAG_MAX_COUNT);
        private final SafeArrayList<GameTimer> timers = new SafeArrayList<>(GameTimer.class, LEVEL_TIMER_MAX_COUNT);
        private final Map<Integer, ActionPoint> actionPointsById = new HashMap<>();
        private final List<ActionPoint> actionPoints = new ArrayList<>();

        public LevelInfo(IKwdFile kwdFile) {
            this.kwdFile = kwdFile;
        }

        public void load() {
            // Action points
            loadActionPoints();
            // Triggers data
            for (short i = 0; i < LEVEL_FLAG_MAX_COUNT; i++) {
                flags.add(i, 0);
            }
            // Timers data
            for (byte i = 0; i < LEVEL_TIMER_MAX_COUNT; i++) {
                timers.add(i, new GameTimer());
            }
        }

        private void loadActionPoints() {
            for (Thing.ActionPoint thing : kwdFile.getThings(Thing.ActionPoint.class)) {
                ActionPoint ap = new ActionPoint(thing);
                actionPointsById.put(ap.getId(), ap);
                actionPoints.add(ap);
            }
        }

        @Override
        public IKwdFile getLevelData() {
            return kwdFile;
        }

        @Override
        public int getFlag(int id) {
            return flags.get(id);
        }

        @Override
        public void setFlag(int id, int value) {
            flags.set(id, value);
        }

        @Override
        public GameTimer getTimer(int id) {
            return timers.get(id);
        }

        @Override
        public Float getTimeLimit() {
            return timeLimit;
        }

        @Override
        public void setTimeLimit(float time) {
            timeLimit = time;
        }

        /**
         * Get level score, not really a player score... kinda
         *
         * @return the level score
         */
        @Override
        public int getLevelScore() {
            return levelScore;
        }

        @Override
        public void setLevelScore(int score) {
            levelScore = score;
        }

        @Override
        public ActionPoint getActionPoint(int id) {
            return actionPointsById.get(id);
        }

        @Override
        public List<ActionPoint> getActionPoints() {
            return actionPoints;
        }

        @Override
        public Keeper getPlayer(short playerId) {
            return players.get(playerId);
        }

        @Override
        public Map<Short, Keeper> getPlayers() {
            return players;
        }
    }

}
