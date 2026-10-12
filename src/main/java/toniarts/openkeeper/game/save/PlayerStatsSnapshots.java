/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.controller.player.PlayerStatsControl;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Stable creature IDs and explicit signed-int counters; map bindings are validated on restore. */
public final class PlayerStatsSnapshots {
    static final int MAGIC = 0x4d4b5053; // MKPS
    static final int VERSION = 1;
    static final int MAX_BYTES = 20 + 3 * 256 * 6;

    public static byte[] encode(PlayerStatsControl.State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);
            for (var counts : List.of(state.slaps(), state.pickups(), state.drops())) {
                out.writeInt(counts.size());
                for (var entry : new TreeMap<>(counts).entrySet()) {out.writeShort(entry.getKey());out.writeInt(entry.getValue());}
            }
        }
        return bytes.toByteArray();
    }

    public static PlayerStatsControl.State decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a MacKeeper player-statistics snapshot");
            if (in.readInt() != VERSION) throw new IOException("Unsupported player-statistics snapshot version");
            var result = new PlayerStatsControl.State(readMap(in), readMap(in), readMap(in));
            if (in.read() != -1) throw new IOException("Trailing player-statistics snapshot data");
            return result;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid player-statistics snapshot", e);
        }
    }

    private static Map<Short, Integer> readMap(DataInput in) throws IOException {
        int size = count(in.readInt(), 256);Map<Short, Integer> result = new HashMap<>();
        for (int i = 0; i < size; i++) {
            if (result.putIfAbsent(in.readShort(), in.readInt()) != null) throw new IOException("Duplicate creature statistic");
        }
        return result;
    }
    private PlayerStatsSnapshots() { }
}
