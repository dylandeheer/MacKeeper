/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import static org.junit.jupiter.api.Assertions.*;

class LevelSnapshotsTest {
    @Test void signedFlagsAllTimersAndClockRoundTripAndRestoreIndependently() throws Exception {
        var source = fixture(88.5f);
        byte[] bytes = LevelSnapshots.encode(source);
        var decoded = LevelSnapshots.decode(bytes);assertEquals(source, decoded);
        var keeper = new Keeper(false, (short) 3);
        IKwdFile definition = definition();
        var one = GameController.restoreLevelInfo(definition, decoded.level(), List.of(keeper));
        var two = GameController.restoreLevelInfo(definition, decoded.level(), List.of(keeper));
        assertSame(definition, one.getLevelData());assertSame(keeper, one.getPlayer((short) 3));
        assertEquals(source.level(), LevelState.capture(one));
        one.setFlag(127, 876);one.setLevelScore(42);one.setTimeLimit(0);one.getTimer(3).initialize();one.getTimer(3).update(2);
        assertEquals(source.level(), LevelState.capture(two));
        assertArrayEquals(bytes, LevelSnapshots.encode(source));
        var clock = new GameTimeController(decoded.clock());clock.start();clock.processTick(.5f);
        assertEquals(source.clock().elapsedSeconds() + .5, clock.getGameTime());
        assertEquals(source.clock().ticks() + 1, clock.captureState().ticks());
    }

    @Test void absentExpiredAndNewlyScriptedNegativeTimeLimitsRemainDistinct() throws Exception {
        for (Float limit : Arrays.asList(null, 0f, -1f, Float.MAX_VALUE)) {
            var source = fixture(limit);var restored = LevelSnapshots.decode(LevelSnapshots.encode(source));
            assertEquals(source, restored);
            assertEquals(limit, GameController.restoreLevelInfo(definition(), restored.level(), List.of()).getTimeLimit());
        }
    }

    @Test void malformedHeadersTimesBooleansAndTruncationsFailBeforeBinding() throws Exception {
        byte[] valid = LevelSnapshots.encode(fixture(5f));assertEquals(LevelSnapshots.MAX_BYTES, valid.length);
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);assertThrows(IOException.class, () -> LevelSnapshots.decode(truncated));
        }
        for (int offset : new int[]{0, 7, 540, 620}) {
            byte[] bad = valid.clone();bad[offset] = 2;assertThrows(IOException.class, () -> LevelSnapshots.decode(bad));
        }
        byte[] negativeCycle = valid.clone();negativeCycle[625] = (byte) 255;
        assertThrows(IOException.class, () -> LevelSnapshots.decode(negativeCycle));
        byte[] nan = valid.clone();java.nio.ByteBuffer.wrap(nan).putDouble(16, Double.NaN);
        assertThrows(IOException.class, () -> LevelSnapshots.decode(nan));
        byte[] negativeTimer = valid.clone();java.nio.ByteBuffer.wrap(negativeTimer).putFloat(536, -1);
        assertThrows(IOException.class, () -> LevelSnapshots.decode(negativeTimer));
        assertThrows(IOException.class, () -> LevelSnapshots.decode(Arrays.copyOf(valid, valid.length + 1)));
    }

    @Test void fixedShapeAndDefensiveCopiesPreventPartialLevelState() {
        List<Integer> flags = new ArrayList<>(Collections.nCopies(128, 0));
        List<GameTimer.State> timers = new ArrayList<>(Collections.nCopies(16, new GameTimer.State(0, false)));
        var state = new LevelState(flags, timers, 0, null, 0);flags.clear();timers.clear();
        assertEquals(128, state.flags().size());assertEquals(16, state.timers().size());
        assertThrows(UnsupportedOperationException.class, () -> state.flags().clear());
        assertThrows(IllegalArgumentException.class, () -> new LevelState(List.of(), state.timers(), 0, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new LevelState(state.flags(), List.of(), 0, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new LevelState(state.flags(), state.timers(), 0, Float.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new LevelState(state.flags(), state.timers(), 0, null, -1));
        var keeper = new Keeper(false, (short) 3);
        assertThrows(IllegalArgumentException.class, () -> GameController.restoreLevelInfo(definition(), state, List.of(keeper, keeper)));
    }

    static LevelSnapshots.State fixture(Float limit) {
        List<Integer> flags = new ArrayList<>();List<GameTimer.State> timers = new ArrayList<>();
        for (int i = 0; i < 128; i++) flags.add(i % 2 == 0 ? Integer.MIN_VALUE + i : Integer.MAX_VALUE - i);
        for (int i = 0; i < 16; i++) timers.add(new GameTimer.State(i + .25f, i % 2 == 1));
        return new LevelSnapshots.State(new LevelState(flags, timers, -832, limit, 5), new GameTimeController.State(9823, 8543.125));
    }
    static IKwdFile definition() {
        return (IKwdFile) Proxy.newProxyInstance(IKwdFile.class.getClassLoader(), new Class<?>[]{IKwdFile.class},
                (p, m, a) -> { if (m.getName().equals("getThings")) return List.of();throw new AssertionError(m.getName()); });
    }
}
