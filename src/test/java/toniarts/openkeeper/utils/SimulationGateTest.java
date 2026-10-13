/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.utils;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import toniarts.openkeeper.game.logic.IGameLogicUpdatable;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SimulationGateTest {
    @Test void captureWaitsForAllUpdatesAndCommandsWaitUntilCaptureEnds() throws Exception {
        var gate=new SimulationGate();
        var updatesEntered=new CountDownLatch(2);var finishUpdates=new CountDownLatch(1);
        var captureEntered=new CountDownLatch(1);var finishCapture=new CountDownLatch(1);
        int[] values=new int[2];
        try(var executor=Executors.newFixedThreadPool(4)) {
            try {
                Future<?> a=executor.submit(()->gate.mutate(()->{values[0]=1;updatesEntered.countDown();await(finishUpdates);values[0]=2;}));
                Future<?> b=executor.submit(()->gate.mutate(()->{values[1]=1;updatesEntered.countDown();await(finishUpdates);values[1]=2;}));
                await(updatesEntered); // Normal updates remain concurrent.
                Future<int[]> capture=executor.submit(()->gate.snapshot(()->{
                    captureEntered.countDown();await(finishCapture);return values.clone();
                }));
                assertThrows(TimeoutException.class,()->capture.get(50,TimeUnit.MILLISECONDS));
                finishUpdates.countDown();a.get();b.get();await(captureEntered);
                Future<?> command=executor.submit(()->gate.mutate(()->values[0]=3));
                assertThrows(TimeoutException.class,()->command.get(50,TimeUnit.MILLISECONDS));
                finishCapture.countDown();assertArrayEquals(new int[]{2,2},capture.get());command.get();
                assertArrayEquals(new int[]{3,2},gate.snapshot(()->values.clone()));
            } finally {finishUpdates.countDown();finishCapture.countDown();}
        }
    }

    @Test void failedCaptureAndFailedCommandReleaseLocksAndNestedCapturesFailFast() {
        var gate=new SimulationGate();
        assertThrows(IllegalArgumentException.class,()->gate.snapshot(()->{throw new IllegalArgumentException();}));
        assertThrows(IllegalArgumentException.class,()->gate.mutate(()->{throw new IllegalArgumentException();}));
        gate.mutate(()->assertThrows(IllegalStateException.class,()->gate.snapshot(()->1)));
        gate.snapshot(()->{
            assertThrows(IllegalStateException.class,()->gate.snapshot(()->1));
            assertThrows(IllegalStateException.class,()->gate.mutate(()->{}));return null;
        });
        gate.mutate(()->gate.mutate(()->{})); // Nested simulation callbacks are supported.
        assertEquals(7,gate.snapshot(()->7));
    }

    @Test void realLoopsFreezeTogetherAndCaptureTimeDoesNotBecomeGameTime() throws Exception {
        var gate=new SimulationGate();var firstTicks=new CountDownLatch(3);
        var observedAfter=new CountDownLatch(3);var captureFinished=new AtomicBoolean();
        var elapsed=new double[3];var postCaptureDelta=new float[3];
        var loops=new GameLoop[3];
        for(int i=0;i<3;i++) {
            int index=i;var once=new AtomicBoolean();var first=new AtomicBoolean();
            loops[i]=new GameLoop(new IGameLogicUpdatable(){
                public void start(){}public void stop(){}
                public void processTick(float tpf){
                    elapsed[index]+=tpf;if(first.compareAndSet(false,true))firstTicks.countDown();
                    if(captureFinished.get()&&once.compareAndSet(false,true)){postCaptureDelta[index]=tpf;observedAfter.countDown();}
                }
            },10_000_000L,"SnapshotTest"+i,gate);
        }
        try {
            for(var loop:loops)loop.start();await(firstTicks);
            gate.snapshot(()->{
                double[] before=elapsed.clone();
                try{Thread.sleep(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                assertArrayEquals(before,elapsed);captureFinished.set(true);return null;
            });
            await(observedAfter);
            for(float delta:postCaptureDelta)assertTrue(delta>=0&&delta<.5f,"Capture leaked into game time: "+delta);
        } finally {for(var loop:loops)loop.stop();}
        for(var loop:loops)assertFalse(loop.isInitialized());
    }

    @Test void pausedLoopCanBeCapturedAndThenStoppedWithoutDeadlock() throws Exception {
        var gate=new SimulationGate();var ticked=new CountDownLatch(1);var stopped=new AtomicBoolean();
        var loop=new GameLoop(new IGameLogicUpdatable(){
            public void start(){}public void processTick(float tpf){ticked.countDown();}public void stop(){stopped.set(true);}
        },1_000_000L,"PausedSnapshot",gate);
        loop.start();
        try{await(ticked);loop.pause();assertEquals(1,gate.snapshot(()->1));}finally{loop.stop();}
        assertTrue(stopped.get());assertFalse(loop.isInitialized());
    }

    @Test void interruptedAndConcurrentClosersWaitForCleanup() throws Exception {
        var ready=new CountDownLatch(1);var cleanupEntered=new CountDownLatch(1);
        var finishCleanup=new CountDownLatch(1);var interruptPreserved=new AtomicBoolean();
        var loop=new GameLoop(new IGameLogicUpdatable(){
            public void start(){ready.countDown();}public void processTick(float tpf){}
            public void stop(){cleanupEntered.countDown();await(finishCleanup);}
        },1_000_000L,"InterruptedClose");
        loop.start();await(ready);
        try(var executor=Executors.newFixedThreadPool(2)) {
            try {
                Future<?> first=executor.submit(()->{
                    Thread.currentThread().interrupt();loop.stop();
                    interruptPreserved.set(Thread.currentThread().isInterrupted());
                    Thread.interrupted();
                });
                await(cleanupEntered);
                Future<?> second=executor.submit(loop::stop);
                assertThrows(TimeoutException.class,()->first.get(50,TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class,()->second.get(50,TimeUnit.MILLISECONDS));
                finishCleanup.countDown();first.get();second.get();
                assertTrue(interruptPreserved.get());assertFalse(loop.isInitialized());
            } finally {finishCleanup.countDown();}
        } finally {loop.stop();}
    }

    static void await(CountDownLatch latch){
        try{assertTrue(latch.await(5,TimeUnit.SECONDS),"Timed out waiting for concurrent operation");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
}
