/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.task;

import java.time.Instant;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.task.worker.DigTileTask;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class SimulationTaskClockTest {
    @Test void restoredClockGivesIdenticalFutureTaskIdentitiesAndStableEqualTimeOrdering() {
        double[] seconds = {1.25};
        var source = TaskIdentityAllocator.forSimulation(new TaskIdentityAllocator.State(0), () -> seconds[0]);
        var first = new DigTileTask(source, null, null, new Point(1, 1), (short)3);
        var second = new DigTileTask(source, null, null, new Point(2, 1), (short)3);
        assertEquals(Instant.EPOCH.plusMillis(1250), first.getTaskCreated());
        assertEquals(first.getTaskCreated(), second.getTaskCreated());
        var ordered = new TreeSet<AbstractTask>(AbstractTask::compareTo);ordered.add(second);ordered.add(first);
        assertEquals(2, ordered.size());assertSame(first, ordered.first());
        var restored = TaskIdentityAllocator.forSimulation(source.captureState(), () -> seconds[0]);
        seconds[0] = 14.75;
        assertEquals(source.allocate(), restored.allocate());assertEquals(source.captureState(), restored.captureState());
    }

    @Test void invalidClockDoesNotConsumeAnIdentity() {
        for (double time : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, 1e20}) {
            var allocator = TaskIdentityAllocator.forSimulation(new TaskIdentityAllocator.State(7), () -> time);
            assertThrows(IllegalStateException.class, allocator::allocate);
            assertEquals(7, allocator.captureState().nextId());
        }
    }
}
