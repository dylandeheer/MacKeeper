/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.fogofwar.FogState;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.view.fogofwar.FogSnapshot;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKFG v1: bounded explicit viewer knowledge and discovery history. */
public final class FogSnapshots {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static byte[] encode(FogSnapshot state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            var map = state.knowledge();
            out.writeInt(0x4d4b4647);out.writeInt(1);out.writeShort(map.viewerId());
            out.writeInt(map.width());out.writeInt(map.height());BOOLEAN.write(out, map.enabled());
            words(out, map.explored());words(out, map.perceived());words(out, map.dirty());
            FLOAT.write(out, state.visionTime());out.writeInt(state.hearts().size());
            for (var heart : state.hearts()) {POINT.write(out, heart.center());out.writeInt(heart.elapsedIntervals());}
            out.writeInt(state.pendingTags().size());
            for (var p : state.pendingTags().stream().sorted(Comparator.comparingInt((Point p) -> p.y).thenComparingInt(p -> p.x)).toList()) POINT.write(out, p);
            ids(out, state.seenEnemies());ids(out, state.observedHearts());nullable(ENTITY_ID).write(out, state.possessedCreature());
        }
        count(bytes.size(), MAX_BYTES);return bytes.toByteArray();
    }
    public static FogSnapshot decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x4d4b4647 || in.readInt() != 1) throw new IOException("Unsupported fog section");
            short viewer = in.readShort();int width = in.readInt(), height = in.readInt();boolean enabled = BOOLEAN.read(in);
            // Validate dimensions before using them to bound collections.
            var dimensions = new FogState.Snapshot(viewer, width, height, enabled, List.of(), List.of(), List.of());
            int size = dimensions.width() * dimensions.height();
            var map = new FogState.Snapshot(viewer, width, height, enabled, words(in, size), words(in, size), words(in, size));
            float time = FLOAT.read(in);int n = count(in.readInt(), FogSnapshot.MAX_ENTITIES);
            var hearts = new ArrayList<FogSnapshot.HeartReveal>();
            for (int i = 0; i < n; i++) hearts.add(new FogSnapshot.HeartReveal(POINT.read(in), in.readInt()));
            n = count(in.readInt(), size);var tags = new HashSet<Point>();
            for (int i = 0; i < n; i++) if (!tags.add(POINT.read(in))) throw new IOException("Duplicate fog tag");
            var state = new FogSnapshot(map, time, hearts, tags, ids(in), ids(in), nullable(ENTITY_ID).read(in));
            if (in.read() != -1) throw new IOException("Trailing fog data");return state;
        } catch (IllegalArgumentException | NullPointerException failure) {throw new IOException("Invalid fog section", failure);}
    }
    private static void words(DataOutput out, List<Long> words) throws IOException {
        out.writeInt(words.size());for (long word : words) out.writeLong(word);
    }
    private static List<Long> words(DataInput in, int size) throws IOException {
        int n = count(in.readInt(), (size + 63) / 64);var words = new ArrayList<Long>();
        for (int i = 0; i < n; i++) words.add(in.readLong());return words;
    }
    private static void ids(DataOutput out, Set<EntityId> ids) throws IOException {
        out.writeInt(ids.size());for (var id : ids.stream().sorted().toList()) ENTITY_ID.write(out, id);
    }
    private static Set<EntityId> ids(DataInput in) throws IOException {
        int n = count(in.readInt(), FogSnapshot.MAX_ENTITIES);var ids = new HashSet<EntityId>();
        for (int i = 0; i < n; i++) if (!ids.add(ENTITY_ID.read(in))) throw new IOException("Duplicate fog entity");
        return ids;
    }
    private FogSnapshots() {}
}
