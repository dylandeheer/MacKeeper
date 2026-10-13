/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.jme3.cinematic.PlayState;
import java.io.*;
import java.util.*;
import toniarts.openkeeper.cinematics.Cinematic;
import toniarts.openkeeper.game.state.session.PresentationEvent.Vec3;
import toniarts.openkeeper.view.camera.CameraState;
import static toniarts.openkeeper.view.camera.CameraState.*;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKCA v1: camera values, ordered movements and active sweep playback; no engine serializers. */
public final class CameraSnapshots {
    public static final int MAX_BYTES = 1024 * 1024;
    public static byte[] encode(CameraState state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x4d4b4341);out.writeInt(1);out.writeShort(state.playerId());BOOLEAN.write(out, state.enabled());
            view(out, state.view());BOOLEAN.write(out, state.storedView() != null);
            if (state.storedView() != null) view(out, state.storedView());
            frame(out, state.audioListener());BOOLEAN.write(out, state.cursorVisible());BOOLEAN.write(out, state.transitionEnd());
            out.writeInt(state.movements().size());
            for (var movement : state.movements()) switch (movement) {
                case Zoom v -> {TEXT.write(out, "ZOOM");BOOLEAN.write(out, v.enabled());vector(out, v.from());vector(out, v.to());FLOAT.write(out, v.tick());}
                case Rotate v -> {TEXT.write(out, "ROTATE");BOOLEAN.write(out, v.enabled());out.writeInt(v.remainingTime());FLOAT.write(out, v.angle());BOOLEAN.write(out, v.relative());}
            }
            out.writeInt(state.sweeps().size());
            for (var sweep : state.sweeps()) {
                var v = sweep.playback();TEXT.write(out, v.sweepFile());vector(out, v.start());FLOAT.write(out, v.time());FLOAT.write(out, v.speed());
                enumeration(PlayState.class).write(out, v.playState());BOOLEAN.write(out, v.initialized());out.writeInt(v.samples());
                TEXT.write(out, v.sampleHash());BOOLEAN.write(out, sweep.acknowledgeSession());
            }
        }
        // Record limits and fixed-size payloads bound all allocation before this final guard.
        count(bytes.size(), MAX_BYTES);return bytes.toByteArray();
    }
    public static CameraState decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x4d4b4341 || in.readInt() != 1) throw new IOException("Unsupported camera section");
            short player = in.readShort();boolean enabled = BOOLEAN.read(in);
            var view = view(in);var stored = BOOLEAN.read(in) ? view(in) : null;var listener = frame(in);
            boolean cursor = BOOLEAN.read(in), end = BOOLEAN.read(in);
            int size = count(in.readInt(), CameraState.MAX_MOVEMENTS);List<Movement> movements = new ArrayList<>();
            for (int i = 0; i < size; i++) movements.add(switch (TEXT.read(in)) {
                case "ZOOM" -> new Zoom(BOOLEAN.read(in), vector(in), vector(in), FLOAT.read(in));
                case "ROTATE" -> new Rotate(BOOLEAN.read(in), in.readInt(), FLOAT.read(in), BOOLEAN.read(in));
                default -> throw new IOException("Unknown camera movement");
            });
            size = count(in.readInt(), CameraState.MAX_SWEEPS);List<Sweep> sweeps = new ArrayList<>();
            for (int i = 0; i < size; i++) sweeps.add(new Sweep(new Cinematic.State(TEXT.read(in), vector(in), FLOAT.read(in), FLOAT.read(in),
                    enumeration(PlayState.class).read(in), BOOLEAN.read(in), in.readInt(), TEXT.read(in)), BOOLEAN.read(in)));
            if (in.read() != -1) throw new IOException("Trailing camera section data");
            return new CameraState(player, enabled, view, stored, listener, cursor, end, movements, sweeps);
        } catch (IllegalArgumentException | NullPointerException failure) {throw new IOException("Invalid camera section", failure);}
    }
    private static void vector(DataOutput out, Vec3 v) throws IOException {FLOAT.write(out, v.x());FLOAT.write(out, v.y());FLOAT.write(out, v.z());}
    private static Vec3 vector(DataInput in) throws IOException {return new Vec3(FLOAT.read(in), FLOAT.read(in), FLOAT.read(in));}
    private static void frame(DataOutput out, Frame v) throws IOException {
        vector(out, v.position());FLOAT.write(out, v.rotation().x());FLOAT.write(out, v.rotation().y());FLOAT.write(out, v.rotation().z());FLOAT.write(out, v.rotation().w());
    }
    private static Frame frame(DataInput in) throws IOException {
        return new Frame(vector(in), new Rotation(FLOAT.read(in), FLOAT.read(in), FLOAT.read(in), FLOAT.read(in)));
    }
    private static void view(DataOutput out, View v) throws IOException {
        frame(out, v.frame());out.writeInt(v.width());out.writeInt(v.height());BOOLEAN.write(out, v.parallel());
        for (float f : new float[]{v.near(), v.far(), v.left(), v.right(), v.top(), v.bottom(), v.viewportLeft(), v.viewportRight(), v.viewportBottom(), v.viewportTop()}) FLOAT.write(out, f);
    }
    private static View view(DataInput in) throws IOException {
        return new View(frame(in), in.readInt(), in.readInt(), BOOLEAN.read(in), FLOAT.read(in), FLOAT.read(in), FLOAT.read(in), FLOAT.read(in), FLOAT.read(in), FLOAT.read(in), FLOAT.read(in), FLOAT.read(in), FLOAT.read(in), FLOAT.read(in));
    }
    private CameraSnapshots() {}
}
