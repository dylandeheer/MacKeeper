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
package toniarts.openkeeper.game.state;

import com.jme3.app.Application;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.math.Vector2f;
import com.simsilica.es.EntityId;
import toniarts.openkeeper.game.data.CampaignLevel;
import toniarts.openkeeper.utils.Point;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import javax.annotation.Nullable;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.controller.IGameWorldController;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.controller.IPlayerController;
import toniarts.openkeeper.game.controller.player.PlayerDoorControl;
import toniarts.openkeeper.game.controller.player.PlayerRoomControl;
import toniarts.openkeeper.game.controller.player.PlayerSpellControl;
import toniarts.openkeeper.game.controller.player.PlayerTrapControl;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.listener.MapListener;
import toniarts.openkeeper.game.listener.PlayerActionListener;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.state.session.GameSessionServerService;
import toniarts.openkeeper.game.state.session.GameSessionServiceListener;
import toniarts.openkeeper.tools.convert.map.Door;
import toniarts.openkeeper.tools.convert.map.KeeperSpell;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.tools.convert.map.Room;
import toniarts.openkeeper.tools.convert.map.Trap;
import toniarts.openkeeper.utils.Utils;

/**
 * The game state that actually runs the game. Has no relation to visuals.
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class GameServerState extends AbstractAppState {

    private static final Logger logger = System.getLogger(GameServerState.class.getName());

    private Main app;

    private Thread loader;
    private AppStateManager stateManager;

    private final Object loadingObject = new Object();
    private volatile boolean gameLoaded = false;
    private boolean closing;
    private boolean startRequested;
    private boolean simulationStarted;
    private final List<Keeper> initialPlayers;
    private final GameLoopManager preparedGame;
    private final java.util.concurrent.CompletableFuture<Void> loadCompletion = new java.util.concurrent.CompletableFuture<>();

    private final IKwdFile kwdFile;
    private final CampaignLevel levelObject;

    private final boolean campaign;
    private final boolean multiplayer;
    private final GameSessionServerService gameService;
    private IMapController mapController;
    private final MapListener mapListener = new MapListenerImpl();
    private final GameSessionServiceListener gameSessionListener = new GameSessionServiceListenerImpl();
    private final PlayerActionListener playerActionListener = new PlayerActionListenerImpl();
    private GameLoopManager game;
    private IGameWorldController gameWorldController;

    /**
     * Single use game states
     *
     * @param level the level to load
     * @param players players participating in this game
     * @param campaign whether this is a campaign level or not
     * @param gameService the game service
     * @param campaignLevel the campaign level, or {@code null} for non-campaign
     */
    public GameServerState(IKwdFile level, List<Keeper> players, boolean campaign, GameSessionServerService gameService, @Nullable toniarts.openkeeper.game.data.CampaignLevel campaignLevel) {
        this(level, players, campaign, gameService, campaignLevel, null);
    }

    /** Own a fully staged independent world without replaying new-game construction or initial client data. */
    public static GameServerState restored(IKwdFile level, boolean campaign,
            toniarts.openkeeper.game.state.session.LocalGameSession service, CampaignLevel campaignLevel, GameLoopManager prepared) {
        if (prepared.getEntityData() != service.getEntityData()) throw new IllegalArgumentException("Restored server store differs");
        return new GameServerState(level, List.of(), campaign, service, campaignLevel, prepared);
    }

    private GameServerState(IKwdFile level, List<Keeper> players, boolean campaign, GameSessionServerService gameService,
            CampaignLevel campaignLevel, GameLoopManager prepared) {
        initialPlayers = players == null ? null : List.copyOf(players);
        preparedGame = prepared;
        this.kwdFile = level;
        this.levelObject = campaignLevel;
        this.campaign = campaign;
        this.gameService = gameService;

        // Set multiplayer
        int humanPlayers = 0;
        if (players != null) {
            for (Keeper player : players) {
                if (!player.isAi()) {
                    humanPlayers++;
                    if (humanPlayers > 1) {
                        break;
                    }
                }
            }
        } else {
            humanPlayers = 1;
        }
        multiplayer = (humanPlayers > 1);

        // Add the listener
        gameService.addGameSessionServiceListener(gameSessionListener);

        // Loading starts after attachment, so a cancelled/unattached state cannot publish a world.
    }

    public boolean isMultiplayer() {
        return multiplayer;
    }

    /** Capture a local world and its exact client under one exclusive simulation boundary. */
    public toniarts.openkeeper.game.save.LocalSessionArchive captureLocalSession(GameClientState client) {
        if (!(gameService instanceof toniarts.openkeeper.game.state.session.LocalGameSession)
                || client.getGameClientService() != gameService || !gameLoaded || game == null
                || stateManager == null || stateManager.getState(GameServerState.class) != this)
            throw new IllegalStateException("The local server/client session is not ready");
        return toniarts.openkeeper.game.save.SimulationState.capture(game, simulation -> {
            var savedClient = client.captureClientState();
            savedClient.progress().validateBinding(toniarts.openkeeper.tools.convert.map.Player.KEEPER1_ID, levelObject);
            return new toniarts.openkeeper.game.save.LocalSessionArchive(campaign,
                    new toniarts.openkeeper.game.save.SimulationArchive(
                            toniarts.openkeeper.game.save.SimulationArchive.Definition.capture(kwdFile), simulation), savedClient);
        });
    }

    public record HeldSession(toniarts.openkeeper.game.save.LocalSessionArchive archive, Runnable release) {}

    /** Capture and hold at one boundary so asynchronous recovery-file I/O cannot lose subsequent game ticks. */
    public HeldSession holdLocalSession(GameClientState client) {
        if (!(gameService instanceof toniarts.openkeeper.game.state.session.LocalGameSession local)
                || client.getGameClientService() != gameService || !gameLoaded || game == null
                || stateManager == null || stateManager.getState(GameServerState.class) != this)
            throw new IllegalStateException("The local session is not ready for replacement");
        boolean admitted = local.isCommandAdmissionEnabled();
        local.setCommandAdmission(false);
        try {
            return toniarts.openkeeper.game.save.SimulationState.capture(game, simulation -> {
                var observed = client.captureClientState();
                observed.progress().validateBinding(toniarts.openkeeper.tools.convert.map.Player.KEEPER1_ID, levelObject);
                var archive = new toniarts.openkeeper.game.save.LocalSessionArchive(campaign,
                        new toniarts.openkeeper.game.save.SimulationArchive(
                                toniarts.openkeeper.game.save.SimulationArchive.Definition.capture(kwdFile), simulation), observed);
                Runnable release = game.holdAtSnapshot();
                return new HeldSession(archive, () -> {release.run();local.setCommandAdmission(admitted);});
            });
        } catch (RuntimeException | Error failure) {local.setCommandAdmission(admitted);throw failure;}
    }

    private void loadGame(List<Keeper> players) {
        loader = new GameLoader(players);
        loader.start();
    }

    @Override
    public void stateAttached(AppStateManager manager) {
        super.stateAttached(manager);
        stateManager = manager;
        if (closing) manager.detach(this);
    }

    @Override
    public void initialize(final AppStateManager stateManager, final Application app) {
        super.initialize(stateManager, app);
        this.app = (Main) app;
        this.stateManager = stateManager;
        synchronized (loadingObject) {
            if (closing) return;
            if (preparedGame != null) publishGame(preparedGame, false);
            else loadGame(initialPlayers);
        }
    }

    public java.util.concurrent.CompletionStage<Void> loadingCompletion() {return loadCompletion.minimalCompletionStage();}

    private void publishGame(GameLoopManager prepared, boolean sendInitialData) {
        // Caller owns loadingObject; cancellation cannot interleave publication or callbacks.
        game = prepared;
        gameWorldController = game.getGameController().getGameWorldController();
        mapController = gameWorldController.getMapController();
        gameWorldController.addListener(playerActionListener);mapController.addListener(mapListener);
        for (IPlayerController player : game.getGameController().getPlayerControllers().values()) player.addListener(gameService);
        gameLoaded = true;
        if (sendInitialData) gameService.sendGameData(game.getGameController().getLevelInfo().getPlayers().values());
        if (startRequested) startSimulation();
        loadCompletion.complete(null);
    }

    private void startSimulation() {
        if (!closing && gameLoaded && !simulationStarted) {
            simulationStarted = true;game.start();
        }
    }

    private void runPlayerCommand(Runnable command) {
        synchronized (loadingObject) {
            if (!closing && gameLoaded) game.runPlayerCommand(command);
        }
    }

    /**
     * If you are getting rid of the game state, use this so that all the related states are detached on the
     * same render loop. Otherwise the app might crash.
     */
    public void detach() {
        GameLoopManager retired;
        synchronized (loadingObject) {
            if (closing) return;
            closing = true;
            if (loader != null) loader.interrupt();
            retired = game != null ? game : preparedGame;
            loadCompletion.completeExceptionally(new java.util.concurrent.CancellationException("Server loading cancelled"));
        }
        gameService.removeGameSessionServiceListener(gameSessionListener);
        if (stateManager != null) stateManager.detach(this);
        if (retired != null) retired.stop();
    }

    @Override
    public void cleanup() {

        // Detach
        detach();

        super.cleanup();
    }

    /**
     * Load the game
     */
    private final class GameLoader extends Thread {

        private final List<Keeper> players;

        public GameLoader(List<Keeper> players) {
            super("GameDataServerLoader");

            this.players = players;
        }

        @Override
        public void run() {
            GameLoopManager prepared = null;
            try {
                prepared = new GameLoopManager(kwdFile, gameService, players);
                synchronized (loadingObject) {
                    if (!closing) {publishGame(prepared, true);return;}
                }
            } catch (RuntimeException failure) {
                boolean report;
                synchronized (loadingObject) {
                    report = !closing;gameLoaded = false;
                    loadCompletion.completeExceptionally(failure);
                }
                if (report) {
                    logger.log(Level.ERROR, "Failed to prepare game server", failure);
                    if (gameService instanceof toniarts.openkeeper.game.state.session.LocalGameSession local) local.failLoading(failure);
                }
            } finally {
                synchronized (loadingObject) {loader = null;}
            }
            if (prepared != null) prepared.stop();
        }
    }

    /**
     * Listen for basically clients' requests
     */
    private final class GameSessionServiceListenerImpl implements GameSessionServiceListener {

        @Override
        public void onStartGame() {
            synchronized (loadingObject) {
                if (closing) return;
                startRequested = true;startSimulation();
            }
        }

        @Override
        public void onSelectTiles(Vector2f start, Vector2f end, boolean select, short playerId) {
            runPlayerCommand(() -> mapController.selectTiles(start, end, select, playerId));
        }

        @Override
        public void onBuild(Vector2f start, Vector2f end, short roomId, short playerId) {
            runPlayerCommand(() -> gameWorldController.build(start, end, playerId, roomId));
        }

        @Override
        public void onSell(Vector2f start, Vector2f end, short playerId) {
            runPlayerCommand(() -> gameWorldController.sell(start, end, playerId));
        }

        @Override
        public void onInteract(EntityId entity, short playerId) {
            runPlayerCommand(() -> gameWorldController.interact(entity, playerId));
        }

        @Override
        public void onPickUp(EntityId entity, short playerId) {
            runPlayerCommand(() -> gameWorldController.pickUp(entity, playerId));
        }

        @Override
        public void onDrop(EntityId entity, Point tile, Vector2f coordinates, EntityId dropOnEntity, short playerId) {
            runPlayerCommand(() -> gameWorldController.drop(entity, tile, coordinates, dropOnEntity, playerId));
        }

        @Override
        public void onCastKeeperSpell(short keeperSpellId, EntityId target, Point tile, Vector2f position, short playerId) {
            runPlayerCommand(() -> gameWorldController.castKeeperSpell(keeperSpellId, target, tile, position, playerId));
        }

        @Override
        public void onPlaceDoor(short doorId, Point tile, short playerId) {
            runPlayerCommand(() -> gameWorldController.placeDoor(doorId, tile, playerId));
        }

        @Override
        public void onPlaceTrap(short trapId, Point tile, short playerId) {
            runPlayerCommand(() -> gameWorldController.placeTrap(trapId, tile, playerId));
        }

        @Override
        public void onTransitionEnd(short playerId) {
            // We are not really interested in this, the status is also tracked in the local clients
        }

        @Override
        public void onPauseRequest(short playerId) {
            // TODO: We should only allow the server owner etc. to pause, otherwise, send a system message that player x wants to pause?
            synchronized (loadingObject) {if (!closing && gameLoaded) game.pause();}

        }

        @Override
        public void onResumeRequest(short playerId) {
            // TODO: We should only allow the server owner etc. to pause, otherwise, send a system message that player x wants to pause?
            synchronized (loadingObject) {if (!closing && gameLoaded) game.resume();}
        }

        @Override
        public void onExitGame(short playerId) {
            // TODO: Close the server and game only when everybody has left
            GameServerState.this.detach();
        }

        @Override
        public void onGetGold(int amount, short playerId) {
            runPlayerCommand(() -> gameWorldController.getGold(amount, playerId));
        }

        @Override
        public void onCheatTriggered(CheatState.CheatType cheat, short playerId) {
            if (isMultiplayer()) {
                return; // No! Bad!
            }

            runPlayerCommand(() -> applyCheat(cheat, playerId));
        }

        private void applyCheat(CheatState.CheatType cheat, short playerId) {
            switch (cheat) {
                case LEVEL_MAX: {
                    gameWorldController.getCreaturesController().levelUpCreatures(playerId, Utils.MAX_CREATURE_LEVEL);
                    break;
                }
                case MANA: {
                    game.getGameController().getPlayerController(playerId).getManaControl().addMana(100000);
                    break;
                }
                case MONEY: {
                    gameWorldController.addGold(playerId, 100000);
                    break;
                }
                case REMOVE_FOW: {
                    mapController.disableFogOfWar(playerId);
                    break;
                }
                case RESET_FOW: {
                    mapController.resetFogOfWar(playerId);
                    break;
                }
                case UNLOCK_ROOMS: {
                    PlayerRoomControl playerRoomControl = game.getGameController()
                            .getPlayerController(playerId).getRoomControl();
                    for (Room room : kwdFile.getRooms()) {
                        playerRoomControl.setTypeAvailable(room, true);
                    }
                    break;
                }
                case UNLOCK_DOORS_TRAPS: {
                    PlayerDoorControl playerDoorControl = game.getGameController()
                            .getPlayerController(playerId).getDoorControl();
                    for (Door door : kwdFile.getDoors()) {
                        playerDoorControl.setTypeAvailable(door, true);
                    }

                    PlayerTrapControl playerTrapControl = game.getGameController()
                            .getPlayerController(playerId).getTrapControl();
                    for (Trap trap : kwdFile.getTraps()) {
                        playerTrapControl.setTypeAvailable(trap, true);
                    }
                    break;
                }
                case UNLOCK_SPELLS: {
                    PlayerSpellControl playerSpellControl = game.getGameController()
                            .getPlayerController(playerId).getSpellControl();
                    for (KeeperSpell keeperSpell : kwdFile.getKeeperSpells()) {
                        playerSpellControl.setTypeAvailable(keeperSpell, true);
                        playerSpellControl.setSpellDiscovered(keeperSpell, true);
                    }
                    break;
                }
                case WIN_LEVEL: {
                    game.getGameController().endGame(playerId, true);
                    break;
                }
                default:
                    logger.log(Level.INFO, "Cheat {0} not implemented!", cheat);
            }
        }
    }

    /**
     * Listen for the map changes
     */
    private final class MapListenerImpl implements MapListener {

        @Override
        public void onTilesChange(List<Point> updatedTiles) {
            gameService.updateTiles(updatedTiles);
        }

        @Override
        public void onTileFlash(List<Point> points, boolean enabled, short keeperId) {
            gameService.flashTiles(points, enabled, keeperId);
        }

        @Override
        public void onTilesReveal(List<Point> points, boolean explore, short keeperId) {
            gameService.revealTiles(points, explore, keeperId);
        }

        @Override
        public void onFogOfWarDisabled(short keeperId) {
            gameService.disableFogOfWar(keeperId);
        }

        @Override
        public void onFogOfWarReset(short keeperId) {
            gameService.resetFogOfWar(keeperId);
        }
    }

    /**
     * Listen for the player actions
     */
    private final class PlayerActionListenerImpl implements PlayerActionListener {

        @Override
        public void onBuild(short keeperId, List<Point> tiles) {
            gameService.onBuild(keeperId, tiles);
        }

        @Override
        public void onSold(short keeperId, List<Point> tiles) {
            gameService.onSold(keeperId, tiles);
        }

    }

}
