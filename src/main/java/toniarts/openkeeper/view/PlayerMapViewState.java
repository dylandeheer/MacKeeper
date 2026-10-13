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
package toniarts.openkeeper.view;

import com.jme3.app.Application;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.asset.AssetManager;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.listener.MapListener;
import toniarts.openkeeper.game.listener.PlayerActionListener;
import toniarts.openkeeper.game.map.IMapInformation;
import toniarts.openkeeper.game.map.IRoomsInformation;
import toniarts.openkeeper.game.map.MapInformation;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.tools.modelviewer.Debug;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.view.effect.EffectManagerState;
import toniarts.openkeeper.view.fogofwar.FogOfWarController;
import toniarts.openkeeper.view.fogofwar.IFogOfWarInformation;
import toniarts.openkeeper.view.map.FlashTileViewState;
import toniarts.openkeeper.view.map.MapRoomContainer;
import toniarts.openkeeper.view.map.MapTileContainer;
import toniarts.openkeeper.view.map.MapViewController;

import java.lang.System.Logger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Handles the handling of game world for a player, visually
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public abstract class PlayerMapViewState extends AbstractAppState implements MapListener, PlayerActionListener {
    
    private static final Logger logger = System.getLogger(PlayerMapViewState.class.getName());

    private Thread renderThread;
    private boolean mapAttached;
    private volatile boolean closing;
    private boolean disposed;
    private boolean updatesSuspended;
    private Thread mapLoaderThread;
    private Main app;
    private AppStateManager stateManager;
    private final IMapInformation mapInformation;
    private final MapViewController mapLoader;
    private final IKwdFile kwdFile;
    private final AssetManager assetManager;
    private final MapTileContainer mapTileContainer;
    private Node worldNode;
    private final EffectManagerState effectManager;
    private final FlashTileViewState flashTileControl;
    private final MapRoomContainer mapRoomContainer;
    private final FogOfWarController fogOfWarController;

    // Creature vision (and the initial fog seeding) can produce tile updates
    // before MapViewController.load() has built its scene graph - queue those
    // and flush them once the map actually exists, instead of crashing
    private volatile boolean mapLoaded = false;
    private final ConcurrentLinkedQueue<Point> pendingTileUpdates = new ConcurrentLinkedQueue<>();

    protected PlayerMapViewState(Main app, final IKwdFile kwdFile, final AssetManager assetManager, Collection<Keeper> players, EntityData entityData, short playerId, ILoadCompleteNotifier loadCompleteNotifier) {
        this(app, kwdFile, assetManager, players, entityData, playerId, loadCompleteNotifier, (entityId) -> {
        });
    }

    protected PlayerMapViewState(Main app, final IKwdFile kwdFile, final AssetManager assetManager, Collection<Keeper> players, EntityData entityData, short playerId, ILoadCompleteNotifier loadCompleteNotifier, Consumer<EntityId> enemySightedNotifier) {
        this(app, kwdFile, assetManager, players, entityData, playerId, loadCompleteNotifier, enemySightedNotifier,
                failure -> logger.log(System.Logger.Level.ERROR, "Failed to load map presentation", failure));
    }

    protected PlayerMapViewState(Main app, final IKwdFile kwdFile, final AssetManager assetManager, Collection<Keeper> players,
            EntityData entityData, short playerId, ILoadCompleteNotifier loadCompleteNotifier,
            Consumer<EntityId> enemySightedNotifier, Consumer<Throwable> loadFailureNotifier) {
        this(app, kwdFile, assetManager, players, entityData, playerId, loadCompleteNotifier, enemySightedNotifier, loadFailureNotifier, null);
    }

    protected PlayerMapViewState(Main app, final IKwdFile kwdFile, final AssetManager assetManager, Collection<Keeper> players,
            EntityData entityData, short playerId, ILoadCompleteNotifier loadCompleteNotifier, Consumer<EntityId> enemySightedNotifier,
            Consumer<Throwable> loadFailureNotifier, toniarts.openkeeper.view.fogofwar.FogSnapshot initialFog) {
        updatesSuspended = initialFog != null;
        this.app = app;
        this.kwdFile = kwdFile;
        this.assetManager = assetManager;

        // World node
        worldNode = new Node("World");
        if (Main.isDebug()) {
            Debug.showNodeAxes(assetManager, worldNode, 10);
        }

        // Load and update rooms
        mapRoomContainer = new MapRoomContainer(entityData, kwdFile, this::updateTiles);

        // Make sure we load the whole map before we continue
        mapTileContainer = new MapTileContainer(entityData, kwdFile, this::updateTiles) {

            @Override
            protected void onLoadComplete() {

                // Fog must be seeded before the initial geometry is built, since
                // the renderer consults it while assembling the map
                if (initialFog == null) fogOfWarController.seedLevelStart();
                else fogOfWarController.restoreState(initialFog);

                // Don't block the caller, might be called from the render thread...
                mapLoaderThread = new Thread(() -> {
                    try {
                    Spatial map = mapLoader.load(assetManager, kwdFile);
                    if (closing) return;
                    mapLoaded = true;

                    // Apply any tile updates (fog reveals from the seeding above,
                    // or from creatures already ticking) that arrived too early to
                    // be applied directly, while the map wasn't attached yet
                    if (!pendingTileUpdates.isEmpty()) {
                        List<Point> pending = new ArrayList<>(pendingTileUpdates.size());
                        Point p;
                        while ((p = pendingTileUpdates.poll()) != null) {
                            pending.add(p);
                        }
                        mapLoader.updateTiles(pending.toArray(new Point[0]));
                    }

                    app.enqueue(() -> {
                        if (closing) return;
                        worldNode.attachChild(map);
                        mapAttached = true;

                        loadCompleteNotifier.onLoadComplete();
                    });
                    } catch (RuntimeException failure) {
                        if (!closing) loadFailureNotifier.accept(failure);
                    }
                }, "GameClientMapLoader");
                mapLoaderThread.start();
            }

        };

        mapInformation = new MapInformation<>(mapTileContainer, kwdFile, players, mapRoomContainer::getRoomInformation);

        fogOfWarController = new FogOfWarController(entityData, kwdFile, mapTileContainer, playerId,
                this::updateTiles, enemySightedNotifier);

        // Effect manager
        effectManager = new EffectManagerState(kwdFile, assetManager);

        // Create the actual map
        mapLoader = new MapViewController(assetManager, kwdFile, mapInformation, fogOfWarController, playerId) {

            @Override
            protected void updateProgress(float progress) {
                PlayerMapViewState.this.updateProgress(progress);
            }

        };

        flashTileControl = new FlashTileViewState(mapLoader);

        // Start collecting the map entities
        mapRoomContainer.start();
        fogOfWarController.start();
        mapTileContainer.start();
    }

    @Override
    public void initialize(final AppStateManager stateManager, final Application app) {
        super.initialize(stateManager, app);
        this.app = (Main) app;
        this.stateManager = stateManager;
        renderThread = Thread.currentThread();

        // Effects
        this.stateManager.attach(effectManager);

        // Tile flash state
        this.stateManager.attach(flashTileControl);

        // Attach the world
        this.app.getRootNode().attachChild(worldNode);
    }

    @Override
    public void cleanup() {
        if (disposed) return;
        cancelLoading();
        // This worker only constructs CPU scene data; it never waits for render-thread work.
        // Finish its reads before releasing the map/fog queries it depends on.
        boolean interrupted = false;
        while (mapLoaderThread != null && mapLoaderThread.isAlive()) {
            try {mapLoaderThread.join();}
            catch (InterruptedException failure) {interrupted = true;}
        }
        if (interrupted) Thread.currentThread().interrupt();
        disposed = true;

        // Detach our map
        if (worldNode != null) {
            app.getRootNode().detachChild(worldNode);
            worldNode = null;
        }

        // Tile flash state
        if (stateManager != null) stateManager.detach(flashTileControl);

        // Effects
        if (stateManager != null) stateManager.detach(effectManager);

        // The actual map data
        mapRoomContainer.stop();
        mapTileContainer.stop();
        fogOfWarController.stop();

        super.cleanup();
    }

    /** May be called before initialization; late worker completion must not attach its scene. */
    public void cancelLoading() {
        closing = true;
        if (mapLoaderThread != null) mapLoaderThread.interrupt();
    }

    /** Release initial restored observations only after the complete client is bound. */
    public void resumeUpdates() {updatesSuspended = false;}

    @Override
    public void update(float tpf) {
        if (closing || updatesSuspended) return;
        // Always process rooms before the map tiles
        mapRoomContainer.update();
        mapTileContainer.update();
        fogOfWarController.update(tpf);
    }

    public AssetManager getAssetManager() {
        return assetManager;
    }

    /**
     * If you want to monitor the map loading progress, use this method
     *
     * @param progress current progress from 0.0 to 1.0
     */
    protected abstract void updateProgress(final float progress);

    @Override
    public void onTilesChange(List<Point> updatedTiles) {
        fogOfWarController.onTileOwnerChanged(updatedTiles);
    }

    @Override
    public void onBuild(short keeperId, List<Point> tiles) {
        fogOfWarController.onRoomBuilt(keeperId, tiles);
    }

    @Override
    public void onSold(short keeperId, List<Point> tiles) {

        // Not wired: by the time this fires the terrain has typically already
        // reverted to non-room, so the room's TileConstruction (needed to decide
        // whether a whole-room unexplore applies, §6.1) can no longer be resolved here
    }

    @Override
    public void onTileFlash(List<Point> points, boolean enabled, short keeperId) {
        flashTileControl.attach(points, enabled);
    }

    @Override
    public void onFogOfWarDisabled(short keeperId) {
        fogOfWarController.disableFogOfWar();
    }

    @Override
    public void onFogOfWarReset(short keeperId) {
        fogOfWarController.resetToLevelStart();
    }

    @Override
    public void onTilesReveal(List<Point> points, boolean explore, short keeperId) {
        fogOfWarController.revealActionPointTiles(points, explore);
    }

    public IMapInformation getMapInformation() {
        return mapInformation;
    }

    public toniarts.openkeeper.view.WorldViewState.Flash captureFlashes() {
        requireFogOwner();return flashTileControl.captureState();
    }

    public void restoreFlashes(toniarts.openkeeper.view.WorldViewState.Flash saved) {
        requireFogOwner();
        for (Point point : saved.points()) {
            if (point.x < 0 || point.y < 0 || point.x >= kwdFile.getMap().getWidth() || point.y >= kwdFile.getMap().getHeight())
                throw new IllegalArgumentException("Flashing tile outside map");
        }
        flashTileControl.restoreState(saved);
    }

    public toniarts.openkeeper.view.fogofwar.FogSnapshot captureFogState() {
        requireFogOwner();return fogOfWarController.captureState();
    }

    public void restoreFogState(toniarts.openkeeper.view.fogofwar.FogSnapshot saved) {
        requireFogOwner();fogOfWarController.restoreState(saved);
        fogOfWarController.refreshPresentation();
    }

    private void requireFogOwner() {
        if (closing || !isInitialized() || !mapAttached || renderThread != Thread.currentThread()
                || stateManager.getState(PlayerMapViewState.class) != this)
            throw new IllegalStateException("Fog restore requires the loaded render-thread map owner");
    }

    public IFogOfWarInformation getFogOfWarInformation() {
        return fogOfWarController;
    }

    public void markPendingTaggedTiles(List<Point> points, boolean tagged) {
        fogOfWarController.markPendingTagged(points, tagged);
    }

    public void setPossessedCreature(EntityId entityId) {
        fogOfWarController.setPossessedCreature(entityId);
    }

    public void addFogOfWarTilesDirtyListener(Consumer<Point[]> listener) {
        fogOfWarController.addTilesDirtyListener(listener);
    }

    private void updateTiles(Point[] points) {
        if (!mapLoaded) {
            pendingTileUpdates.addAll(Arrays.asList(points));
            return;
        }
        mapLoader.updateTiles(points);
    }

    public IRoomsInformation getRoomsInformation() {
        return mapRoomContainer;
    }

    public interface ILoadCompleteNotifier {

        void onLoadComplete();

    }

}
