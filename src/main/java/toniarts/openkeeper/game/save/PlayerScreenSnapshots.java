/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import toniarts.openkeeper.game.state.session.PlayerScreenState;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKUI v1: logical screen destination and local pause/result-input lifecycle. */
public final class PlayerScreenSnapshots {
    private static final int MAX_BYTES = 256 * 1024;
    public static byte[] encode(PlayerScreenState state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x4d4b5549);out.writeInt(1);out.writeShort(state.playerId());
            BOOLEAN.write(out,state.playerEnabled());BOOLEAN.write(out,state.paused());BOOLEAN.write(out,state.transitionEnd());
            enumeration(PlayerScreenState.Route.class).write(out,state.route());
            nullable(TEXT).write(out,state.cinematicText());BOOLEAN.write(out,state.awaitingEndInput());
        }
        count(bytes.size(),MAX_BYTES);return bytes.toByteArray();
    }
    public static PlayerScreenState decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=0x4d4b5549 || in.readInt()!=1)throw new IOException("Unsupported player screen section");
            var state=new PlayerScreenState(in.readShort(),BOOLEAN.read(in),BOOLEAN.read(in),BOOLEAN.read(in),
                    enumeration(PlayerScreenState.Route.class).read(in),nullable(TEXT).read(in),BOOLEAN.read(in));
            if(in.read()!=-1)throw new IOException("Trailing player screen data");
            return state;
        } catch(IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid player screen section",e);}
    }
    private PlayerScreenSnapshots() { }
}
