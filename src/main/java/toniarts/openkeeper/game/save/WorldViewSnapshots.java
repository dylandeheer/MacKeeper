/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import com.simsilica.es.EntityId;
import toniarts.openkeeper.view.WorldViewState;
import toniarts.openkeeper.view.WorldViewState.*;
import toniarts.openkeeper.utils.Point;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKWV v1: exact flash membership/phase and unit indicator display clocks. */
public final class WorldViewSnapshots {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    public static byte[] encode(WorldViewState saved) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x4d4b5756);out.writeInt(1);out.writeShort(saved.playerId());out.writeInt(saved.width());out.writeInt(saved.height());
            var flash = saved.flash();BOOLEAN.write(out, flash.enabled());FLOAT.write(out, flash.tick());BOOLEAN.write(out, flash.phase());
            points(out, flash.points());points(out, flash.painted());
            out.writeInt(saved.flowers().size());
            for (var entry : new TreeMap<>(saved.flowers()).entrySet()) {
                ENTITY_ID.write(out, entry.getKey());var flower = entry.getValue();
                enumeration(Kind.class).write(out, flower.kind());out.writeShort(flower.definitionId());
                BOOLEAN.write(out, flower.visible());FLOAT.write(out, flower.elapsed());FLOAT.write(out, flower.deadline());
                if (flower.kind() == Kind.CREATURE) {
                    enumeration(Status.class).write(out, flower.cycle().status());
                    FLOAT.write(out, flower.cycle().statusTime());FLOAT.write(out, flower.cycle().redrawTime());
                }
            }
        }
        count(bytes.size(), MAX_BYTES);return bytes.toByteArray();
    }
    public static WorldViewState decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x4d4b5756 || in.readInt() != 1) throw new IOException("Unsupported world view section");
            short player = in.readShort();int width = in.readInt(), height = in.readInt();
            var flash = new Flash(BOOLEAN.read(in), FLOAT.read(in), BOOLEAN.read(in), points(in), points(in));
            int size = count(in.readInt(), WorldViewState.MAX_FLOWERS);var flowers = new LinkedHashMap<EntityId, Flower>();
            for (int i = 0; i < size; i++) {
                var id = ENTITY_ID.read(in);var kind = enumeration(Kind.class).read(in);short definition = in.readShort();
                boolean visible = BOOLEAN.read(in);float elapsed = FLOAT.read(in), deadline = FLOAT.read(in);
                Cycle cycle = kind == Kind.CREATURE ? new Cycle(enumeration(Status.class).read(in), FLOAT.read(in), FLOAT.read(in)) : null;
                if (flowers.put(id, new Flower(kind, definition, visible, elapsed, deadline, cycle)) != null)
                    throw new IOException("Duplicate unit flower");
            }
            var state = new WorldViewState(player, width, height, flash, flowers);
            if (in.read() != -1) throw new IOException("Trailing world view data");return state;
        } catch (IllegalArgumentException | NullPointerException failure) {throw new IOException("Invalid world view section", failure);}
    }
    private static void points(DataOutputStream out, Set<Point> points) throws IOException {
        out.writeInt(points.size());
        for (Point p : points.stream().sorted(Comparator.comparingInt((Point p) -> p.y).thenComparingInt(p -> p.x)).toList()) POINT.write(out, p);
    }
    private static Set<Point> points(DataInputStream in) throws IOException {
        int size = count(in.readInt(), WorldViewState.MAX_TILES);var points = new HashSet<Point>();
        for (int i = 0; i < size; i++) if (!points.add(POINT.read(in))) throw new IOException("Duplicate flashing tile");
        return points;
    }
    private WorldViewSnapshots() {}
}
