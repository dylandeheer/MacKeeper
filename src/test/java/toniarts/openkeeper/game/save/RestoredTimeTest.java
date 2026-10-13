/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.controller.GameTimeController;
import toniarts.openkeeper.game.data.GameTimer;
import static org.junit.jupiter.api.Assertions.*;

class RestoredTimeTest {
    @Test void restoredClockSurvivesStartupAndContinuesAtTheSameTick() {
        var original = new GameTimeController();original.start();
        for (int i = 0; i < 1024; i++) original.processTick(0.125f);
        var snapshot = original.captureState();assertEquals(new GameTimeController.State(1024, 128), snapshot);
        var restored = new GameTimeController(snapshot);restored.start();
        assertEquals(128, restored.getGameTime());
        original.processTick(0.125f);restored.processTick(0.125f);
        assertEquals(original.captureState(), restored.captureState());
        assertEquals(1024, snapshot.ticks());
        var fresh = new GameTimeController();fresh.start();assertEquals(0, fresh.getGameTime());
    }

    @Test void levelTimersKeepActivityAndElapsedTimeWithoutInitialization() {
        for (boolean active : new boolean[]{false, true}) {
            var snapshot = new GameTimer.State(135.5f, active);
            var restored = GameTimer.restoreState(snapshot);
            assertEquals(snapshot, restored.captureState());restored.update(0.5f);
            assertEquals(active ? 136 : 135.5f, restored.getTime());assertEquals(active, restored.isActive());
            assertEquals(135.5f, snapshot.time());
            restored.initialize();assertEquals(0, restored.getTime());assertTrue(restored.isActive());
        }
    }

    @Test void invalidElapsedTimesAndTicksCannotEnterRestoredControllers() {
        for (float time : new float[]{-1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> new GameTimer.State(time, true));
        for (double time : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> new GameTimeController.State(1, time));
        assertThrows(IllegalArgumentException.class, () -> new GameTimeController.State(-1, 0));
    }
}
