/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view.camera;

import com.jme3.cinematic.PlayState;
import com.jme3.math.*;
import com.jme3.renderer.Camera;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.cinematics.Cinematic;
import toniarts.openkeeper.game.save.CameraSnapshots;
import toniarts.openkeeper.game.state.session.PresentationEvent.Vec3;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.view.camera.CameraState.*;

class CameraSnapshotsTest {
    @Test void cameraCodecPreservesOrderedActionsAndIndependentViewValues() throws Exception {
        var camera = new Camera(1280, 800);camera.setFrustumPerspective(60, 1.6f, .1f, 100);
        camera.setLocation(new Vector3f(5, 6, 7));camera.setRotation(new Quaternion().fromAngles(.1f, .2f, .3f));
        var movements = new ArrayList<Movement>(List.of(new Zoom(true, new Vec3(1, 0, 2), new Vec3(3, 0, 4), .35f), new Rotate(false, 27, .3f, true)));
        var saved = new CameraState((short) 3, false, View.capture(camera), View.capture(camera), Frame.capture(camera), true, false, movements,
                List.of(new Sweep(new Cinematic.State("EnginePath67", new Vec3(1, 2, 3), 2.1f, 1, PlayState.Playing, true, 300, "a".repeat(64)), true)));
        movements.clear();camera.setLocation(new Vector3f(10, 20, 30));
        byte[] bytes = CameraSnapshots.encode(saved);
        for (int i = 0; i < bytes.length; i++) {var truncated = Arrays.copyOf(bytes, i);assertThrows(IOException.class, () -> CameraSnapshots.decode(truncated));}
        for (int i = 0; i < 4; i++) {
            var restored = CameraSnapshots.decode(bytes);assertEquals(saved, restored);assertArrayEquals(bytes, CameraSnapshots.encode(restored));
            assertEquals(new Vector3f(5, 6, 7), restored.view().restore(1280, 800).getLocation());
        }
        assertThrows(UnsupportedOperationException.class, () -> saved.movements().clear());
        var empty = new CameraState((short) 3, true, saved.view(), null, saved.audioListener(), true, true, List.of(), List.of());
        assertEquals(empty, CameraSnapshots.decode(CameraSnapshots.encode(empty)));
    }
    @Test void newViewportRetainsVerticalFramingAndDoesNotMutateSavedPose() {
        var original = new Camera(1280, 800);original.setFrustumPerspective(60, 1.6f, .1f, 100);
        original.setViewPort(.1f, .9f, .2f, .8f);var saved = View.capture(original);
        var wide = saved.restore(1600, 800);
        assertEquals(saved.top(), wide.getFrustumTop());assertEquals(saved.left() * 1.25f, wide.getFrustumLeft());
        assertEquals(saved.viewportLeft(), wide.getViewPortLeft());assertEquals(saved, View.capture(original));
        var again = saved.restore(1280, 800);assertEquals(saved, View.capture(again));
    }
    @Test void invalidResizedProjectionFailsBeforeChangingTheReceivingCamera() {
        var target = new Camera(1600, 800);target.setFrustumPerspective(60, 2, .1f, 100);
        var before = View.capture(target);
        for (float bound : new float[]{Float.MAX_VALUE, Float.MIN_VALUE}) {
            int sourceWidth = bound == Float.MIN_VALUE ? 32768 : 1;
            var invalid = new View(before.frame(), sourceWidth, 800, false, .1f, 100,
                    -bound, bound, 1, -1, 0, 1, 0, 1);
            assertThrows(IllegalArgumentException.class, () -> invalid.apply(target));
            assertEquals(before, View.capture(target));
        }
        var invalidMatrix = new View(before.frame(), 1600, 800, false, Float.MAX_VALUE / 2, Float.MAX_VALUE,
                -1, 1, 1, -1, 0, 1, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> invalidMatrix.apply(target));
        assertEquals(before, View.capture(target));
    }

    @Test void rejectsMalformedCameraAndPlaybackRecordsAndAllTruncations() throws Exception {
        var camera = new Camera(1280, 800);camera.setFrustumPerspective(60, 1.6f, .1f, 100);
        var view = View.capture(camera);
        var state = new CameraState((short) 3, true, view, null, Frame.capture(camera), true, true, List.of(), List.of());
        byte[] bytes = CameraSnapshots.encode(state);
        for (int i = 0; i < bytes.length; i++) {var truncated = Arrays.copyOf(bytes, i);assertThrows(IOException.class, () -> CameraSnapshots.decode(truncated));}
        assertThrows(IOException.class, () -> CameraSnapshots.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        for (int offset : new int[]{0, 4}) {var bad = bytes.clone();ByteBuffer.wrap(bad).putInt(offset, 99);assertThrows(IOException.class, () -> CameraSnapshots.decode(bad));}
        var invalidBoolean = bytes.clone();invalidBoolean[10] = 3;assertThrows(IOException.class, () -> CameraSnapshots.decode(invalidBoolean));
        var invalidRotation = bytes.clone();ByteBuffer.wrap(invalidRotation).putFloat(23, Float.NaN);assertThrows(IOException.class, () -> CameraSnapshots.decode(invalidRotation));
        assertThrows(IllegalArgumentException.class, () -> new Rotation(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Cinematic.State("../path", new Vec3(0, 0, 0), 0, 1, PlayState.Playing, false, 30, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new Cinematic.State("path", new Vec3(0, 0, 0), 1, 1, PlayState.Playing, false, 30, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new CameraState((short) 3, true, view, null, state.audioListener(), true, true, Collections.nCopies(MAX_MOVEMENTS + 1, new Rotate(true, 1, 1, false)), List.of()));
        assertThrows(IOException.class, () -> CameraSnapshots.decode(new byte[CameraSnapshots.MAX_BYTES + 1]));
    }
}
