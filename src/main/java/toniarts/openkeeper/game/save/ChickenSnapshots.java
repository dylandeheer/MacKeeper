/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.controller.chicken.ChickenController;
import toniarts.openkeeper.game.controller.chicken.ChickenState;
import toniarts.openkeeper.game.logic.ChickenAiSystem;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Explicit chicken FSMs, observed view membership and pending view-change identities. */
public final class ChickenSnapshots {
    static final int MAGIC = 0x4d4b4348; // MKCH
    static final int VERSION = 1;
    static final int MAX_BYTES = 20 + 65_536 * 85;
    private static final Value<ChickenState> STATE = nullable(enumeration(ChickenState.class));
    public static byte[] encode(ChickenAiSystem.SavedState state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(state.controllers().size());
            for (var c : state.controllers()) {
                ENTITY_ID.write(out,c.entityId());STATE.write(out,c.current());STATE.write(out,c.previous());STATE.write(out,c.global());FLOAT.write(out,c.motionless());
            }
            list(ENTITY_ID).write(out,state.viewIds());list(ENTITY_ID).write(out,state.dirtyViews());
        }
        return bytes.toByteArray();
    }
    public static ChickenAiSystem.SavedState decode(byte[] bytes) throws IOException {
        count(bytes.length,MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt()!=MAGIC || in.readInt()!=VERSION) throw new IOException("Unsupported chicken section");
            int n=count(in.readInt(),65_536);var controllers=new ArrayList<ChickenController.SavedState>();
            for (int i=0;i<n;i++) controllers.add(new ChickenController.SavedState(ENTITY_ID.read(in),STATE.read(in),STATE.read(in),STATE.read(in),FLOAT.read(in)));
            var state=new ChickenAiSystem.SavedState(controllers,list(ENTITY_ID).read(in),list(ENTITY_ID).read(in));
            if (in.read()!=-1) throw new IOException("Trailing chicken data");return state;
        } catch (IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid chicken section",e);}
    }
    private ChickenSnapshots() { }
}
