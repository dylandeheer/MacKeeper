/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.controller.map.FlashTileControl;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Ordered map flash deadlines, independent of the current tile flashing flags. */
public final class MapControlSnapshots {
    static final int MAGIC = 0x4d4b4d46; // MKMF
    static final int VERSION = 1;
    static final int MAX_BYTES = 8 * 1024 * 1024;
    public static byte[] encode(List<FlashTileControl.State> states) throws IOException {
        count(states.size(), MAX_LIST_ITEMS);
        long size = 12;
        for (var state : states) size += 11L + 8L * state.points().size();
        if (size > MAX_BYTES) throw new IOException("Map controls exceed size limit");
        var bytes = new ByteArrayOutputStream((int) size);
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(states.size());
            for (var state : states) {
                list(POINT).write(out, state.points());out.writeShort(state.playerId());
                FLOAT.write(out, state.remainingTime());out.writeBoolean(state.enabled());
            }
        }
        return bytes.toByteArray();
    }
    public static List<FlashTileControl.State> decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported map control section");
            int n = count(in.readInt(), MAX_LIST_ITEMS);List<FlashTileControl.State> states = new ArrayList<>();
            for (int i = 0; i < n; i++) states.add(new FlashTileControl.State(list(POINT).read(in), in.readShort(), FLOAT.read(in), BOOLEAN.read(in)));
            if (in.read() != -1) throw new IOException("Trailing map control data");
            return List.copyOf(states);
        } catch (IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid map control section", e);}
    }
    private MapControlSnapshots() { }
}
