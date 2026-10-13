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

    private AppStateManager stateManager;

    private final IKwdFile kwdFile;

    private final Map<Short, Keeper> players = new TreeMap<>();
    private final Object loadingObject = new Object();
    private volatile boolean gameStarted = false;

    private final Short playerId;
    private final boolean multiplayer;
    private IPlayerLoadingProgress loadingState;
    private final GameSessionClientService gameClientService;
    private final PresentationQueue pendingPresentation = new PresentationQueue();
    private final GameSessionListenerImpl presentationTarget = new GameSessionListenerImpl();
    private final QueuedGameSessionListener gameSessionListener = new QueuedGameSessionListener(pendingPresentation, presentationTarget);
    private Thread renderThread;
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
        if (!gameStarted || playerState == null || stateManager.getState(GameClientState.class) != this) return;
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

    private void requireReadyClient() {
        requireRenderThread();
        if (!isInitialized() || !gameStarted || stateManager.getState(GameClientState.class) != this)
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
    public void initialize(final AppStateManager stateManager, final Application app) {
        this.stateManager = stateManager;
        renderThread = Thread.currentThread();

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
        stateManager.detach(playerModelViewState);
        stateManager.detach(playerMapViewState);
        stateManager.detach(playerState);
    }

    /**
     * If you are getting rid of the game state, use this so that all the
     * related states are detached on the same render loop. Otherwise the app
     * might crash.
     */
    public void detach() {
        gameClientService.removeGameSessionListener(gameSessionListener);

        // If we are stuck loading
        synchronized (loadingObject) {
            loadingObject.notifyAll();
        }

        stateManager.detach(this);
        detachRelatedAppStates();

        SoundState sound = stateManager.getState(SoundState.class);
        if (sound != null && sound.isInitialized()) sound.unbindSession(this);
        pendingPresentation.close();
        playerState = null;
        playerMapViewState = null;
        playerModelViewState = null;
    }

    @Override
    public void cleanup() {

        // Signal our exit & close all the connections
        gameClientService.exitGame();
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

        // Set initialized
        GameClientState.this.initialized = true;

        // Set the processors
        GameClientState.this.app.setViewProcessors();
    }

    private void onGameLoad() {
        try {

            // The game is actually loaded elsewhere but for the visuals we need this
            if (!gameStarted) {
                synchronized (loadingObject) {
                    if (!gameStarted) {
                        loadingObject.wait();
                    }
                }
            }

        } catch (Exception e) {
            logger.log(Level.ERROR, "Failed to load the game!", e);
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

        private final Object mapDataLoadingObject = new Object();
        private volatile boolean mapDataLoaded = false;

        @Override
        public void onGameDataLoaded(Collection<Keeper> players) {

            // This might take awhile, don't block
            Thread loadingThread = new Thread(() -> {
                AssetUtils.prewarmAssets(kwdFile, app.getAssetManager(), app);
                for (Keeper keeper : players) {
                    keeper.setPlayer(kwdFile.getPlayer(keeper.getId()));
                    GameClientState.this.players.put(keeper.getId(), keeper);
                }

                // Create player state
                playerState = new PlayerState(playerId, kwdFile, gameClientService.getEntityData(), false, app, campaignLevel);

                playerMapViewState = new PlayerMapViewState(app, kwdFile, app.getAssetManager(), players, gameClientService.getEntityData(), playerId,
                        () -> {
                            synchronized (mapDataLoadingObject) {
                                mapDataLoaded = true;
                                mapDataLoadingObject.notifyAll();
                            }
                        }, gameSessionListener::onEnemySighted) {

                    private float lastProgress = 0;

                    @Override
                    protected void updateProgress(float progress) {

                        // Update ourselves
                        onLoadStatusUpdate(progress, playerId);

                        if (progress - lastProgress >= 0.01f) {
                            gameClientService.loadStatus(progress);
                            lastProgress = progress;
                        }
                    }
                };
                mapInformation = playerMapViewState.getMapInformation();
                fogOfWarInformation = playerMapViewState.getFogOfWarInformation();
                textParser = new TextParserService(mapInformation, playerMapViewState.getRoomsInformation());
                playerModelViewState = new PlayerEntityViewState(kwdFile, app.getAssetManager(), gameClientService.getEntityData(), playerId, textParser, app.getRootNode(), mapInformation.getMapData(), fogOfWarInformation);
                playerMapViewState.addFogOfWarTilesDirtyListener(playerModelViewState::onTilesDirty);

                // Attach the states
                stateManager.attach(playerState);
                stateManager.attach(playerMapViewState);
                stateManager.attach(playerModelViewState);

                // Wait until loaded
                if (!mapDataLoaded) {
                    synchronized (mapDataLoadingObject) {
                        if (!mapDataLoaded) {
                            try {
                                mapDataLoadingObject.wait();
                            } catch (InterruptedException ex) {
                                System.getLogger(GameClientState.class.getName()).log(Level.ERROR, "Map data loading interrupted!", ex);
                            }
                        }
                    }
                }

                // Loaded up, send our ready signal on the next frame to ensure all the states are attached
                app.enqueue(() -> {

                    // Prewarm the whole scene
                    app.getRenderManager().preloadScene(app.getRootNode());

                    // Signal our readiness
                    gameClientService.loadComplete();
                });

            }, "GameDataClientLoader");
            loadingThread.start();
        }

        @Override
        public void onLoadComplete(short keeperId) {
            loadingState.setProgress(1f, keeperId);
        }

        @Override
        public void onLoadStatusUpdate(float progress, short keeperId) {
            loadingState.setProgress(progress, keeperId);
        }

        @Override
        public void onGameStarted() {

            // Release loading state from memory
            loadingState = null;

            app.enqueue(() -> {
                if (stateManager.getState(GameClientState.class) != GameClientState.this || playerState == null) return;
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
