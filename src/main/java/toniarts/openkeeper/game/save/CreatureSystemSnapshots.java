/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import toniarts.openkeeper.game.logic.CreatureAiSystem;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Last observed active membership, independent of the current ECS query. */
public final class CreatureSystemSnapshots {
    private static final int MAGIC = 0x4d4b4341; // MKCA
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 12 + 65_536 * 8;
    public static byte[] encode(CreatureAiSystem.SavedState state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);list(ENTITY_ID).write(out,state.activeControllers());
        }
        return bytes.toByteArray();
    }
    public static CreatureAiSystem.SavedState decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt()!=MAGIC || in.readInt()!=VERSION) throw new IOException("Unsupported creature system section");
            var state = new CreatureAiSystem.SavedState(list(ENTITY_ID).read(in));
            if (in.read()!=-1) throw new IOException("Trailing creature system data");return state;
        } catch (IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid creature system section",e);}
    }
    private CreatureSystemSnapshots() { }
}
