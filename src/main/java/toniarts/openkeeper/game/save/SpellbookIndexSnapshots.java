/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.data.ResearchableType;
import toniarts.openkeeper.game.logic.PlayerSpellbookSystem;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Observed stored research books, including pending transfers, additions and removals. */
public final class SpellbookIndexSnapshots {
    static final int MAGIC = 0x4d4b5349; // MKSI
    static final int VERSION = 1;
    static final int MAX_BYTES = 12 + 65_536 * 21; // ID, owner, longest named enum, type ID
    private static final Value<ResearchableType> TYPE = enumeration(ResearchableType.class);

    public static byte[] encode(PlayerSpellbookSystem.State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(state.entries().size());
            for (var entry : state.entries()) {
                ENTITY_ID.write(out, entry.entityId());out.writeShort(entry.ownerId());
                TYPE.write(out, entry.type());out.writeShort(entry.typeId());
            }
        }
        return bytes.toByteArray();
    }

    public static PlayerSpellbookSystem.State decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported spellbook index section");
            int n = count(in.readInt(), 65_536);List<PlayerSpellbookSystem.Entry> entries = new ArrayList<>();
            for (int i = 0; i < n; i++) entries.add(new PlayerSpellbookSystem.Entry(
                    ENTITY_ID.read(in), in.readShort(), TYPE.read(in), in.readShort()));
            if (in.read() != -1) throw new IOException("Trailing spellbook index data");
            return new PlayerSpellbookSystem.State(entries);
        } catch (IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid spellbook index section", e);}
    }
    private SpellbookIndexSnapshots() { }
}
