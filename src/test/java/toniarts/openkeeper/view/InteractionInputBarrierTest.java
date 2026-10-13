/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.view;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InteractionInputBarrierTest {
    @Test void oldReleasesAreIgnoredButFreshClicksWorkIndependentlyForEachButton() {
        var barrier = new InteractionInputBarrier();assertTrue(barrier.mouse(1, false));barrier.reset();
        assertFalse(barrier.mouse(0, false));assertFalse(barrier.mouse(1, false));
        assertTrue(barrier.mouse(0, true));assertFalse(barrier.mouse(1, false));
        assertTrue(barrier.mouse(1, true));assertTrue(barrier.mouse(0, false));assertTrue(barrier.mouse(1, false));
        assertFalse(barrier.mouse(1, false));
    }
    @Test void heldKeyRepeatCannotBecomeAFreshPostLoadKeyPress() {
        var barrier = new InteractionInputBarrier();barrier.reset();
        assertFalse(barrier.key(29, true, true));assertFalse(barrier.key(29, false, false));
        assertTrue(barrier.key(29, true, false));assertTrue(barrier.key(29, true, true));
        assertFalse(barrier.key(30, true, true));assertTrue(barrier.key(29, false, false));
        assertFalse(barrier.key(29, true, true));
    }
    @Test void anotherLoadOrDisableDiscardsInFlightFreshInputAgain() {
        var barrier = new InteractionInputBarrier();barrier.reset();
        barrier.mouse(1, true);barrier.key(29, true, false);barrier.reset();
        assertFalse(barrier.mouse(1, false));assertFalse(barrier.key(29, true, true));
        assertTrue(barrier.mouse(1, true));assertTrue(barrier.mouse(1, false));
    }
}
