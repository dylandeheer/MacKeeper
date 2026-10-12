/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.data.*;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Ordered controller indexes and explicit bindings to the separate keeper section. */
public final class PlayerControlSnapshots {
    static final int MAGIC = 0x4d4b5043; // MKPC
    static final int VERSION = 1;
    static final int MAX_BYTES = 2 * 1024 * 1024;

    public static byte[] encode(PlayerControlState state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeShort(state.playerId());out.writeInt(state.goldMax());
            nullable(SHORT).write(out, state.impType());list(SHORT).write(out, state.creaturesAvailable());
            writeGroups(out, state.creatures());writeGroups(out, state.rooms());out.writeBoolean(state.portalsOpen());nullable(ENTITY_ID).write(out, state.dungeonHeart());
            for (var index : List.of(state.roomResearch(), state.spells(), state.doors(), state.traps())) {
                list(SHORT).write(out, index.available());out.writeInt(index.entries().size());
                for (var entry : index.entries()) {
                    var v = entry.value();out.writeShort(v.id());enumeration(ResearchableType.class).write(out, v.type());
                    out.writeBoolean(v.spell());out.writeBoolean(v.discovered());out.writeInt(v.research());out.writeBoolean(v.upgraded());
                    enumeration(PlayerControlState.Binding.class).write(out, entry.binding());
                }
            }
            out.writeBoolean(state.hand() != null);
            if (state.hand() != null) writeSection(out, HandSnapshots.encode(state.hand()));
            writeSection(out, PlayerStatsSnapshots.encode(state.statistics()));
        }
        count(bytes.size(), MAX_BYTES);return bytes.toByteArray();
    }

    public static PlayerControlState decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported player controller section");
            short player = in.readShort();int max = in.readInt();Short imp = nullable(SHORT).read(in);
            var available = readIds(in);var creatures = readGroups(in);var rooms = readGroups(in);
            boolean portals = BOOLEAN.read(in);var heart = nullable(ENTITY_ID).read(in);
            List<PlayerControlState.ResearchIndex> indexes = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                var types = readIds(in);int n = count(in.readInt(), 256);List<PlayerControlState.ResearchEntry> entries = new ArrayList<>();
                for (int j = 0; j < n; j++) entries.add(new PlayerControlState.ResearchEntry(new KeeperState.Research(in.readShort(),
                        enumeration(ResearchableType.class).read(in), BOOLEAN.read(in), BOOLEAN.read(in), in.readInt(), BOOLEAN.read(in)),
                        enumeration(PlayerControlState.Binding.class).read(in)));
                indexes.add(new PlayerControlState.ResearchIndex(types, entries));
            }
            var hand = BOOLEAN.read(in) ? HandSnapshots.decode(readSection(in, HandSnapshots.MAX_BYTES)) : null;
            var stats = PlayerStatsSnapshots.decode(readSection(in, PlayerStatsSnapshots.MAX_BYTES));
            if (in.read() != -1) throw new IOException("Trailing player control data");
            return new PlayerControlState(player, max, imp, available, creatures, rooms, portals, heart,
                    indexes.get(0), indexes.get(1), indexes.get(2), indexes.get(3), hand, stats);
        } catch (IllegalArgumentException | NullPointerException e) {throw new IOException("Invalid player controller section", e);}
    }
    private static List<Short> readIds(DataInput in) throws IOException {
        int n = count(in.readInt(), 256);List<Short> ids = new ArrayList<>();for (int i = 0; i < n; i++) ids.add(in.readShort());return ids;
    }
    private static void writeGroups(DataOutput out, List<PlayerControlState.Group> groups) throws IOException {
        out.writeInt(groups.size());for (var group : groups) {out.writeShort(group.type());list(ENTITY_ID).write(out, group.entities());}
    }
    private static List<PlayerControlState.Group> readGroups(DataInput in) throws IOException {
        int n = count(in.readInt(), 256), total = 0;List<PlayerControlState.Group> groups = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            var group = new PlayerControlState.Group(in.readShort(), list(ENTITY_ID).read(in));
            total += group.entities().size();count(total, PlayerControlState.MAX_ENTITIES);groups.add(group);
        }
        return groups;
    }
    private static void writeSection(DataOutput out, byte[] bytes) throws IOException {out.writeInt(bytes.length);out.write(bytes);}
    private static byte[] readSection(DataInput in, int limit) throws IOException {
        byte[] bytes = new byte[count(in.readInt(), limit)];in.readFully(bytes);return bytes;
    }
    private PlayerControlSnapshots() { }
}
