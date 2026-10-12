/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import toniarts.openkeeper.game.controller.player.PlayerHandControl;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Versioned hand order; entity/component consistency is checked when binding the hand. */
public final class HandSnapshots {
    static final int MAGIC = 0x4d4b4853; // MKHS
    static final int VERSION = 1;
    static final int MAX_BYTES = 18 + 8 * PlayerHandControl.State.MAX_CAPACITY;

    public static byte[] encode(PlayerHandControl.State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeShort(state.playerId());
            out.writeInt(state.capacity());
            list(ENTITY_ID).write(out, state.bottomToTop());
        }
        return bytes.toByteArray();
    }

    public static PlayerHandControl.State decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a MacKeeper hand snapshot");
            if (in.readInt() != VERSION) throw new IOException("Unsupported hand snapshot version");
            var result = new PlayerHandControl.State(in.readShort(), in.readInt(), list(ENTITY_ID).read(in));
            if (in.read() != -1) throw new IOException("Trailing hand snapshot data");
            return result;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid hand snapshot", e);
        }
    }

    private HandSnapshots() { }
}
