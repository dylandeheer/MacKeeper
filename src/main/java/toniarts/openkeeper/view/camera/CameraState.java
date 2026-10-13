/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view.camera;

import com.jme3.audio.Listener;
import com.jme3.math.Quaternion;
import com.jme3.renderer.Camera;
import java.util.List;
import java.util.Objects;
import toniarts.openkeeper.cinematics.Cinematic;
import toniarts.openkeeper.game.state.session.PresentationEvent.Vec3;

/** Detached camera owner values. Pressed keys are transient input, not saved commands. */
public record CameraState(short playerId, boolean enabled, View view, View storedView,
        Frame audioListener, boolean cursorVisible, boolean transitionEnd,
        List<Movement> movements, List<Sweep> sweeps) {
    public static final int MAX_MOVEMENTS = 4096;
    public static final int MAX_SWEEPS = 256;
    public CameraState {
        if (playerId < 0 || playerId > 255) throw new IllegalArgumentException("Invalid camera player");
        Objects.requireNonNull(view);Objects.requireNonNull(audioListener);
        movements = List.copyOf(movements);sweeps = List.copyOf(sweeps);
        if (movements.size() > MAX_MOVEMENTS || sweeps.size() > MAX_SWEEPS) throw new IllegalArgumentException("Too many camera actions");
        if (!sweeps.isEmpty() && storedView == null) throw new IllegalArgumentException("Missing pre-cinematic camera");
    }
    public record Rotation(float x, float y, float z, float w) {
        public Rotation {
            float norm = x*x + y*y + z*z + w*w;
            if (!Float.isFinite(norm) || Math.abs(norm - 1) > .001f) throw new IllegalArgumentException("Invalid camera rotation");
        }
        public static Rotation of(Quaternion value) {return new Rotation(value.getX(), value.getY(), value.getZ(), value.getW());}
        public Quaternion quaternion() {return new Quaternion(x, y, z, w);}
    }
    public record Frame(Vec3 position, Rotation rotation) {
        public Frame {Objects.requireNonNull(position);Objects.requireNonNull(rotation);}
        public static Frame capture(Camera camera) {return new Frame(Vec3.of(camera.getLocation()), Rotation.of(camera.getRotation()));}
        public static Frame capture(Listener listener) {return new Frame(Vec3.of(listener.getLocation()), Rotation.of(listener.getRotation()));}
        public void apply(Camera camera) {camera.setFrame(position.vector(), rotation.quaternion());}
        public void apply(Listener listener) {listener.setLocation(position.vector());listener.setRotation(rotation.quaternion());}
    }
    public record View(Frame frame, int width, int height, boolean parallel,
            float near, float far, float left, float right, float top, float bottom,
            float viewportLeft, float viewportRight, float viewportBottom, float viewportTop) {
        public View {
            Objects.requireNonNull(frame);
            if (width < 1 || height < 1 || width > 32768 || height > 32768) throw new IllegalArgumentException("Invalid saved viewport dimensions");
            for (float v : new float[]{near, far, left, right, top, bottom, viewportLeft, viewportRight, viewportBottom, viewportTop}) finite(v);
            if (near <= 0 || far <= near || left >= right || bottom >= top
                    || viewportLeft < 0 || viewportRight > 1 || viewportLeft >= viewportRight
                    || viewportBottom < 0 || viewportTop > 1 || viewportBottom >= viewportTop) throw new IllegalArgumentException("Invalid camera frustum");
        }
        public static View capture(Camera camera) {
            return new View(Frame.capture(camera), camera.getWidth(), camera.getHeight(), camera.isParallelProjection(),
                    camera.getFrustumNear(), camera.getFrustumFar(), camera.getFrustumLeft(), camera.getFrustumRight(),
                    camera.getFrustumTop(), camera.getFrustumBottom(), camera.getViewPortLeft(), camera.getViewPortRight(),
                    camera.getViewPortBottom(), camera.getViewPortTop());
        }
        /** Keep vertical framing while adapting horizontal framing to the receiving window. */
        public void apply(Camera camera) {
            if (camera.getWidth() < 1 || camera.getHeight() < 1) throw new IllegalArgumentException("Receiving viewport has no area");
            float aspectScale = ((float) camera.getWidth() / camera.getHeight()) / ((float) width / height);
            // Validate the resized view and derived matrices on a detached camera.
            // Finite source bounds can overflow or collapse under aspect scaling.
            var prepared = new Camera(camera.getWidth(), camera.getHeight());
            applyValues(prepared, aspectScale);
            View.capture(prepared);
            var matrix = new float[16];prepared.getViewProjectionMatrix().get(matrix);
            for (float value : matrix) finite(value);
            applyValues(camera, aspectScale);
        }
        private void applyValues(Camera camera, float aspectScale) {
            frame.apply(camera);camera.setParallelProjection(parallel);
            camera.setFrustum(near, far, left * aspectScale, right * aspectScale, top, bottom);
            camera.setViewPort(viewportLeft, viewportRight, viewportBottom, viewportTop);
        }
        public Camera restore(int targetWidth, int targetHeight) {
            Camera camera = new Camera(targetWidth, targetHeight);apply(camera);return camera;
        }
    }
    public sealed interface Movement permits Zoom, Rotate {}
    public record Zoom(boolean enabled, Vec3 from, Vec3 to, float tick) implements Movement {
        public Zoom {Objects.requireNonNull(from);Objects.requireNonNull(to);finite(tick);if (tick < 0) throw new IllegalArgumentException("Negative zoom progress");}
    }
    /** Current rotation controller values, including the legacy integer duration semantics. */
    public record Rotate(boolean enabled, int remainingTime, float angle, boolean relative) implements Movement {
        public Rotate {finite(angle);}
    }
    public record Sweep(Cinematic.State playback, boolean acknowledgeSession) {
        public Sweep {Objects.requireNonNull(playback);}
    }
    private static void finite(float value) {if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite camera value");}
}
