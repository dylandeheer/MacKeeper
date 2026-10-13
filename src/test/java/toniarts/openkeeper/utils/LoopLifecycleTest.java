/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.utils;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import toniarts.openkeeper.game.logic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class LoopLifecycleTest {
    @Test void discardedStagedLoopReleasesResourcesOnceAndCannotStart() throws Exception {
        var starts = new AtomicInteger();var stops = new AtomicInteger();
        var entered = new CountDownLatch(1);var finish = new CountDownLatch(1);
        var loop = new GameLoop(new IGameLogicUpdatable() {
            public void start() {starts.incrementAndGet();}
            public void processTick(float delta) {fail("Discarded loop ticked");}
            public void stop() {stops.incrementAndGet();entered.countDown();SimulationGateTest.await(finish);}
        });
        try (var threads = Executors.newFixedThreadPool(2)) {
            try {
                var first = threads.submit(loop::stop);SimulationGateTest.await(entered);
                var second = threads.submit(loop::stop);
                assertThrows(TimeoutException.class, () -> second.get(50, TimeUnit.MILLISECONDS));
                finish.countDown();first.get();second.get();
            } finally {finish.countDown();}
        }
        loop.stop();assertEquals(0, starts.get());assertEquals(1, stops.get());
        assertFalse(loop.isInitialized());assertThrows(IllegalStateException.class, loop::start);
    }

    @Test void failedStartupReleasesEveryConstructedSystem() throws Exception {
        var stops = new AtomicInteger();var reported = new CountDownLatch(1);
        var startupFailure = new IllegalStateException("startup");var observed = new AtomicReference<Throwable>();
        var loop = new GameLoop(new GameLogicManager(new IGameLogicUpdatable() {
            public void start() {throw startupFailure;}
            public void processTick(float delta) {fail("Failed startup ticked");}
            public void stop() {stops.incrementAndGet();}
        }, new IGameLogicUpdatable() {
            public void start() {fail("Startup continued after failure");}
            public void processTick(float delta) {fail("Failed startup ticked");}
            public void stop() {stops.incrementAndGet();}
        }));
        var runner = GameLoop.class.getDeclaredField("loop");runner.setAccessible(true);
        ((Thread)runner.get(loop)).setUncaughtExceptionHandler((thread, failure) -> {observed.set(failure);reported.countDown();});
        loop.start();SimulationGateTest.await(reported);loop.stop();loop.stop();
        assertSame(startupFailure, observed.get());assertEquals(2, stops.get());assertFalse(loop.isInitialized());
    }

    @Test void cleanupFailureStillStopsRemainingSystemsAndIsRetainedForEveryCloser() {
        var stops = new AtomicInteger();var failure = new IllegalArgumentException("cleanup");
        var loop = new GameLoop(new GameLogicManager(new IGameLogicUpdatable() {
            public void start() {}public void processTick(float delta) {}
            public void stop() {stops.incrementAndGet();throw failure;}
        }, new IGameLogicUpdatable() {
            public void start() {}public void processTick(float delta) {}
            public void stop() {stops.incrementAndGet();throw failure;}
        }, new IGameLogicUpdatable() {
            public void start() {}public void processTick(float delta) {}
            public void stop() {stops.incrementAndGet();}
        }));
        assertSame(failure, assertThrows(IllegalArgumentException.class, loop::stop));
        assertSame(failure, assertThrows(CompletionException.class, loop::stop).getCause());
        assertEquals(3, stops.get());assertThrows(IllegalStateException.class, loop::start);
    }
}
