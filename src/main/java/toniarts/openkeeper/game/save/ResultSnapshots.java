/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.data.GameResult;
import toniarts.openkeeper.game.data.GameResult.ResultType;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** MKGR v1: completed player outcomes with explicit primitive statistic types. */
public final class ResultSnapshots {
    private static final int MAGIC = 0x4d4b4752;
    private static final int MAX_PLAYERS = 256;
    private static final int MAX_BYTES = 256 * 1024;
    private static final Value<ResultType> KEY = enumeration(ResultType.class);

    public record State(Map<Short, GameResult.State> results) {
        public State {
            Objects.requireNonNull(results);
            if (results.size() > MAX_PLAYERS) throw new IllegalArgumentException("Too many results");
            var copy = new TreeMap<Short, GameResult.State>();
            results.forEach((player, result) -> {
                if (player == null || player < 0 || player > 255) throw new IllegalArgumentException("Invalid result player");
                Objects.requireNonNull(result);
                if (!(result.values().get(ResultType.LEVEL_WON) instanceof Boolean)
                        || !(result.values().get(ResultType.TIME_TAKEN) instanceof Number))
                    throw new IllegalArgumentException("Missing terminal outcome or time");
                copy.put(player, result);
            });
            results = Collections.unmodifiableMap(copy);
        }
    }

    public static byte[] encode(State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(1);out.writeInt(state.results().size());
            for (var entry : state.results().entrySet()) {
                out.writeShort(entry.getKey());out.writeInt(entry.getValue().values().size());
                for (var statistic : entry.getValue().values().entrySet()) {
                    KEY.write(out, statistic.getKey());
                    switch (statistic.getValue()) {
                        case null -> out.writeByte(0);
                        case Boolean value -> {out.writeByte(1);out.writeBoolean(value);}
                        case Byte value -> {out.writeByte(2);out.writeByte(value);}
                        case Short value -> {out.writeByte(3);out.writeShort(value);}
                        case Integer value -> {out.writeByte(4);out.writeInt(value);}
                        case Long value -> {out.writeByte(5);out.writeLong(value);}
                        case Float value -> {out.writeByte(6);FLOAT.write(out, value);}
                        case Double value -> {out.writeByte(7);DOUBLE.write(out, value);}
                        default -> throw new IOException("Unsupported result statistic");
                    }
                }
            }
        }
        count(bytes.size(), MAX_BYTES);
        return bytes.toByteArray();
    }

    public static State decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != 1) throw new IOException("Unsupported result section");
            int players = count(in.readInt(), MAX_PLAYERS);
            var results = new TreeMap<Short, GameResult.State>();
            for (int i = 0; i < players; i++) {
                short player = in.readShort();
                int size = count(in.readInt(), ResultType.values().length);
                var values = new EnumMap<ResultType, Object>(ResultType.class);
                for (int j = 0; j < size; j++) {
                    var key = KEY.read(in);
                    if (values.containsKey(key)) throw new IOException("Duplicate result statistic");
                    Object value = switch (in.readUnsignedByte()) {
                        case 0 -> null;
                        case 1 -> BOOLEAN.read(in);
                        case 2 -> in.readByte();
                        case 3 -> in.readShort();
                        case 4 -> in.readInt();
                        case 5 -> in.readLong();
                        case 6 -> FLOAT.read(in);
                        case 7 -> DOUBLE.read(in);
                        default -> throw new IOException("Unknown result value type");
                    };
                    values.put(key, value);
                }
                if (results.putIfAbsent(player, new GameResult.State(values)) != null)
                    throw new IOException("Duplicate result player");
            }
            if (in.read() != -1) throw new IOException("Trailing result section data");
            return new State(results);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid result section", e);
        }
    }
    private ResultSnapshots() { }
}
