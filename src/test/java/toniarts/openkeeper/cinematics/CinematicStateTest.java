/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.cinematics;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.AssetManager;
import com.jme3.audio.Listener;
import com.jme3.cinematic.PlayState;
import com.jme3.cinematic.events.*;
import com.jme3.math.*;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.view.camera.CameraState;
import static org.junit.jupiter.api.Assertions.*;

class CinematicStateTest {
    @Test void repeatedRestoreResumesFutureSamplesWithoutStartStopOrInitializationWrites() {
        var original = new Fixture();var live = original.create();original.attach(live);live.play();live.update(.075f);
        var saved = live.captureState();var pose = CameraState.View.capture(original.camera);
        var receiver = new Fixture();var callbacks = new ArrayList<String>();Cinematic restored = null;
        for (int i = 0; i < 4; i++) {
            if (restored != null) restored.discard();
            restored = receiver.restore(saved);watch(restored, callbacks);
            assertEquals(saved, restored.captureState());assertTrue(callbacks.isEmpty());
            receiver.camera.setLocation(new Vector3f(80, 90, 100));receiver.listener.setLocation(new Vector3f(10, 11, 12));
            receiver.attach(restored);
            assertEquals(new Vector3f(80, 90, 100), receiver.camera.getLocation());
            assertEquals(new Vector3f(10, 11, 12), receiver.listener.getLocation());
            assertEquals(saved, restored.captureState());assertTrue(callbacks.isEmpty());
            saved = restored.captureState();
        }
        pose.apply(receiver.camera);CameraState.Frame.capture(original.listener).apply(receiver.listener);
        for (float dt : new float[]{.01f, .03f, .1f, .2f}) {
            live.update(dt);restored.update(dt);
            assertEquals(CameraState.View.capture(original.camera), CameraState.View.capture(receiver.camera));
            assertEquals(CameraState.Frame.capture(original.listener), CameraState.Frame.capture(receiver.listener));
            assertEquals(live.getPlayState(), restored.getPlayState());
            if (live.getPlayState() == PlayState.Playing) assertEquals(live.captureState(), restored.captureState());
        }
        assertEquals(List.of("stop"), callbacks);restored.stop();restored.discard();assertEquals(List.of("stop"), callbacks);
    }
    @Test void pausedPlaybackStaysPausedAndUninitializedPlaybackKeepsItsFirstSamplePending() {
        var f = new Fixture();var live = f.create();live.play();var pending = live.captureState();assertFalse(pending.initialized());
        var receiver = new Fixture();var restored = receiver.restore(pending);
        receiver.camera.setLocation(new Vector3f(100, 100, 100));
        assertEquals(new Vector3f(100, 100, 100), receiver.camera.getLocation());
        f.attach(live);receiver.attach(restored);
        assertEquals(CameraState.View.capture(f.camera), CameraState.View.capture(receiver.camera));
        live.update(.05f);live.pause();var paused = live.captureState();
        restored.discard();restored = receiver.restore(paused);receiver.attach(restored);
        var before = CameraState.View.capture(receiver.camera);restored.update(30);
        assertEquals(before, CameraState.View.capture(receiver.camera));assertEquals(paused, restored.captureState());
        assertEquals(PlayState.Paused, restored.getPlayState());
    }
    @Test void changedInstalledSweepRejectsWithoutTouchingCameraAndDiscardDoesNotNotify() {
        var f = new Fixture();var live = f.create();f.attach(live);live.play();live.update(.05f);
        var saved = live.captureState();var changed = new Fixture(3);
        var before = CameraState.View.capture(changed.camera);
        assertThrows(IllegalArgumentException.class, () -> changed.restore(saved));
        assertEquals(before, CameraState.View.capture(changed.camera));
        var calls = new ArrayList<String>();watch(live, calls);var pose = CameraState.View.capture(f.camera);
        live.discard();live.cancel();live.update(100);assertTrue(calls.isEmpty());assertEquals(pose, CameraState.View.capture(f.camera));
        assertThrows(IllegalStateException.class, live::captureState);
    }
    @Test @org.junit.jupiter.api.Timeout(5)
    void emptySweepTimelineCannotOverflowItsFrameScanAtExtremePlaybackSpeed() {
        var source = new Fixture();var sweep = source.create();source.attach(sweep);sweep.play();
        var saved = sweep.captureState();
        var extreme = new Cinematic.State(saved.sweepFile(), saved.start(), saved.time(), Float.MAX_VALUE,
                saved.playState(), saved.initialized(), saved.samples(), saved.sampleHash());
        var target = new Fixture();var restored = target.restore(extreme);target.attach(restored);
        restored.update(1f / 60);
        assertEquals(PlayState.Stopped, restored.getPlayState());
        assertTrue(Float.isFinite(target.camera.getLocation().x));
    }

    private static void watch(Cinematic sweep, List<String> calls) {
        sweep.addListener(new CinematicEventListener() {
            public void onPlay(CinematicEvent event) {calls.add("play");}
            public void onPause(CinematicEvent event) {calls.add("pause");}
            public void onStop(CinematicEvent event) {calls.add("stop");}
        });
    }
    static class Fixture {
        final Camera camera = new Camera(1280, 800);final Listener listener = new Listener();
        final SimpleApplication app = new SimpleApplication() {public void simpleInitApp() {}};
        final AssetManager assets;
        Fixture() {this(0);}
        Fixture(int offset) {
            var entries = new ArrayList<CameraSweepDataEntry>();
            for (int i = 0; i < 8; i++) entries.add(new CameraSweepDataEntry(new Vector3f(i + offset, 2, 3), new Quaternion().fromAngles(0, i * .1f, 0), 40 + i, .1f));
            var data = new CameraSweepData(entries);
            assets = (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(), new Class[]{AssetManager.class}, (p, m, a) -> data);
        }
        Cinematic create() {return new Cinematic(assets, camera, listener, new Vector3f(4, 5, 6), "synthetic", new Node(), app.getStateManager());}
        Cinematic restore(Cinematic.State state) {return Cinematic.restore(state, assets, camera, listener, new Node(), app.getStateManager());}
        void attach(Cinematic sweep) {app.getStateManager().attach(sweep);sweep.initialize(app.getStateManager(), app);}
    }
}
