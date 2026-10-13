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
import com.jme3.app.state.AppStateManager;
import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import toniarts.openkeeper.game.data.CampaignLevel;
import toniarts.openkeeper.utils.Point;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.data.CampaignLevel;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.data.ResearchableEntity;
import toniarts.openkeeper.game.data.ResearchableType;
import toniarts.openkeeper.game.map.IMapInformation;
import toniarts.openkeeper.view.fogofwar.IFogOfWarInformation;
import toniarts.openkeeper.game.state.loading.IPlayerLoadingProgress;
import toniarts.openkeeper.game.state.loading.MultiplayerLoadingState;
import toniarts.openkeeper.game.state.loading.SingleBarLoadingState;
import toniarts.openkeeper.game.state.lobby.ClientInfo;
import toniarts.openkeeper.game.state.session.GameSessionClientService;
import toniarts.openkeeper.game.state.session.GameSessionListener;
import toniarts.openkeeper.game.state.session.PresentationEvent;
import toniarts.openkeeper.game.state.session.PresentationQueue;
import toniarts.openkeeper.game.state.session.QueuedGameSessionListener;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.tools.convert.map.TriggerAction;
import toniarts.openkeeper.tools.convert.map.Variable;
import toniarts.openkeeper.utils.AssetUtils;
import toniarts.openkeeper.view.PlayerCameraState;
import toniarts.openkeeper.view.PlayerEntityViewState;
import toniarts.openkeeper.view.PlayerMapViewState;
import toniarts.openkeeper.view.SystemMessageState;
import toniarts.openkeeper.view.text.TextParser;
import toniarts.openkeeper.view.text.TextParserService;

import javax.annotation.Nullable;

