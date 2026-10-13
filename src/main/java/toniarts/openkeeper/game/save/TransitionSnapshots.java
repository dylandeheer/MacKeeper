/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.TreeSet;
import toniarts.openkeeper.game.state.session.SessionTransitions;

/** MKTA v1: stable player IDs still awaiting a cinematic acknowledgement. */
public final class TransitionSnapshots {
    public static byte[] encode(SessionTransitions.State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x4d4b5441);out.writeInt(1);out.writeInt(state.pendingPlayers().size());
            for (short player : state.pendingPlayers()) out.writeShort(player);
        }
        return bytes.toByteArray();
    }

    public static SessionTransitions.State decode(byte[] bytes) throws IOException {
        if (bytes.length > 524) throw new IOException("Transition section too large");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x4d4b5441 || in.readInt() != 1) throw new IOException("Unsupported transition section");
            int count = in.readInt();
            if (count < 0 || count > 256) throw new IOException("Invalid transition count");
            var players = new TreeSet<Short>();
            for (int i = 0; i < count; i++) if (!players.add(in.readShort())) throw new IOException("Duplicate transition player");
            if (in.read() != -1) throw new IOException("Trailing transition section data");
            return new SessionTransitions.State(players);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IOException("Invalid transition section", invalid);
        }
    }
    private TransitionSnapshots() {}
}
