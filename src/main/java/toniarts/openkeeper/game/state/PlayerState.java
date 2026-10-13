/*
 * Copyright (C) 2014-2015 OpenKeeper
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

import toniarts.openkeeper.game.logic.PaydaySystem;

import com.jme3.app.Application;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.asset.AssetManager;
import com.jme3.math.Vector3f;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import de.lessvoid.nifty.Nifty;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.component.Position;
import toniarts.openkeeper.game.console.ConsoleState;
import toniarts.openkeeper.game.controller.player.*;
import toniarts.openkeeper.game.data.GameResult;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.data.ResearchableEntity;
import toniarts.openkeeper.game.listener.PlayerListener;
import toniarts.openkeeper.tools.convert.map.*;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.view.*;
import toniarts.openkeeper.view.PlayerInteractionState.InteractionState;
import toniarts.openkeeper.view.control.EntityViewControl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import toniarts.openkeeper.game.data.CampaignLevel;
import toniarts.openkeeper.game.data.Settings;
import toniarts.openkeeper.game.state.session.PlayerProgressState;
import toniarts.openkeeper.game.state.session.PlayerScreenState;
import java.util.UUID;

/**
 * The player state! GUI, camera, etc. Player interactions
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class PlayerState extends AbstractAppState implements PlayerListener {

    protected final Main app;

    protected AssetManager assetManager;
    protected AppStateManager stateManager;

    private final short playerId;
    private final IKwdFile kwdFile;
    private final EntityData entityData;
    @Nullable
    private final CampaignLevel campaignLevel;
    private GameResult gameResult;
    private final UUID attemptId;
    private Thread renderThread;

    private boolean paused = false;

    private final List<AbstractPauseAwareState> appStates = new ArrayList<>();
    protected PlayerInteractionState interactionState;
    private PossessionInteractionState possessionState;
    protected PlayerCameraState cameraState;
    private PossessionCameraState possessionCameraState;

    private boolean transitionEnd = true;
    private PlayerScreenController screen;

    public PlayerState(int playerId, IKwdFile kwdFile, EntityData entityData, boolean enabled, Main app, @Nullable CampaignLevel campaignLevel) {
        this(playerId, kwdFile, entityData, enabled, app, campaignLevel,
                PlayerProgressState.fresh((short) playerId, campaignLevel));
    }

    /** Stage progress before constructing views; the session assembler restores screen/input owners separately. */
    public PlayerState(int playerId, IKwdFile kwdFile, EntityData entityData, boolean enabled, Main app,
            @Nullable CampaignLevel campaignLevel, PlayerProgressState progress) {
        if (playerId < 0 || playerId > 255) throw new IllegalArgumentException("Invalid local player");
        progress.validateBinding((short) playerId, campaignLevel);
        this.playerId = (short) playerId;
        this.attemptId = progress.attemptId();
        this.gameResult = progress.result() == null ? null : GameResult.restoreState(progress.result());
        this.kwdFile = kwdFile;
        this.entityData = entityData;
        this.app = app;
        this.campaignLevel = campaignLevel;

        screen = new PlayerScreenController(this, app.getNifty());
        app.getNifty().registerScreenController(screen);
        app.getNifty().addXml(new ByteArrayInputStream(app.getGameUiXml()));

        super.setEnabled(enabled);
    }

    @Override
    public void initialize(final AppStateManager stateManager, final Application app) {
        super.initialize(stateManager, app);

        renderThread = Thread.currentThread();
        assetManager = this.app.getAssetManager();
        this.stateManager = stateManager;
    }

    /** Capture only observes local result delivery; it neither reads server results nor writes the profile. */
    public PlayerProgressState captureProgressState() {
        requireActiveOwner();
        return new PlayerProgressState(playerId, attemptId, PlayerProgressState.Campaign.of(campaignLevel),
                gameResult == null ? null : gameResult.captureState());
    }

    private void requireActiveOwner() {
        if (!isInitialized() || Thread.currentThread() != renderThread
                || stateManager.getState(PlayerState.class) != this)
            throw new IllegalStateException("Player state belongs to the active render-thread owner");
    }

    public boolean hasGameResult() { return gameResult != null; }

    public PlayerScreenState captureScreenState() {
        requireActiveOwner();
        EndGameState end = stateManager.getState(EndGameState.class);
        boolean awaiting = end != null && end.isAwaitingInputFor(this);
        if (!isEnabled() && !awaiting || isEnabled() && end != null)
            throw new IllegalStateException("Player screen lifecycle is still changing");
        return new PlayerScreenState(playerId, isEnabled(), paused, transitionEnd,
                screen.getRequestedRoute(), screen.getCinematicText(), awaiting);
    }

    /**
     * Restore into an already prepared matching lifecycle. Other owners retain their own enabled
     * flags; this does not pause/resume the server, rebuild controls or replay result delivery.
     * Wait for isScreenReady() before admitting input to a replaced session.
     */
    public void restoreScreenState(PlayerScreenState saved) {
        requireActiveOwner();
        if (saved.playerId() != playerId || saved.playerEnabled() != isEnabled())
            throw new IllegalArgumentException("Player screen requires a matching prepared owner");
        EndGameState end = stateManager.getState(EndGameState.class);
        if (saved.awaitingEndInput() ? !hasGameResult() || end == null || !end.isAwaitingInputFor(this) : end != null)
            throw new IllegalArgumentException("Player result screen is not prepared");
        screen.validateScreenRestore(saved);
        paused = saved.paused();transitionEnd = saved.transitionEnd();
        screen.restoreScreenRouting(saved);
        if (saved.awaitingEndInput()) end.restoreAwaitingInput();
    }

    public boolean isScreenReady() { requireActiveOwner();return screen.isRouteReady(); }

    void updateScreenRouting() { if (screen != null) screen.updateScreenRouting(); }

    @Override
    public void cleanup() {

        // Detach states
        for (AbstractAppState state : appStates) {
            stateManager.detach(state);
        }
        appStates.clear();
        interactionState = null;
        possessionState = null;
        cameraState = null;
        possessionCameraState = null;


        // Disassemble Nifty
        screen.cleanup();
        Nifty nifty = app.getNifty();
        nifty.unregisterScreenController(screen);
        for (String screenId : nifty.getAllScreensName()) {
            if (screen.equals(nifty.getScreen(screenId).getScreenController())) {
                nifty.removeScreen(screenId);
            }
        }
        screen = null;

        super.cleanup();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);

        if (!isInitialized()) {
            return;
        }

        if (enabled) {

            // Get the game state
            final GameClientState gameState = stateManager.getState(GameClientState.class);
            screen.setState(this);
            screen.initHud(gameState.getLevelData().getGameLevel(), entityData);

            // Cursor
            app.getInputManager().setCursorVisible(true);

            // Set the pause state
            paused = false;
            screen.setPause(paused);

            appStates.add(new ConsoleState());

            // Create app states
            Player player = gameState.getLevelData().getPlayer(playerId);

            possessionCameraState = new PossessionCameraState(false);
            possessionState = new PossessionInteractionState(false) {
                @Override
                protected void onExit() {
                    // Enable states
                    for (AbstractAppState state : appStates) {
                        if (state instanceof PossessionInteractionState
                                || state instanceof PossessionCameraState) {
                            continue;
                        }
                        state.setEnabled(true);
                    }

                    gameState.setPossessedCreature(null);

                    screen.goToScreen(PlayerScreenController.SCREEN_HUD_ID);
                }

                @Override
                protected void onActionChange(PossessionInteractionState.Action action) {
                    PlayerState.this.screen.updatePossessionSelectedItem(action);
                }
            };
            appStates.add(possessionCameraState);
            appStates.add(possessionState);

            cameraState = new PlayerCameraState(player);
            interactionState = new PlayerInteractionState(player, kwdFile, entityData, gameState.getMapClientService(), gameState.getTextParser()) {
                @Override
                protected void onInteractionStateChange(InteractionState interactionState) {
                    PlayerState.this.screen.updateSelectedItem(interactionState);
                }

                @Override
                protected void onPossession(EntityId entityId) {
                    // Disable states
                    for (AbstractAppState state : appStates) {
                        if (state instanceof PossessionInteractionState
                                || state instanceof PossessionCameraState) {
                            continue;
                        }
                        state.setEnabled(false);
                    }
                    // Enable state
                    possessionState.setTarget(entityId);
                    possessionState.setEnabled(true);

                    gameState.setPossessedCreature(entityId);

                    screen.goToScreen(PlayerScreenController.SCREEN_POSSESSION_ID);
                }
            };
            appStates.add(cameraState);
            appStates.add(interactionState);
            // FIXME add to appStates or directly to stateManager
            appStates.add(new SystemMessageState(screen.getMessages()));
            // Load the state
            for (AbstractAppState state : appStates) {
                stateManager.attach(state);
            }
        } else {

            // Detach states
            for (AbstractAppState state : appStates) {
                stateManager.detach(state);
            }

            appStates.clear();
            screen.cleanup();
            screen.goToScreen(PlayerScreenController.SCREEN_EMPTY_ID);
        }
    }

    @Override
    public void update(float tpf) {
        screen.update(tpf);
    }

    public IKwdFile getKwdFile() {
        return kwdFile;
    }

    public EntityData getEntityData() {
        return entityData;
    }

    public PlayerScreenController getScreen() {
        return screen;
    }

    /**
     * Get this player
     *
     * @return this player
     */
    public Keeper getPlayer() {
        GameClientState gs = stateManager.getState(GameClientState.class);
        if (gs != null) {
            return gs.getPlayer(playerId);
        }
        return null;
    }

    public PlayerStatsControl getStatsControl() {
        Keeper keeper = getPlayer();
//        if (keeper != null) {
//            return keeper.getStatsControl();
//        }
        return null;
    }

    public PlayerGoldControl getGoldControl() {
        Keeper keeper = getPlayer();
//        if (keeper != null) {
//            return keeper.getGoldControl();
//        }
        return null;
    }

    public PlayerCreatureControl getCreatureControl() {
        Keeper keeper = getPlayer();
//        if (keeper != null) {
//            return keeper.getCreatureControl();
//        }
        return null;
    }

    public PlayerRoomControl getRoomControl() {
        Keeper keeper = getPlayer();
//        if (keeper != null) {
//            return keeper.getRoomControl();
//        }
        return null;
    }

    public PlayerSpellControl getSpellControl() {
        Keeper keeper = getPlayer();
//        if (keeper != null) {
//            return keeper.getSpellControl();
//        }
        return null;
    }

    public void setTransitionEnd(boolean value) {
        transitionEnd = value;
    }

    public boolean isTransitionEnd() {
        return transitionEnd;
    }

    public void setWideScreen(boolean enable) {
        if (interactionState != null && interactionState.isInitialized()) {
            interactionState.setEnabled(!enable);
        }

        if (enable) {
            screen.goToScreen(PlayerScreenController.SCREEN_CINEMATIC_ID);
        } else {
            screen.goToScreen(PlayerScreenController.SCREEN_HUD_ID);
        }
    }

    public void setText(int textId, boolean introduction, int pathId) {
        screen.setCinematicText(textId, introduction, pathId);
    }

    public void setGeneralText(int textId) {
        screen.setCinematicText(textId);
    }

    public void flashButton(TriggerAction.MakeType buttonType, short targetId, TriggerAction.ButtonType targetButtonType, boolean enabled, int time) {
        // TODO make flash button
    }

    protected List<ResearchableEntity> getAvailableRoomsToBuild() {

        // TODO: cache, or something, maybe add the listeners here
        Keeper keeper = getPlayer();
        return keeper.getAvailableRooms();
    }

    protected List<ResearchableEntity> getAvailableKeeperSpells() {

        // TODO: cache, or something, maybe add the listeners here
        Keeper keeper = getPlayer();
        return keeper.getAvailableSpells();
    }

    protected List<ResearchableEntity> getAvailableDoors() {
        // TODO: cache, or something, maybe add the listeners here
        Keeper keeper = getPlayer();
        return keeper.getAvailableDoors();
    }

    protected List<ResearchableEntity> getAvailableTraps() {
        // TODO: cache, or something, maybe add the listeners here
        Keeper keeper = getPlayer();
        return keeper.getAvailableTraps();
    }

    public void setPaused(boolean paused) {

        // Pause / unpause
        // TODO: We should give the client to these states to self register to the client service listener...
        // But PlayerState is persistent.. I don't want to have the reference here
        if (paused) {
            stateManager.getState(GameClientState.class).getGameClientService().pauseGame();
        } else {
            stateManager.getState(GameClientState.class).getGameClientService().resumeGame();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public void onPaused(boolean paused) {

        // Pause state
        this.paused = paused;

        // Delegate to the screen
        screen.onPaused(paused);

        for (AbstractPauseAwareState state : appStates) {
            if (state.isPauseable()) {
                state.setEnabled(!paused);
            }
        }
    }

    public void quitToMainMenu() {

        // Detach game and enable main menu
        stateManager.getState(GameClientState.class).detach();
        stateManager.detach(this);
        stateManager.getState(MainMenuState.class).setEnabled(true);
    }

    public void quitToDebriefing() {
        if (gameResult == null) { quitToMainMenu(); return; }
        stateManager.getState(GameClientState.class).detach();
        stateManager.detach(this);
        stateManager.getState(MainMenuState.class).doDebriefing(gameResult, kwdFile, campaignLevel);
    }

    public void quitToOS() {
        app.stop();
    }

    public void zoomToDungeon() {
        Point heart = stateManager.getState(GameClientState.class).getPlayer(playerId).getDungeonHeartLocation();
        if (heart != null) {
            cameraState.setCameraLookAt(heart);
        }
    }

    /**
     * Zoom to entity
     *
     * @param entityId the entity to zoom to
     * @param animate whether to animate the transition
     */
    public void zoomToEntity(EntityId entityId, boolean animate) {
        Position position = entityData.getComponent(entityId, Position.class);
        if (position != null) {
            zoomToPosition(position.position, animate);
        }
    }

    /**
     * Zoom to position
     *
     * @param position the position to zoom to
     * @param animate whether to animate the transition
     */
    public void zoomToPosition(Vector3f position, boolean animate) {
        if (animate) {
            cameraState.zoomToPoint(position);
        } else {
            cameraState.setCameraLookAt(position);
        }
    }

    /**
     * Pick up an entity
     *
     * @param entityId entity
     */
    public void pickUpEntity(EntityId entityId) {

        // TODO: We should really have some sort of static etc. service that we can just ask these for entityId, not to pass a view controller
        // They can be used with convenience methods from the view controller yes, but so that all will have access, shared with game logic, so the rules are the same (canPickup etc.)
        interactionState.pickupObject(new EntityViewControl(entityId, entityData, app, null, assetManager, null) {
            @Override
            protected ArtResource getAnimationData(Object state) {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }
        });
    }

    public short getPlayerId() {
        return playerId;
    }

    protected Creature getPossessionCreature() {
        return null/*possessionState.getTarget().getCreature()*/;
    }

    protected InteractionState getInteractionState() {
        return interactionState.getInteractionState();
    }

    public void rotateViewAroundPoint(Vector3f point, boolean relative, int angle, int time) {
        cameraState.rotateAroundPoint(point, relative, angle, time);
    }

    public void showMessage(int textId) {
        stateManager.getState(SystemMessageState.class).addMessage(SystemMessageState.MessageType.INFO, String.format("${level.%d}", textId));
    }

    public void showObjective(int objectiveId) {
        // GameClientState dispatches this on the render thread.
        if (screen != null) screen.showObjective(objectiveId);
    }

    public void zoomToPoint(Vector3f point) {
        cameraState.zoomToPoint(point);
    }

    protected void grabGold(int amount) {
        if (!interactionState.isKeeperHandFull()) {
            stateManager.getState(GameClientState.class).getGameClientService().getGold(amount);
        }
    }

    /**
     * End the game for the player
     *
     * @param win did we win or not
     */
    protected void endGame(boolean win, double elapsedSeconds) {
        if (gameResult != null) return;
        gameResult = GameResult.completed(win, elapsedSeconds);

        // The local profile owns campaign progress; server and remote opponents do not.
        if (campaignLevel != null) {
            Settings settings = Main.getUserSettings();
            settings.recordCampaignResult(campaignLevel, attemptId, win);
            try {
                settings.save();
            } catch (IOException ex) {
                java.lang.System.getLogger(PlayerState.class.getName()).log(java.lang.System.Logger.Level.ERROR, "Failed to save level progress!", ex);
            }
        }

        // Disable us to get rid of all interaction
        setEnabled(false);
        stateManager.attach(new EndGameState(this));
    }

    String getTooltipText(String text) {
        int dungeonHealth = 0;
        long paydayCost = PaydaySystem.estimateNextPayroll(entityData, playerId);

        return text.replaceAll("%19%", String.valueOf(dungeonHealth))
                .replaceAll("%20", String.valueOf(paydayCost));
    }

    @Override
    public void onGoldChange(short keeperId, long gold, int goldReserve) {
        screen.setGold(gold);
    }

    @Override
    public void onManaChange(short keeperId, int mana, int manaLoose, int manaGain) {
        screen.setMana(mana, manaLoose, manaGain);
    }

    @Override
    public void onBuild(short keeperId, List<Point> tiles) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void onSold(short keeperId, List<Point> tiles) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void onEntityAdded(short keeperId, ResearchableEntity researchableEntity) {
        populateTab(researchableEntity);
    }

    private void populateTab(ResearchableEntity researchableEntity) {
        switch (researchableEntity.getResearchableType()) {
            case SPELL: {
                screen.populateSpellTab();
                break;
            }
            case TRAP:
            case DOOR: {
                screen.populateManufactureTab();
                break;
            }
            case ROOM: {
                screen.populateRoomTab();
                break;
            }
        }
    }

    @Override
    public void onEntityRemoved(short keeperId, ResearchableEntity researchableEntity) {
        populateTab(researchableEntity);
    }

    @Override
    public void onResearchStatusChanged(short keeperId, ResearchableEntity researchableEntity) {
        screen.updateEntityResearch(researchableEntity);
    }

    void setPossession(EntityId target) {

    }

}
