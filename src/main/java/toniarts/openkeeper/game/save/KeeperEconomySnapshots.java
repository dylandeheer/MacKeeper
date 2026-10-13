/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.utils.Point;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Decision cadence, remembered deposits and unfinished construction, independent of authored AI settings. */
public final class KeeperEconomySnapshots {
    public record Plan(short room, int x, int y, int width, int height) {
        public Plan {
            if (room < 0 || room > 255 || x < 0 || y < 0 || width < 1 || height < 1
                    || (long) width * height > 65536 || (long) x + width > Integer.MAX_VALUE
                    || (long) y + height > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid economy plan");
        }
    }
    public record Brain(double elapsed, Set<Point> knownGold, Plan plan, int unchanged, int progress) {
        public Brain {
            knownGold = Set.copyOf(knownGold);
            if (!Double.isFinite(elapsed) || elapsed < 0 || unchanged < 0 || unchanged > 60 || progress < -1
                    || knownGold.size() > 65536) throw new IllegalArgumentException("Invalid economy state");
            for (var point : knownGold) if (point.x < 0 || point.y < 0) throw new IllegalArgumentException("Invalid remembered deposit");
        }
    }
    public record State(Map<Short, Brain> players) {
        public State {
            players = Map.copyOf(players);
            if (players.size() > 253) throw new IllegalArgumentException("Too many economy players");
            long points = 0;
            for (var entry : players.entrySet()) {
                if (entry.getKey() < 3 || entry.getKey() > 255) throw new IllegalArgumentException("Invalid economy player");
                points += entry.getValue().knownGold().size();
            }
            if (points > 1_000_000) throw new IllegalArgumentException("Too many remembered deposits");
        }
    }
    private static final int MAGIC = 0x4d4b4543, VERSION = 1, MAX_BYTES = 16 * 1024 * 1024;
    public static byte[] encode(State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(state.players().size());
            for (var entry : new TreeMap<>(state.players()).entrySet()) {
                out.writeShort(entry.getKey());var brain = entry.getValue();DOUBLE.write(out, brain.elapsed());
                out.writeInt(brain.knownGold().size());
                for (var point : brain.knownGold().stream().sorted(Comparator.comparingInt((Point p) -> p.y).thenComparingInt(p -> p.x)).toList()) POINT.write(out, point);
                out.writeBoolean(brain.plan() != null);
                if (brain.plan() != null) {
                    var plan = brain.plan();out.writeShort(plan.room());out.writeInt(plan.x());out.writeInt(plan.y());out.writeInt(plan.width());out.writeInt(plan.height());
                }
                out.writeInt(brain.unchanged());out.writeInt(brain.progress());
            }
        }
        count(bytes.size(), MAX_BYTES);return bytes.toByteArray();
    }
    public static State decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported economy section");
            int n = count(in.readInt(), 253);var players = new TreeMap<Short, Brain>();int total = 0;
            for (int i = 0; i < n; i++) {
                short owner = in.readShort();double elapsed = DOUBLE.read(in);
                int size = count(in.readInt(), 65536);total += size;count(total, 1_000_000);
                var known = new HashSet<Point>();
                for (int j = 0; j < size; j++) if (!known.add(POINT.read(in))) throw new IOException("Duplicate remembered deposit");
                Plan plan = BOOLEAN.read(in) ? new Plan(in.readShort(), in.readInt(), in.readInt(), in.readInt(), in.readInt()) : null;
                var brain = new Brain(elapsed, known, plan, in.readInt(), in.readInt());
                if (players.put(owner, brain) != null) throw new IOException("Duplicate economy player");
            }
            var result = new State(players);if (in.read() != -1) throw new IOException("Trailing economy data");return result;
        } catch (IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid economy section", e);}
    }
    private KeeperEconomySnapshots() { }
}
