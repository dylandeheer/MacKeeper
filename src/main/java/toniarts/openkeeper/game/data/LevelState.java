/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.data;

import java.util.*;
import toniarts.openkeeper.game.controller.ILevelInfo;

/** Mutable level values, separate from map identity, keepers and action-point trigger state. */
public record LevelState(List<Integer> flags, List<GameTimer.State> timers,
        int score, Float timeLimit, long paydayCycle) {
    public static final int FLAG_COUNT = 128;
    public static final int TIMER_COUNT = 16;

    public LevelState {
        flags = List.copyOf(flags);timers = List.copyOf(timers);
        if (flags.size() != FLAG_COUNT || timers.size() != TIMER_COUNT || paydayCycle < 0
                || (timeLimit != null && !Float.isFinite(timeLimit))) {
            throw new IllegalArgumentException("Invalid level values");
        }
        // Flags, score and a newly scripted time limit may be signed. The
        // normal update clamps an expired time limit; capture must not alter it.
    }

    public static LevelState capture(ILevelInfo info) {
        List<Integer> flags = new ArrayList<>(FLAG_COUNT);
        List<GameTimer.State> timers = new ArrayList<>(TIMER_COUNT);
        for (int i = 0; i < FLAG_COUNT; i++) flags.add(info.getFlag(i));
        for (int i = 0; i < TIMER_COUNT; i++) timers.add(info.getTimer(i).captureState());
        return new LevelState(flags, timers, info.getLevelScore(), info.getTimeLimit(), info.getPaydayCycle());
    }
}