/**
 * The game client state
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class GameClientState extends AbstractPauseAwareState {

    private static final Logger logger = System.getLogger(GameClientState.class.getName());

    private final Main app;
    private final toniarts.openkeeper.game.state.session.ClientState restored;
    private int restorePhase;
    private boolean inputSuppressed;
    private boolean priorIgnoreMouse, priorIgnoreKeyboard;

    private AppStateManager stateManager;

    private final IKwdFile kwdFile;

    private final Map<Short, Keeper> players = new TreeMap<>();
    private final Object loadingObject = new Object();
    private volatile boolean gameStarted = false;
    private volatile boolean closing;
    private volatile Throwable loadFailure;
    private volatile List<Keeper> preparedPlayers;
    private Thread dataLoader;
    private boolean receivedGameData;
    private boolean mapReady;
    private boolean readySent;
    private final java.util.concurrent.CompletableFuture<Void> loadCompletion = new java.util.concurrent.CompletableFuture<>();

    private final Short playerId;
    private final boolean multiplayer;
    private IPlayerLoadingProgress loadingState;
    private final GameSessionClientService gameClientService;
    private final PresentationQueue pendingPresentation = new PresentationQueue();
    private final GameSessionListenerImpl presentationTarget = new GameSessionListenerImpl();
    private final QueuedGameSessionListener gameSessionListener = new QueuedGameSessionListener(pendingPresentation, presentationTarget);
    private Thread renderThread;
    private boolean transferBlocked;
    private IMapInformation mapInformation;
    private IFogOfWarInformation fogOfWarInformation;
    private PlayerState playerState;
    @Nullable
    private final CampaignLevel campaignLevel;

    private PlayerMapViewState playerMapViewState;
    private PlayerEntityViewState playerModelViewState;
    private TextParser textParser;
    @Override
    public void update(float tpf) {
        if (closing || transferBlocked || stateManager.getState(GameClientState.class) != this) return;
        if (loadFailure != null) {
            logger.log(Level.ERROR, "Failed to load the game", loadFailure);
            detach();
            MainMenuState menu = stateManager.getState(MainMenuState.class);
            if (menu != null) menu.setEnabled(true);
            return;
        }
        if (!gameStarted) {
            try { advanceLoading(); }
            catch (RuntimeException failure) { failLoading(failure); }
            return;
        }
        if (playerState == null) return;
        playerState.updateScreenRouting();
        if (!loadCompletion.isDone() && playerState.isScreenReady()) loadCompletion.complete(null);
        pendingPresentation.drain(() -> {
            PlayerCameraState camera = stateManager.getState(PlayerCameraState.class);
            return !playerState.isEnabled() || camera == null || camera.isInitialized();
        }, event -> {
            try {event.deliver(presentationTarget);}
            catch (RuntimeException failure) {logger.log(Level.ERROR, "Failed to apply client presentation event " + event.kind(), failure);}
        });
    }

    /** Call on the render thread inside the server snapshot boundary for a complete local capture. */
    public PresentationQueue.State capturePendingPresentation() {
        requireRenderThread();
        if (!gameStarted || !isInitialized()) throw new IllegalStateException("Client is not ready for capture");
        return pendingPresentation.capture();
    }

    /** Stage pending notifications without rendering or acknowledging them. */
    public void restorePendingPresentation(PresentationQueue.State state) {
        requireRenderThread();pendingPresentation.restore(state);
    }

    /** Capture on the render thread inside the server gate when joining a local session snapshot. */
    public toniarts.openkeeper.game.state.session.ClientModelState captureClientModel() {
        requireReadyClient();
        return new toniarts.openkeeper.game.state.session.ClientModelState(playerId,
                players.values().stream().map(Keeper::captureState).toList(), pendingPresentation.capture());
    }

    /** Restore observed model values without consuming pending events or rebuilding views. */
    public void restoreClientModel(toniarts.openkeeper.game.state.session.ClientModelState saved) {
        requireReadyClient();
        if (saved.playerId() != playerId) throw new IllegalArgumentException("Client model belongs to another player");
        saved.restore(players, pendingPresentation, kwdFile);
    }

    /** All implemented logical owners, observed on the render thread without draining notifications. */
    public toniarts.openkeeper.game.state.session.ClientState captureClientState() {
        requireReadyClient();
        var screen = playerState.captureScreenState();
        toniarts.openkeeper.game.state.session.ClientState.Active active = null;
        if (screen.playerEnabled()) {
            var camera = stateManager.getState(PlayerCameraState.class);
            var messages = stateManager.getState(SystemMessageState.class);
            var interaction = stateManager.getState(toniarts.openkeeper.view.PlayerInteractionState.class);
            var console = stateManager.getState(toniarts.openkeeper.game.console.ConsoleState.class);
            if (camera == null || messages == null || interaction == null || console == null || !console.isInitialized())
                throw new IllegalStateException("Client owners are still loading");
            active = new toniarts.openkeeper.game.state.session.ClientState.Active(camera.captureState(), messages.captureState(),
                    playerState.getScreen().captureHudState(), interaction.captureSelection(),
                    playerState.getScreen().captureCreatureCards(), interaction.captureHandView(), console.isEnabled());
        }
        return new toniarts.openkeeper.game.state.session.ClientState(captureClientModel(), playerState.captureProgressState(),
                screen, playerMapViewState.captureFogState(), stateManager.getState(SoundState.class).captureState(),
                new toniarts.openkeeper.view.WorldViewState(playerId, kwdFile.getMap().getWidth(), kwdFile.getMap().getHeight(),
                        playerMapViewState.captureFlashes(), playerModelViewState.captureFlowers()), active);
    }

    private void requireReadyClient() {
        requireRenderThread();
        if (closing || !isInitialized() || !gameStarted || stateManager.getState(GameClientState.class) != this)
            throw new IllegalStateException("Client is not ready for model restoration");
    }

    private void requireRenderThread() {
        if (renderThread == null || Thread.currentThread() != renderThread)
            throw new IllegalStateException("Client presentation state belongs to the render thread");
    }

    private boolean activePresentation() {return playerState != null && playerState.isEnabled();}

    /** Audio start effects are typed data, delivered only by this live session's render-thread owner. */
    public void onSpeechStarted(int speechId, toniarts.openkeeper.game.sound.SoundSnapshot.StartAction action) {
        requireRenderThread();
        if (!activePresentation() || stateManager.getState(GameClientState.class) != this) return;
        SystemMessageState messages = stateManager.getState(SystemMessageState.class);
        if (messages != null) messages.addMessage(SystemMessageState.MessageType.INFO, String.format("${level.%d}", speechId - 1));
        if (action.showText()) playerState.setText(speechId, action.introduction(), action.pathId());
    }


    /**
     * Single use game states
     *
     * @param level the level to load
     * @param playerId our player ID
     * @param players players participating in this game
     * @param gameClientService client services
     * @param app the main application
     * @param campaignLevel the campaign level, or {@code null} for non-campaign
     */
    public GameClientState(IKwdFile level, Short playerId, List<ClientInfo> players, GameSessionClientService gameClientService, Main app, @Nullable CampaignLevel campaignLevel) {
        this(level, playerId, players, gameClientService, app, campaignLevel, null);
    }

    public static GameClientState restored(IKwdFile level, toniarts.openkeeper.game.state.session.LocalGameSession service,
            Main app, toniarts.openkeeper.game.state.session.ClientState saved) {
        var info = new ClientInfo(0, null, 0);
        info.setKeeper(saved.model().bind(level).get(saved.model().playerId()));
        var branch = saved.progress().campaign();
        return new GameClientState(level, saved.model().playerId(), List.of(info), service, app,
                branch == null ? null : branch.bind(), saved);
    }

    private GameClientState(IKwdFile level, Short playerId, List<ClientInfo> players, GameSessionClientService gameClientService,
            Main app, CampaignLevel campaignLevel, toniarts.openkeeper.game.state.session.ClientState restored) {
        this.restored = restored;
        this.kwdFile = level;
        this.gameClientService = gameClientService;
        this.playerId = playerId;
        this.app = (Main) app;
        this.campaignLevel = campaignLevel;

        // Set multiplayer
        int humanPlayers = 0;
        for (ClientInfo clientInfo : players) {
            if (!clientInfo.getKeeper().isAi()) {
                humanPlayers++;
                if (humanPlayers > 1) {
                    break;
                }
            }
        }
        multiplayer = (humanPlayers > 1);

        // Create the loading state
        loadingState = createLoadingState(app);

        // Add the listener
        gameClientService.addGameSessionListener(gameSessionListener);

        // Tell that we are ready to start receiving game data
        gameClientService.markReady();
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
        this.stateManager = stateManager;
        renderThread = Thread.currentThread();
        if (closing) return;
        if (restored != null) {
            priorIgnoreMouse = this.app.getNifty().isIgnoreMouseEvents();
            priorIgnoreKeyboard = this.app.getNifty().isIgnoreKeyboardEvents();
            this.app.getNifty().setIgnoreMouseEvents(true);this.app.getNifty().setIgnoreKeyboardEvents(true);
            inputSuppressed = true;
            gameSessionListener.onGameDataLoaded(restored.model().bind(kwdFile).values());
        }

        // Attach the loading state finally
        if (!gameStarted) {
            synchronized (loadingObject) {
                if (!gameStarted) {
                    stateManager.attach(loadingState);
                }
            }
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
    }

    private void detachRelatedAppStates() {
        if (playerModelViewState != null) stateManager.detach(playerModelViewState);
        if (playerMapViewState != null) {
            playerMapViewState.cancelLoading();
            stateManager.detach(playerMapViewState);
            // JME never calls cleanup for a state detached before its initialization.
            if (!playerMapViewState.isInitialized()) playerMapViewState.cleanup();
        }
        if (playerState != null) {
            EndGameState end = stateManager.getState(EndGameState.class);
            if (end != null && end.belongsTo(playerState)) stateManager.detach(end);
            stateManager.detach(playerState);
            if (!playerState.isInitialized()) playerState.cleanup();
        }
    }

    /** Cancel first so obsolete workers and queued completion callbacks cannot publish a session. */
    public void detach() {
        synchronized (loadingObject) {
            if (closing) return;
            closing = true;
            preparedPlayers = null;
            if (dataLoader != null) dataLoader.interrupt();
            loadingObject.notifyAll();
        }
        gameClientService.removeGameSessionListener(gameSessionListener);
        // JME skips cleanup if cancellation precedes initialization; retire the server here too.
        gameClientService.exitGame();
        loadCompletion.completeExceptionally(new java.util.concurrent.CancellationException("Game loading cancelled"));
        if (stateManager != null) {
            stateManager.detach(this);
            if (loadingState != null) stateManager.detach(loadingState);
            detachRelatedAppStates();
            SoundState sound = stateManager.getState(SoundState.class);
            if (sound != null && sound.isInitialized()) sound.unbindSession(this);
            restoreInputPolicy();
        }
        pendingPresentation.close();
        playerState = null;playerMapViewState = null;playerModelViewState = null;
    }

    /** Completion follows settled HUD routing, not merely receipt of server data. */
    public java.util.concurrent.CompletionStage<Void> loadingCompletion() { return loadCompletion.minimalCompletionStage(); }

    private void failLoading(Throwable failure) {
        synchronized (loadingObject) {
            if (closing || loadFailure != null) return;
            loadFailure = failure;loadingObject.notifyAll();
        }
        loadCompletion.completeExceptionally(failure);
    }

    private void advanceLoading() {
        if (preparedPlayers != null && playerState == null) {
            List<Keeper> prepared = preparedPlayers;preparedPlayers = null;
            createViews(prepared);
        }
        if (!readySent && mapReady && playerState != null && playerState.isInitialized()
                && playerMapViewState.isInitialized() && playerModelViewState.isInitialized()) {
            app.getRenderManager().preloadScene(app.getRootNode());
            if (restored != null) {advanceRestore();return;}
            readySent = true;gameClientService.loadComplete();
        }
    }

    private void createViews(List<Keeper> received) {
        requireRenderThread();
        for (Keeper keeper : received) {
            keeper.setPlayer(kwdFile.getPlayer(keeper.getId()));players.put(keeper.getId(), keeper);
        }
        playerState = restored == null
                ? new PlayerState(playerId, kwdFile, gameClientService.getEntityData(), false, app, campaignLevel)
                : new PlayerState(playerId, kwdFile, gameClientService.getEntityData(), false, app, campaignLevel, restored.progress());
        playerMapViewState = new PlayerMapViewState(app, kwdFile, app.getAssetManager(), received,
                gameClientService.getEntityData(), playerId, () -> { if (!closing) mapReady = true; },
                gameSessionListener::onEnemySighted, GameClientState.this::failLoading, restored == null ? null : restored.fog()) {
            private float lastProgress;
            @Override protected void updateProgress(float progress) {
                if (closing) return;
                presentationTarget.onLoadStatusUpdate(progress, playerId);
                if (progress - lastProgress >= .01f) {gameClientService.loadStatus(progress);lastProgress = progress;}
            }
        };
        mapInformation = playerMapViewState.getMapInformation();
        fogOfWarInformation = playerMapViewState.getFogOfWarInformation();
        textParser = new TextParserService(mapInformation, playerMapViewState.getRoomsInformation());
        playerModelViewState = new PlayerEntityViewState(kwdFile, app.getAssetManager(), gameClientService.getEntityData(),
                playerId, textParser, app.getRootNode(), mapInformation.getMapData(), fogOfWarInformation);
        if (restored != null) playerModelViewState.setEnabled(false);
        playerMapViewState.addFogOfWarTilesDirtyListener(playerModelViewState::onTilesDirty);
        stateManager.attach(playerState);stateManager.attach(playerMapViewState);stateManager.attach(playerModelViewState);
    }

    /** Input stays unavailable until the replacement client has all owners and its requested screen. */
    public boolean isAcceptingInput() {return gameStarted && !closing && !transferBlocked;}

    /** Temporary transfer ownership suppresses raw input and pending-event delivery without retiring the client. */
    public void setTransferBlocked(boolean blocked) {
        requireRenderThread();
        if (closing || stateManager.getState(GameClientState.class) != this)
            throw new IllegalStateException("Transfer belongs to the current client");
        var camera = stateManager.getState(PlayerCameraState.class);
        if (camera != null && camera.isInitialized()) camera.resetTransferInput();
        var interaction = stateManager.getState(toniarts.openkeeper.view.PlayerInteractionState.class);
        if (interaction != null && interaction.isInitialized()) interaction.resetTransferInput();
        var end = stateManager.getState(EndGameState.class);
        if (end != null && end.isInitialized() && end.belongsTo(playerState)) end.restoreAwaitingInput();
        transferBlocked = blocked;
    }

    private void restoreInputPolicy() {
        if (!inputSuppressed) return;
        app.getNifty().resetMouseInputEvents();
        app.getNifty().setIgnoreMouseEvents(priorIgnoreMouse);app.getNifty().setIgnoreKeyboardEvents(priorIgnoreKeyboard);
        inputSuppressed = false;
    }

    private void advanceRestore() {
        if (restorePhase == 0) {
            if (restored.active() != null) playerState.setEnabled(true);
            else stateManager.attach(EndGameState.restored(playerState));
            restorePhase = 1;return;
        }
        playerState.updateScreenRouting();
        if (restorePhase == 1) {
            if (restored.active() != null) {
                if (!playerState.isScreenReady() || stateManager.getState(PlayerCameraState.class) == null
                        || !stateManager.getState(PlayerCameraState.class).isInitialized()
                        || stateManager.getState(toniarts.openkeeper.view.PlayerInteractionState.class) == null
                        || !stateManager.getState(toniarts.openkeeper.view.PlayerInteractionState.class).isInitialized()
                        || stateManager.getState(SystemMessageState.class) == null
                        || !stateManager.getState(SystemMessageState.class).isInitialized()) return;
            } else {
                EndGameState end = stateManager.getState(EndGameState.class);
                if (end == null || !end.isInitialized()) return;
            }
            playerState.restoreScreenState(restored.screen());restorePhase = 2;return;
        }
        if (!playerState.isScreenReady()) return;
        // Bind every owner before starting server threads or admitting pending notifications/input.
        restored.model().restore(players, pendingPresentation, kwdFile);
        playerMapViewState.restoreFogState(restored.fog());
        playerMapViewState.restoreFlashes(restored.worldView().flash());
        playerModelViewState.restoreFlowers(restored.worldView().flowers());
        var active = restored.active();
        if (active != null) {
            playerState.getScreen().restoreHudState(active.hud());
            playerState.getScreen().restoreCreatureCards(active.cards());
            stateManager.getState(toniarts.openkeeper.game.console.ConsoleState.class).setEnabled(active.consoleEnabled());
            var interaction = stateManager.getState(toniarts.openkeeper.view.PlayerInteractionState.class);
            interaction.restoreSelection(active.interaction());interaction.restoreHandView(active.hand());
            stateManager.getState(SystemMessageState.class).restoreState(active.messages());
            stateManager.getState(PlayerCameraState.class).restoreState(active.camera());
        }
        SoundState sound = stateManager.getState(SoundState.class);
        sound.bindSession(this, kwdFile, playerId);sound.restoreState(restored.sound());
        playerState.restoreScreenState(restored.screen());
        playerMapViewState.resumeUpdates();playerModelViewState.setEnabled(true);
        readySent = true;
        synchronized (loadingObject) {gameStarted = true;loadingObject.notifyAll();}
        ((toniarts.openkeeper.game.state.session.LocalGameSession) gameClientService).setCommandAdmission(true);
        restoreInputPolicy();gameClientService.loadComplete();
    }

    @Override
    public void cleanup() {

        // Close network connections; detach signals session exit exactly once.
        ConnectionState cs = stateManager.getState(ConnectionState.class);
        if (cs != null) {
            cs.disconnect();
        }

        // Detach
        detach();

        super.cleanup();
    }

    @Override
    public boolean isPauseable() {
        return true;
    }

    public boolean isMultiplayer() {
        return multiplayer;
    }

    private IPlayerLoadingProgress createLoadingState(Main app) {

        // Create the appropriate loaging screen
        IPlayerLoadingProgress loader;
        if (isMultiplayer()) {
            loader = new MultiplayerLoadingState(app, "Multiplayer") {

                @Override
                public void onLoad() {
                    onGameLoad();
                }

                @Override
                public void onLoadComplete() {
                    onGameLoadComplete();
                }
            };
        } else {
            loader = new SingleBarLoadingState(app, "Singleplayer") {

                @Override
                public void onLoad() {
                    onGameLoad();
                }

                @Override
                public void onLoadComplete() {
                    onGameLoadComplete();
                }
            };
        }
        return loader;
    }

    private void onGameLoadComplete() {

        if (!closing && loadFailure == null && gameStarted) app.setViewProcessors();
    }

    private void onGameLoad() {
        synchronized (loadingObject) {
            while (!gameStarted && !closing && loadFailure == null) {
                try {loadingObject.wait();}
                catch (InterruptedException interrupted) {Thread.currentThread().interrupt();return;}
            }
        }
    }

    /**
     * Get the level raw data file
     *
     * @return the KWD
     */
    public IKwdFile getLevelData() {
        return kwdFile;
    }

    public Keeper getPlayer(short playerId) {
        return players.get(playerId);
    }

    public Keeper getPlayer() {
        return players.get(playerId);
    }

    public Collection<Keeper> getPlayers() {
        return players.values();
    }

    /**
     * Get level variable value
     *
     * @param variable the variable type
     * @return variable value
     */
    public float getLevelVariable(Variable.MiscVariable.MiscType variable) {
        // TODO: player is able to change these, so need a wrapper and store these to GameState
        return kwdFile.getVariables().get(variable).getValue();
    }

    /**
     * Gets a text parser that can fill up the parameters in translations
     *
     * @return a text parser
     */
    public TextParser getTextParser() {
        return textParser;
    }

    private final class GameSessionListenerImpl implements PresentationEvent.Target {

        @Override public void onLoadFailed(String message) {failLoading(new IllegalStateException(message));}

        @Override
        public void onGameDataLoaded(Collection<Keeper> players) {
            synchronized (loadingObject) {
                if (closing || receivedGameData) return;
                receivedGameData = true;
                List<Keeper> received = List.copyOf(players);
                dataLoader = new Thread(() -> {
                    try {
                        AssetUtils.prewarmAssets(kwdFile, app.getAssetManager(), app);
                        synchronized (loadingObject) {
                            if (!closing && loadFailure == null) preparedPlayers = received;
                        }
                    } catch (RuntimeException failure) {failLoading(failure);}
                }, "GameDataClientLoader");
                dataLoader.start();
            }
        }

        @Override
        public void onLoadComplete(short keeperId) {
            if (!closing && loadingState != null) loadingState.setProgress(1f, keeperId);
        }

        @Override
        public void onLoadStatusUpdate(float progress, short keeperId) {
            if (!closing && loadingState != null) loadingState.setProgress(progress, keeperId);
        }

        @Override
        public void onGameStarted() {
            if (restored != null) return;
            app.enqueue(() -> {
                if (closing || gameStarted || loadFailure != null || stateManager.getState(GameClientState.class) != GameClientState.this || playerState == null) return;
                SoundState sound = stateManager.getState(SoundState.class);
                sound.bindSession(GameClientState.this, kwdFile, playerId);
                sound.setEnabled(true);
                playerState.setEnabled(true);

                // Release the lock and enter to the game phase
                synchronized (loadingObject) {
                    gameStarted = true;
                    loadingObject.notifyAll();
                }
            });
        }

        @Override
        public void onTilesChange(List<Point> updatedTiles) {
            playerMapViewState.onTilesChange(updatedTiles);
        }

        @Override
        public void onTilesReveal(List<Point> points, boolean explore, short keeperId) {
            playerMapViewState.onTilesReveal(points, explore, keeperId);
        }

        @Override
        public void onFogOfWarDisabled(short keeperId) {
            playerMapViewState.onFogOfWarDisabled(keeperId);
        }

        @Override
        public void onFogOfWarReset(short keeperId) {
            playerMapViewState.onFogOfWarReset(keeperId);
        }

        @Override
        public void onGoldChange(short keeperId, long gold, int goldReserve) {
            Keeper keeper = getPlayer(keeperId);
            if (keeper == null) return;
            keeper.setGold(gold);keeper.setGoldReserve(goldReserve);
            if (playerState != null && playerState.getPlayerId() == keeperId) playerState.onGoldChange(keeperId, gold, goldReserve);
        }

        @Override
        public void onManaChange(short keeperId, int mana, int manaLoose, int manaGain) {
            Keeper keeper = getPlayer(keeperId);
            if (keeper == null) return;
            keeper.setMana(mana);keeper.setManaGain(manaGain);keeper.setManaLoose(manaLoose);
            if (playerState != null && playerState.getPlayerId() == keeperId) playerState.onManaChange(keeperId, mana, manaLoose, manaGain);
        }

        @Override
        public void onBuild(short keeperId, List<Point> tiles) {
            playerMapViewState.onBuild(keeperId, tiles);
        }

        @Override
        public void onSold(short keeperId, List<Point> tiles) {
            playerMapViewState.onSold(keeperId, tiles);
        }

        @Override
        public void onGamePaused() {
            if (activePresentation()) playerState.onPaused(true);
        }

        @Override
        public void onGameResumed() {
            if (activePresentation()) playerState.onPaused(false);
        }

        @Override
        public void onSetWidescreen(boolean enable) {
            if (activePresentation()) playerState.setWideScreen(enable);
        }

        @Override
        public void onGameEnded(boolean win, double elapsedSeconds) {
            if (playerState != null) playerState.endGame(win, elapsedSeconds);
        }

        @Override
        public void onPlaySpeech(int speechId, boolean showText, boolean introduction, int pathId) {
            SoundState sound = stateManager.getState(SoundState.class);
            if (!activePresentation() || sound == null) return;
            sound.attachLevelSpeech(speechId, new toniarts.openkeeper.game.sound.SoundSnapshot.StartAction(showText, introduction, pathId));
        }

        @Override
        public void onDoTransition(short pathId, Vector3f start) {
            PlayerCameraState camera = stateManager.getState(PlayerCameraState.class);
            if (!activePresentation() || camera == null) {
                // Terminal screens can remove the camera while the session continues.
                gameClientService.transitionEnd();return;
            }
            camera.doTransition(pathId, start, true);
        }

        @Override
        public void onFlashButton(TriggerAction.MakeType buttonType, short targetId, TriggerAction.ButtonType targetButtonType, boolean enabled, int time) {
            if (activePresentation()) playerState.flashButton(buttonType, targetId, targetButtonType, enabled, time);
        }

        @Override
        public void onRotateViewAroundPoint(Vector3f point, boolean relative, int angle, int time) {
            if (activePresentation()) playerState.rotateViewAroundPoint(point, relative, angle, time);
        }

        @Override
        public void onShowMessage(int textId) {
            if (activePresentation()) playerState.showMessage(textId);
        }

        @Override
        public void onShowObjective(int objectiveId) {
            if (playerState != null) playerState.showObjective(objectiveId);
        }

        @Override
        public void onZoomViewToPoint(Vector3f point) {
            if (activePresentation()) playerState.zoomToPoint(point);
        }

        @Override
        public void onTileFlash(List<Point> points, boolean enabled, short keeperId) {
            playerMapViewState.onTileFlash(points, enabled, keeperId);
        }

        @Override
        public void onZoomViewToEntity(EntityId entityId) {
            if (activePresentation()) playerState.zoomToEntity(entityId, true);
        }

        @Override
        public void onShowUnitFlower(EntityId entityId, int interval) {
            playerModelViewState.showUnitFlower(entityId, interval);
        }

        @Override
        public void onEntityAdded(short keeperId, ResearchableEntity researchableEntity) {
            setResearchableEntity(keeperId, researchableEntity, () -> {
                playerState.onEntityAdded(keeperId, researchableEntity);
                });
        }

        private void setResearchableEntity(short keeperId, ResearchableEntity researchableEntity, Runnable notifier) {
            Keeper keeper = getPlayer(keeperId);
            setResearchableEntity(researchableEntity, getResearchableEntitiesList(keeper, researchableEntity));

            if (notifier != null && playerState != null && playerState.getPlayerId() == keeperId) {
                notifier.run();
            }
        }

        @Override
        public void onEntityRemoved(short keeperId, ResearchableEntity researchableEntity) {
            setResearchableEntity(keeperId, researchableEntity, () -> {
                playerState.onEntityRemoved(keeperId, researchableEntity);
                });
        }

        @Override
        public void onResearchStatusChanged(short keeperId, ResearchableEntity researchableEntity) {
            setResearchableEntity(keeperId, researchableEntity, () -> {
                playerState.onResearchStatusChanged(keeperId, researchableEntity);
                });
        }

        private List<ResearchableEntity> getResearchableEntitiesList(Keeper keeper, ResearchableEntity researchableEntity) {
            List<ResearchableEntity> researchableEntities = null;
            switch (researchableEntity.getResearchableType()) {
                case SPELL: {
                    researchableEntities = keeper.getAvailableSpells();
                    break;
                }
                case DOOR: {
                    researchableEntities = keeper.getAvailableDoors();
                    break;
                }
                case ROOM: {
                    researchableEntities = keeper.getAvailableRooms();
                    break;
                }
                case TRAP: {
                    researchableEntities = keeper.getAvailableTraps();
                    break;
                }
            }

            return researchableEntities;
        }

        private <T extends ResearchableEntity> void setResearchableEntity(T researchableEntity, List<T> researchableEntities) {
            int index = Collections.binarySearch(researchableEntities, researchableEntity, (ResearchableEntity o1, ResearchableEntity o2) -> {
                return getResearchableEntityType(kwdFile, o1.getResearchableType(), o1.getId()).compareTo(getResearchableEntityType(kwdFile, o2.getResearchableType(), o2.getId()));
            });
            if (index < 0) {
                researchableEntities.add(~index, researchableEntity);
            } else if (index >= 0) {
                researchableEntities.set(index, researchableEntity);
            }
        }

        private Comparable getResearchableEntityType(IKwdFile kwdFile, ResearchableType researchableType, short typeId) {
            switch (researchableType) {
                case DOOR: {
                    return kwdFile.getDoorById(typeId);
                }
                case ROOM: {
                    return kwdFile.getRoomById(typeId);
                }
                case SPELL: {
                    return kwdFile.getKeeperSpellById(typeId);
                }
                case TRAP: {
                    return kwdFile.getTrapById(typeId);
                }
            }

            return null;
        }

        @Override
        public void onEnemySighted(EntityId entityId) {
            SystemMessageState messages = stateManager.getState(SystemMessageState.class);
            if (messages != null) messages.addMessage(SystemMessageState.MessageType.FIGHT, "Enemy creature sighted!");
        }

        @Override
        public void setPossession(EntityId target) {
            if (activePresentation()) playerState.setPossession(target);
            setPossessedCreature(target);
        }
    }

    public IMapInformation getMapClientService() {
        return mapInformation;
    }

    public IFogOfWarInformation getFogOfWarInformation() {
        return fogOfWarInformation;
    }

    /**
     * Marks (or unmarks) still-unexplored tiles as locally "looking tagged"
     * while the viewer drags a tag/dig selection over them - see
     * {@link IFogOfWarInformation#isPendingTagged(Point)}.
     */
    public void markPendingTaggedTiles(List<Point> points, boolean tagged) {
        if (playerMapViewState != null) {
            playerMapViewState.markPendingTaggedTiles(points, tagged);
        }
    }

    /**
     * Tells the local fog-of-war module which creature (if any) the viewer is
     * currently possessing, so it can bypass fog for the first-person view
     * and keep exploring the possessed creature's tile (§2/§6.9).
     *
     * @param entityId the possessed creature, or {@code null} if possession has ended
     */
    public void setPossessedCreature(EntityId entityId) {
        if (playerMapViewState != null) {
            playerMapViewState.setPossessedCreature(entityId);
        }
    }

    public GameSessionClientService getGameClientService() {
        return gameClientService;
    }

}
