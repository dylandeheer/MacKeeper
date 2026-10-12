/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.*;
import java.util.*;
import toniarts.openkeeper.game.controller.GameTimeController;
import toniarts.openkeeper.game.data.*;
import static toniarts.openkeeper.game.save.SaveValues.*;

/** Level flags/timers and global game clock. Definitions and controller state are separate. */
public final class LevelSnapshots {
    static final int MAGIC = 0x4d4b4c53; // MKLS
    static final int VERSION = 1;
    static final int MAX_BYTES = 633;

    public record State(LevelState level, GameTimeController.State clock) {
        public State { Objects.requireNonNull(level);Objects.requireNonNull(clock); }
    }

    public static byte[] encode(State state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(VERSION);
            out.writeLong(state.clock().ticks());out.writeDouble(state.clock().elapsedSeconds());
            for (int flag : state.level().flags()) out.writeInt(flag);
            for (var timer : state.level().timers()) {out.writeFloat(timer.time());out.writeBoolean(timer.active());}
            out.writeInt(state.level().score());nullable(FLOAT).write(out, state.level().timeLimit());
            out.writeLong(state.level().paydayCycle());
        }
        return bytes.toByteArray();
    }

    public static State decode(byte[] bytes) throws IOException {
        count(bytes.length, MAX_BYTES);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a MacKeeper level snapshot");
            if (in.readInt() != VERSION) throw new IOException("Unsupported level snapshot version");
            var clock = new GameTimeController.State(in.readLong(), DOUBLE.read(in));
            List<Integer> flags = new ArrayList<>(LevelState.FLAG_COUNT);
            for (int i = 0; i < LevelState.FLAG_COUNT; i++) flags.add(in.readInt());
            List<GameTimer.State> timers = new ArrayList<>(LevelState.TIMER_COUNT);
            for (int i = 0; i < LevelState.TIMER_COUNT; i++) timers.add(new GameTimer.State(FLOAT.read(in), BOOLEAN.read(in)));
            var level = new LevelState(flags, timers, in.readInt(), nullable(FLOAT).read(in), in.readLong());
            if (in.read() != -1) throw new IOException("Trailing level snapshot data");
            return new State(level, clock);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid level snapshot", e);
        }
    }
    private LevelSnapshots() { }
}
