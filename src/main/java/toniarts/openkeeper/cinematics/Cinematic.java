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
package toniarts.openkeeper.cinematics;

import com.jme3.app.Application;
import com.jme3.app.state.AppStateManager;
import com.jme3.asset.AssetManager;
import com.jme3.audio.Listener;
import com.jme3.cinematic.PlayState;
import com.jme3.cinematic.events.CinematicEvent;
import com.jme3.cinematic.events.CinematicEventListener;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.utils.AssetUtils;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.utils.WorldUtils;

/**
 * Our wrapper on JME cinematic class, produces ready cinematics from camera
 * sweep files.<br>
 * This extends the JME's own Cinematic, so adding effects etc. is as easy as
 * possible.
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class Cinematic extends com.jme3.cinematic.Cinematic {

    private final CameraSweepData cameraSweepData;
    private final Camera cam;
    private final Vector3f start;
    private final Listener audioListener;
    private final Vector3f sampledPosition = new Vector3f();
    private final Quaternion sampledRotation = new Quaternion();
    private final Quaternion nextRotation = new Quaternion();
    private boolean cancelled;
    private boolean completed;

    /**
     * Creates a new cinematic ready for consumption
     *
     * @param assetManager asset manager instance
     * @param cam the camera to use
     * @param audioListener the audio listener position
     * @param start starting map coordinates, zero based
     * @param cameraSweepFile the camera sweep file name that is the basis for
     * this animation (without the extension)
     * @param scene scene node to attach to
     * @param stateManager the state manager
     */
    public Cinematic(final AssetManager assetManager, Camera cam, Listener audioListener,
            Point start, String cameraSweepFile, Node scene, AppStateManager stateManager) {

        this(assetManager, cam, audioListener, WorldUtils.pointToVector3f(start), cameraSweepFile, scene, stateManager);
    }

    public Cinematic(final Main app, String cameraSweepFile, final Vector3f start) {

        this(app.getAssetManager(), app.getCamera(), app.getListener(),
                start.add(0, WorldUtils.FLOOR_HEIGHT, 0),
                cameraSweepFile, app.getRootNode(), app.getStateManager());
    }

    /**
     * Creates a new cinematic ready for consumption
     *
     * @param assetManager asset manager instance
     * @param cam the camera to use
     * @param audioListener the audio listener position
     * @param start starting location, zero based
     * @param cameraSweepFile the camera sweep file name that is the basis for
     * this animation (without the extension)
     * @param scene scene node to attach to
     * @param stateManager the state manager
     */
    public Cinematic(final AssetManager assetManager, final Camera cam, Listener audioListener,
            final Vector3f start, String cameraSweepFile, Node scene, final AppStateManager stateManager) {

        super(scene);

        this.audioListener = audioListener;
        this.start = start.clone();
        this.cam = cam;

        // Load up the camera sweep file
        cameraSweepData = AssetUtils.loadCameraSweep(assetManager, cameraSweepFile);
        if (cameraSweepData.getEntries().isEmpty()) {
            throw new IllegalArgumentException("Camera sweep has no samples: " + cameraSweepFile);
        }
        setInitialDuration(cameraSweepData.getEntries().size() / getFramesPerSecond());

        // Add the listener, it is critical that this listener is executed before any custom ones
        addListener(new CinematicEventListener() {
            @Override
            public void onPlay(CinematicEvent cinematic) {
            }

            @Override
            public void onPause(CinematicEvent cinematic) {
            }

            @Override
            public void onStop(CinematicEvent cinematic) {

                if (!cancelled) {
                    CameraSweepDataEntry entry = cameraSweepData.getEntries().get(cameraSweepData.getEntries().size() - 1);
                    applyCameraSweepEntry(cam, Cinematic.this.start, entry, audioListener);
                }
                stateManager.detach(Cinematic.this);
            }
        });
    }

    @Override
    public void stop() {
        // Detaching a cinematic invokes stop again. This one-shot sweep must notify
        // script/menu listeners only once, including explicit stop/cancel calls.
        if (completed) return;
        completed = true;
        super.stop();
    }

    @Override
    public void play() {
        if (!completed) super.play();
    }

    /** Cancel an owned transition during teardown without moving the next screen's camera. */
    public void cancel() {
        cancelled = true;
        stop();
    }

    @Override
    public void initialize(AppStateManager stateManager, Application app) {
        super.initialize(stateManager, app);
        if (!cancelled && getPlayState() == PlayState.Playing) applySample(getTime());
    }

    @Override
    public void onPlay() {
        super.onPlay();
        if (!cancelled && isInitialized()) applySample(getTime());
    }

    @Override
    public void onUpdate(float tpf) {
        super.onUpdate(tpf);
        if (!cancelled && getPlayState() == PlayState.Playing) applySample(getTime());
    }

    private void applySample(float seconds) {
        // KCS entries are consecutive 30 Hz samples, including repeated positions.
        // Sampling by time preserves holds and rotation/FOV changes at a fixed location.
        var entries = cameraSweepData.getEntries();
        float frame = Math.max(0, Math.min(entries.size() - 1, seconds * getFramesPerSecond()));
        int index = (int) frame;
        CameraSweepDataEntry from = entries.get(index);
        CameraSweepDataEntry to = entries.get(Math.min(index + 1, entries.size() - 1));
        float fraction = frame - index;
        sampledPosition.interpolateLocal(from.getPosition(), to.getPosition(), fraction)
                .multLocal(WorldUtils.TILE_WIDTH).addLocal(start);
        sampledRotation.set(from.getRotation());
        nextRotation.set(to.getRotation());
        sampledRotation.slerp(nextRotation, fraction);
        cam.setLocation(sampledPosition);
        cam.setRotation(sampledRotation);
        float fov = FastMath.interpolateLinear(fraction, from.getFov(), to.getFov());
        cam.setFrustumPerspective(fov, (float) cam.getWidth() / cam.getHeight(), 0.1f, 100f);
        updateAudioListenerPosition(audioListener, cam);
    }

    /**
     * Apply the camera sweep entry data to the given camera
     *
     * @param cam the camera
     * @param startLocation the start location for the path
     * @param entry the entry to apply to
     * @param audioListener the audio listener position
     */
    public static void applyCameraSweepEntry(final Camera cam, final Vector3f startLocation,
            final CameraSweepDataEntry entry, Listener audioListener) {

        // Set Position
        cam.setLocation(startLocation.add(entry.getPosition().mult(WorldUtils.TILE_WIDTH)));

        // Set the rotation
        cam.setRotation(entry.getRotation());

        // FIXME: Near should be the very minimun needed, how to use the original near info
        // Set the near & FOV
        //cam.setFrustumNear(entry.getNear() / 4096f);
        cam.setFrustumPerspective(entry.getFov(), (float) cam.getWidth() / cam.getHeight(), 0.1f, 100f);

        // Update audio listener position
        updateAudioListenerPosition(audioListener, cam);
    }

    private static void updateAudioListenerPosition(final Listener audioListener, final Camera cam) {
        audioListener.setLocation(cam.getLocation());
        audioListener.setRotation(cam.getRotation());
    }

    /**
     * Animation speed, FPS
     *
     * @return FPS
     */
    protected float getFramesPerSecond() {
        return 30f;
    }
}
