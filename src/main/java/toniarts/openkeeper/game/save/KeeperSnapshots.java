/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.data.KeeperState;
import toniarts.openkeeper.game.data.ResearchableType;
import toniarts.openkeeper.tools.convert.map.AI.AIType;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Explicit keeper section; map definitions and player controllers are separate. */
public final class KeeperSnapshots {
    static final int MAGIC = 0x4d4b4b53; // MKKS
    static final int VERSION = 1;
    static final int MAX_BYTES = 131_072;

    private static final Value<KeeperState.Research> RESEARCH = new Value<>() {
        public KeeperState.Research copy(KeeperState.Research value) {return Objects.requireNonNull(value);}
        public void write(DataOutput out, KeeperState.Research value) throws IOException {
            out.writeShort(value.id());enumeration(ResearchableType.class).write(out, value.type());
            out.writeBoolean(value.spell());out.writeBoolean(value.discovered());out.writeInt(value.research());out.writeBoolean(value.upgraded());
        }
        public KeeperState.Research read(DataInput in) throws IOException {
            return new KeeperState.Research(in.readShort(), enumeration(ResearchableType.class).read(in),
                    BOOLEAN.read(in), BOOLEAN.read(in), in.readInt(), BOOLEAN.read(in));
        }
    };

    public static byte[] encode(KeeperState state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeShort(state.id());out.writeBoolean(state.ai());
            nullable(enumeration(AIType.class)).write(out, state.aiType());
            out.writeLong(state.gold());out.writeInt(state.goldReserve());out.writeInt(state.goldMined());
            out.writeInt(state.mana());out.writeInt(state.manaGain());out.writeInt(state.manaLoose());out.writeInt(state.maxMana());
            nullable(POINT).write(out, state.dungeonHeartLocation());out.writeBoolean(state.possession());
            writeCatalog(out, state.availableRooms());writeCatalog(out, state.availableSpells());
            writeIds(out, state.availableCreatures());writeCatalog(out, state.availableDoors());writeCatalog(out, state.availableTraps());
            nullable(RESEARCH).write(out, state.currentResearch());out.writeBoolean(state.currentResearchShared());
            out.writeBoolean(state.destroyed());out.writeShort(state.killedByPlayerId());
            out.writeLong(state.creaturesLost());out.writeLong(state.enemyCreaturesKilled());out.writeLong(state.heroesKilled());
            for (long value : state.creatureKillsByPlayer()) out.writeLong(value);
            writeIds(out, new TreeSet<>(state.allies()));
        }
        count(bytes.size(), MAX_BYTES);return bytes.toByteArray();
    }

    public static KeeperState decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a MacKeeper keeper snapshot");
            if (in.readInt() != VERSION) throw new IOException("Unsupported keeper snapshot version");
            short id = in.readShort();boolean ai = BOOLEAN.read(in);AIType aiType = nullable(enumeration(AIType.class)).read(in);
            long gold = in.readLong();int reserve = in.readInt(), mined = in.readInt();
            int mana = in.readInt(), gain = in.readInt(), loose = in.readInt(), max = in.readInt();
            var heart = nullable(POINT).read(in);boolean possession = BOOLEAN.read(in);
            var rooms = readCatalog(in);var spells = readCatalog(in);var creatures = readIds(in);var doors = readCatalog(in);var traps = readCatalog(in);
            var current = nullable(RESEARCH).read(in);boolean shared = BOOLEAN.read(in), destroyed = BOOLEAN.read(in);short killer = in.readShort();
            long lost = in.readLong(), enemyKills = in.readLong(), heroes = in.readLong();
            List<Long> kills = new ArrayList<>(256);for (int i = 0; i < 256; i++) kills.add(in.readLong());
            var allies = readIds(in);
            if (new HashSet<>(allies).size() != allies.size()) throw new IOException("Duplicate alliance in keeper snapshot");
            if (in.read() != -1) throw new IOException("Trailing keeper snapshot data");
            return new KeeperState(id, ai, aiType, gold, reserve, mined, mana, gain, loose, max, heart, possession,
                    rooms, spells, creatures, doors, traps, current, shared, destroyed, killer, lost, enemyKills, heroes, kills, new HashSet<>(allies));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid keeper snapshot", e);
        }
    }

    private static void writeCatalog(DataOutput out, List<KeeperState.Research> values) throws IOException {
        out.writeInt(values.size());for (var value : values) RESEARCH.write(out, value);
    }
    private static List<KeeperState.Research> readCatalog(DataInput in) throws IOException {
        int size = count(in.readInt(), KeeperState.MAX_TYPES);List<KeeperState.Research> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) values.add(RESEARCH.read(in));return values;
    }
    private static void writeIds(DataOutput out, Collection<Short> values) throws IOException {
        out.writeInt(values.size());for (short value : values) out.writeShort(value);
    }
    private static List<Short> readIds(DataInput in) throws IOException {
        int size = count(in.readInt(), KeeperState.MAX_TYPES);List<Short> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) values.add(in.readShort());return values;
    }
    private KeeperSnapshots() { }
}
