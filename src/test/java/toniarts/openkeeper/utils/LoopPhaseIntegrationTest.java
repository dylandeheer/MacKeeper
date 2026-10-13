/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.utils;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import toniarts.openkeeper.game.logic.IGameLogicUpdatable;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class LoopPhaseIntegrationTest {
    @Test void snapshotUsesOneFrozenTimestampAcrossSlowCaptureAndExcludesCopyTime() throws Exception {
        var gate = new SimulationGate();var ticked = new CountDownLatch(1);var loop = new GameLoop(system(ticked),10_000_000,"Phase",gate);
        loop.start();
        try {
            SimulationGateTest.await(ticked);
            assertThrows(IllegalStateException.class,loop::capturePhase);
            gate.snapshot(() -> {
                var first = loop.capturePhase();
                try {Thread.sleep(100);}catch(InterruptedException e){throw new AssertionError(e);}
                assertEquals(first,loop.capturePhase());return null;
            });
        } finally {loop.stop();}
    }

    @Test void restoredPausedRunnerWaitsForResumeAndPreservesPendingTime() throws Exception {
        var gate = new SimulationGate();var started = new CountDownLatch(1);var ticked = new CountDownLatch(1);
        var state = new GameLoopClock.State(10_000_000,1234,5678,true);
        var delta = new AtomicReference<Float>();var count = new AtomicInteger();
        var loop = new GameLoop(new IGameLogicUpdatable() {
            public void start(){started.countDown();}public void stop(){}
            public void processTick(float tpf){delta.compareAndSet(null,tpf);count.incrementAndGet();ticked.countDown();}
        },state,"RestoredPhase",gate);
        // Construction and loading delay must never enter the saved clock.
        Thread.sleep(100);loop.start();
        try {
            SimulationGateTest.await(started);
            assertEquals(state,gate.snapshot(loop::capturePhase));
            assertFalse(ticked.await(100,TimeUnit.MILLISECONDS));
            assertEquals(state,gate.snapshot(loop::capturePhase));assertEquals(0,count.get());
            loop.resume();SimulationGateTest.await(ticked);
            assertTrue(delta.get()>0 && delta.get()<.5f,"Loading/paused wall time leaked into update: "+delta.get());
        } finally {loop.stop();}
    }

    @Test void initializationAndClockReadinessShareTheSameBoundary() throws Exception {
        var gate = new SimulationGate();var starting = new CountDownLatch(1);var finish = new CountDownLatch(1);
        var loop = new GameLoop(new IGameLogicUpdatable() {
            public void start(){starting.countDown();SimulationGateTest.await(finish);}public void stop(){}public void processTick(float tpf){}
        },new GameLoopClock.State(10_000_000,11,22,true),"Readiness",gate);
        assertThrows(IllegalStateException.class,()->gate.snapshot(loop::capturePhase));loop.start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                SimulationGateTest.await(starting);assertFalse(loop.isInitialized());
                var capture = executor.submit(()->gate.snapshot(loop::capturePhase));
                assertThrows(TimeoutException.class,()->capture.get(50,TimeUnit.MILLISECONDS));
                finish.countDown();assertEquals(new GameLoopClock.State(10_000_000,11,22,true),capture.get());
            } finally {finish.countDown();}
        } finally {loop.stop();}
    }

    @Test void concurrentPauseResumeAndTickSamplingNeverApplyAnOlderTimestamp() throws Exception {
        var gate = new SimulationGate();var ticked = new CountDownLatch(1);var failure = new AtomicReference<Throwable>();
        var loop = new GameLoop(system(ticked),100_000,"ConcurrentPhase",gate);
        var field = GameLoop.class.getDeclaredField("loop");field.setAccessible(true);
        ((Thread)field.get(loop)).setUncaughtExceptionHandler((thread,error)->failure.set(error));
        loop.start();
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                SimulationGateTest.await(ticked);
                Callable<Void> toggle = () -> {for(int i=0;i<2000;i++){loop.pause();loop.resume();}return null;};
                var first=executor.submit(toggle);var second=executor.submit(toggle);
                first.get();second.get();
                assertTrue(loop.isInitialized());assertNull(failure.get());
                assertNotNull(gate.snapshot(loop::capturePhase));
            } finally {loop.stop();}
        }
        assertNull(failure.get());
    }

    private static IGameLogicUpdatable system(CountDownLatch ticked) {
        return new IGameLogicUpdatable(){public void start(){}public void stop(){}public void processTick(float delta){ticked.countDown();}};
    }
}
