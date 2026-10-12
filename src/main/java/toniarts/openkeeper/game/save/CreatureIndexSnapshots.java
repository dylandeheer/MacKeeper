/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.logic.PlayerCreatureSystem;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Last-observed membership, including ECS changes awaiting the next counter-system tick. */
public final class CreatureIndexSnapshots {
    static final int MAGIC = 0x4d4b4349; // MKCI
    static final int VERSION = 1;
    static final int MAX_BYTES = 12 + 65_536 * 12;
    public static byte[] encode(PlayerCreatureSystem.State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(state.entries().size());
            for (var entry : state.entries()) {ENTITY_ID.write(out, entry.entityId());out.writeShort(entry.ownerId());out.writeShort(entry.creatureId());}
        }
        return bytes.toByteArray();
    }
    public static PlayerCreatureSystem.State decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported creature index section");
            int n = count(in.readInt(), 65_536);List<PlayerCreatureSystem.Entry> entries = new ArrayList<>();
            for (int i = 0; i < n; i++) entries.add(new PlayerCreatureSystem.Entry(ENTITY_ID.read(in), in.readShort(), in.readShort()));
            if (in.read() != -1) throw new IOException("Trailing creature index data");
            return new PlayerCreatureSystem.State(entries);
        } catch (IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid creature index section", e);}
    }
    private CreatureIndexSnapshots() { }
}
