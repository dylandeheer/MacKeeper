/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.cinematics;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.AssetManager;
import com.jme3.audio.Listener;
import com.jme3.cinematic.PlayState;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CameraPlaybackTest {
    @Test void oneFramePathPlaysAndEndsWithoutASpline() {
        var sample = entry(4, 60, new Quaternion());
        var f = new Fixture(List.of(sample));
        f.play();
        f.cinematic.update(1f / 60);
        assertEquals(PlayState.Playing, f.cinematic.getPlayState());
        assertEquals(new Vector3f(4, 2, 3), f.camera.getLocation());
        f.cinematic.update(1f / 30);
        assertEquals(PlayState.Stopped, f.cinematic.getPlayState());
        assertEquals(f.camera.getLocation(), f.listener.getLocation());
    }

    @Test void stationarySamplesStillInterpolateRotationAndFieldOfView() {
        var rotation = new Quaternion().fromAngles(0, (float)Math.PI / 2, 0);
        var f = new Fixture(List.of(entry(4, 40, new Quaternion()), entry(4, 80, rotation)));
        f.play();f.cinematic.update(1f / 60);
        assertEquals(new Vector3f(4, 2, 3), f.camera.getLocation());
        var expected = new Quaternion().fromAngles(0, (float)Math.PI / 4, 0);
        assertTrue(Math.abs(expected.dot(f.camera.getRotation())) > .99999f);
        assertEquals(Math.tan(Math.toRadians(30)) * .1, f.camera.getFrustumTop(), .00001);
        assertEquals(f.camera.getRotation(), f.listener.getRotation());
    }

    @Test void repeatedPositionsRetainTheirAuthoredHoldAndSampleTiming() {
        var f = new Fixture(List.of(entry(0, 60, new Quaternion()), entry(0, 60, new Quaternion()),
                entry(10, 60, new Quaternion()), entry(11, 60, new Quaternion())));
        f.play();f.cinematic.update(1f / 60);
        assertEquals(0, f.camera.getLocation().x, .0001);
        f.cinematic.update(1f / 30);
        assertEquals(5, f.camera.getLocation().x, .0001);
        f.cinematic.update(1f / 30);
        assertEquals(10.5, f.camera.getLocation().x, .0001);
        f.cinematic.update(1);
        assertEquals(11, f.camera.getLocation().x, .0001);
        assertEquals(PlayState.Stopped, f.cinematic.getPlayState());
    }

    @Test void cancellationKeepsCurrentPoseAndPauseDoesNotAdvancePlayback() {
        var f = new Fixture(List.of(entry(0, 60, new Quaternion()), entry(10, 60, new Quaternion()), entry(20, 60, new Quaternion())));
        f.play();f.cinematic.update(1f / 60);f.cinematic.pause();
        var paused = f.camera.getLocation().clone();
        f.cinematic.update(1);
        assertEquals(paused, f.camera.getLocation());
        f.cinematic.play();f.cinematic.update(1f / 60);
        assertEquals(10, f.camera.getLocation().x, .0001);
        var cancelled = f.camera.getLocation().clone();
        f.cinematic.cancel();f.cinematic.update(1);
        assertEquals(cancelled, f.camera.getLocation());
        assertEquals(cancelled, f.listener.getLocation());
    }

    @Test void playbackDoesNotMutateCachedSamplesOrCallerOrigin() {
        var first = entry(0, 60, new Quaternion());
        var second = entry(10, 90, new Quaternion(0, 0, 0, -1));
        var origin = new Vector3f(100, 0, 0);
        var f = new Fixture(List.of(first, second), origin);
        origin.x = 200;
        f.play();f.cinematic.update(1f / 60);
        assertEquals(105, f.camera.getLocation().x, .0001);
        assertEquals(new Vector3f(0, 2, 3), first.getPosition());
        assertEquals(new Quaternion(0, 0, 0, -1), second.getRotation());
        f.cinematic.stop();
        assertEquals(110, f.camera.getLocation().x, .0001);
        assertEquals(200, origin.x);
    }

    @Test void emptyCameraPathIsRejectedBeforePlayback() {
        assertThrows(IllegalArgumentException.class, () -> new Fixture(List.of()));
    }

    @Test void completionNotifiesOnceAcrossDetachmentAndRepeatedStop() {
        var f = new Fixture(List.of(entry(4, 60, new Quaternion())));
        var completions = new java.util.concurrent.atomic.AtomicInteger();
        f.cinematic.addListener(new com.jme3.cinematic.events.CinematicEventListener() {
            public void onPlay(com.jme3.cinematic.events.CinematicEvent event) {}
            public void onPause(com.jme3.cinematic.events.CinematicEvent event) {}
            public void onStop(com.jme3.cinematic.events.CinematicEvent event) { completions.incrementAndGet(); }
        });
        f.play();f.cinematic.update(1);f.cinematic.stop();f.cinematic.cancel();
        assertEquals(1, completions.get());
    }

    private static CameraSweepDataEntry entry(float x, float fov, Quaternion rotation) {
        return new CameraSweepDataEntry(new Vector3f(x, 2, 3), rotation, fov, .1f);
    }

    private static class Fixture {
        final Camera camera = new Camera(1280, 800);
        final Listener listener = new Listener();
        final SimpleApplication app = new SimpleApplication() { public void simpleInitApp() {} };
        final Cinematic cinematic;

        Fixture(List<CameraSweepDataEntry> entries) { this(entries, Vector3f.ZERO); }
        Fixture(List<CameraSweepDataEntry> entries, Vector3f origin) {
            var data = new CameraSweepData(entries);
            var assets = (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                    new Class<?>[]{AssetManager.class}, (p, m, a) -> data);
            cinematic = new Cinematic(assets, camera, listener, origin, "synthetic", new Node(), app.getStateManager());
            app.getStateManager().attach(cinematic);
            cinematic.initialize(app.getStateManager(), app);
        }
        void play() { cinematic.play(); }
    }
}
