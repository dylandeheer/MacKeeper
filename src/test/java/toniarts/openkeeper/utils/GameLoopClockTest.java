/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.utils;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameLoopClockTest {
    @Test void repeatedRestoreAtDifferentOriginsRetainsNextDeadlineAndElapsedUpdates() {
        var original = new GameLoopClock(new GameLoopClock.State(100, 0, 0, false));
        original.start(1000);
        assertEquals(OptionalLong.of(117), original.poll(1117));
        var saved = original.capture(1160);
        assertEquals(new GameLoopClock.State(100, 17, 43, false), saved);
        assertEquals(40, original.sleepNanos(1160));
        long now = 1160;
        for (int cycle = 0; cycle < 50; cycle++) {
            long otherNow = 9_000_000_000L + cycle * 10_000_000L;
            var restored = new GameLoopClock(original.capture(now));restored.start(otherNow);
            assertEquals(original.capture(now), restored.capture(otherNow));
            for (long step : new long[]{1, 7, 11, 39, 100, 297, 0, 43}) {
                now += step;otherNow += step;
                assertEquals(original.poll(now), restored.poll(otherNow));
                assertEquals(original.capture(now), restored.capture(otherNow));
                assertEquals(original.sleepNanos(now), restored.sleepNanos(otherNow));
            }
        }
    }

    @Test void pausedFractionSurvivesLongPauseAndRestorationWithoutCountingDowntime() {
        var clock = new GameLoopClock(new GameLoopClock.State(100, 0, 0, false));clock.start(0);
        assertEquals(OptionalLong.of(117), clock.poll(117));clock.pause(150);
        var state = new GameLoopClock.State(100, 17, 33, true);
        assertEquals(state, clock.capture(1_000_000));assertTrue(clock.poll(1_000_000).isEmpty());
        clock.pause(2_000_000);assertEquals(state, clock.capture(2_000_000));
        var restored = new GameLoopClock(state);restored.start(5_000_000);
        assertEquals(state, restored.capture(9_000_000));assertEquals(50, restored.sleepNanos(9_000_000));
        clock.resume(2_000_000);restored.resume(9_000_000);
        assertTrue(clock.poll(2_000_049).isEmpty());assertTrue(restored.poll(9_000_049).isEmpty());
        assertEquals(OptionalLong.of(83), clock.poll(2_000_050));
        assertEquals(OptionalLong.of(83), restored.poll(9_000_050));
        assertEquals(clock.capture(2_000_050), restored.capture(9_000_050));
    }

    @Test void lateFramesConsumeElapsedOnceAndSleepOnlyUntilTheRemainingDeadline() {
        var clock = new GameLoopClock(new GameLoopClock.State(100, 0, 0, false));clock.start(0);
        assertEquals(OptionalLong.of(357), clock.poll(357));
        assertTrue(clock.poll(357).isEmpty());assertEquals(43, clock.sleepNanos(357));
        assertEquals(13, clock.sleepNanos(387));assertTrue(clock.poll(399).isEmpty());
        assertEquals(0, clock.sleepNanos(400));assertEquals(OptionalLong.of(43), clock.poll(400));
        assertTrue(clock.poll(400).isEmpty());assertEquals(100, clock.sleepNanos(400));
    }

    @Test void pauseBeforeStartupAndResumeBeforeStartupPreserveSavedPendingTime() {
        var state = new GameLoopClock.State(100, 5, 10, false);
        var clock = new GameLoopClock(state);clock.pause(4000);clock.start(9000);
        assertEquals(new GameLoopClock.State(100, 5, 10, true), clock.capture(10_000));
        var restored = new GameLoopClock(clock.capture(10_000));restored.resume(100_000);restored.start(1_000_000);
        assertEquals(state, restored.capture(1_000_000));
    }

    @Test void invalidStatesLifecycleAndBackwardTimeFailWhileNanoTimeWrapWorks() {
        for (long[] values : new long[][]{{0,0,0},{-1,0,0},{10,-1,0},{10,10,0},{10,0,-1},{10,0,Long.MAX_VALUE}})
            assertThrows(IllegalArgumentException.class, () -> new GameLoopClock.State(values[0],values[1],values[2],false));
        var clock = new GameLoopClock(new GameLoopClock.State(100, 0, 0, false));
        assertThrows(IllegalStateException.class, () -> clock.capture(0));
        assertThrows(IllegalStateException.class, () -> clock.poll(0));
        clock.start(Long.MAX_VALUE-50);
        assertThrows(IllegalStateException.class, () -> clock.start(0));
        assertThrows(IllegalArgumentException.class, () -> clock.poll(Long.MAX_VALUE-51));
        assertEquals(OptionalLong.of(101), clock.poll(Long.MIN_VALUE+50));
        assertEquals(new GameLoopClock.State(100,1,0,false), clock.capture(Long.MIN_VALUE+50));
        var wide = new GameLoopClock(new GameLoopClock.State(Long.MAX_VALUE,Long.MAX_VALUE-2,0,false));wide.start(0);
        assertEquals(OptionalLong.of(Long.MAX_VALUE-1), wide.poll(Long.MAX_VALUE-1));
        assertEquals(Long.MAX_VALUE-3, wide.capture(Long.MAX_VALUE-1).remainderNanos());
    }
}
