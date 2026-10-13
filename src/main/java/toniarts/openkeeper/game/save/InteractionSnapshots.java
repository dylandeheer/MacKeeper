/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import toniarts.openkeeper.view.InteractionSelection;
import toniarts.openkeeper.view.PlayerInteractionState.InteractionState.Type;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKIS v1: player, input-enabled state and named tool selection. */
public final class InteractionSnapshots {
    public static final int MAX_BYTES = 128;
    public static byte[] encode(InteractionSelection state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x4d4b4953);out.writeInt(1);out.writeShort(state.playerId());BOOLEAN.write(out, state.enabled());
            enumeration(Type.class).write(out, state.type());out.writeInt(state.itemId());
        }
        count(bytes.size(), MAX_BYTES);return bytes.toByteArray();
    }
    public static InteractionSelection decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x4d4b4953 || in.readInt() != 1) throw new IOException("Unsupported interaction section");
            var state = new InteractionSelection(in.readShort(), BOOLEAN.read(in), enumeration(Type.class).read(in), in.readInt());
            if (in.read() != -1) throw new IOException("Trailing interaction data");return state;
        } catch (IllegalArgumentException | NullPointerException failure) {throw new IOException("Invalid interaction section", failure);}
    }
    private InteractionSnapshots() {}
}
