/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view.camera;

import com.jme3.math.*;
import com.jme3.renderer.Camera;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.control.Container;
import toniarts.openkeeper.tools.convert.map.Thing;
import toniarts.openkeeper.view.PlayerCamera;
import static org.junit.jupiter.api.Assertions.*;

class CameraMovementStateTest {
    @Test void orderedZoomAndRotationContinueIdenticallyAcrossRepeatedIndependentRestores() throws Exception {
        var original = new Fixture();var to = new Vector3f(20, 0, 10);
        original.controls.addControl(new PlayerCameraControl(original.owner, to));to.set(100, 100, 100);
        original.controls.addControl(new PlayerCameraRotateControl(original.owner, true, 90, 30));
        var inactive = new PlayerCameraControl(original.owner, new Vector3f(40, 0, 40));inactive.setEnabled(false);original.controls.addControl(inactive);
        for (int i = 0; i < 4; i++) original.controls.update(1f / 60);
        var other = new Fixture();CameraState.View.capture(original.camera).apply(other.camera);
        other.restore(original.states());
        for (int i = 0; i < 60; i++) {
            if (i % 7 == 0) other.restore(other.states());
            original.controls.update(1f / 60);other.controls.update(1f / 60);
            assertEquals(CameraState.View.capture(original.camera), CameraState.View.capture(other.camera));
            assertEquals(original.states(), other.states());
        }
        assertEquals(1, original.controls.getNumControls());assertFalse(((PlayerCameraControl) original.controls.getControl(0)).isEnabled());
    }
    static class Fixture {
        final Camera camera = new Camera(1280, 800);final PlayerCamera owner;Container controls = new Container();
        Fixture() throws Exception {
            var preset = new Thing.Camera();
            for (var entry : Map.<String, Object>of("zoomValue", 10f, "lensValue", 4f, "anglePitch", 256, "flags", EnumSet.noneOf(Thing.Camera.CameraFlag.class)).entrySet()) {
                var field = Thing.Camera.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(preset, entry.getValue());
            }
            owner = new PlayerCamera(camera, preset);owner.setLimit(new Vector2f(100, 100));
        }
        List<CameraState.Movement> states() {
            var result = new ArrayList<CameraState.Movement>();
            for (int i = 0; i < controls.getNumControls(); i++) result.add(switch (controls.getControl(i)) {
                case PlayerCameraControl zoom -> zoom.captureState();
                case PlayerCameraRotateControl rotate -> rotate.captureState();
                default -> throw new AssertionError();
            });return result;
        }
        void restore(List<CameraState.Movement> saved) {
            var before = CameraState.View.capture(camera);controls = new Container();
            for (var state : saved) controls.addControl(switch (state) {
                case CameraState.Zoom zoom -> PlayerCameraControl.restore(zoom, owner);
                case CameraState.Rotate rotate -> PlayerCameraRotateControl.restore(rotate, owner);
            });assertEquals(before, CameraState.View.capture(camera));
        }
    }
}
