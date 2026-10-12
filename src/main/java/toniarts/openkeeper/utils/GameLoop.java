/*
 * Copyright (C) 2014-2018 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenKeeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenKeeper.  If not, see <http://www.gnu.org/licenses/>.
 */
package toniarts.openkeeper.utils;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import toniarts.openkeeper.game.logic.IGameLogicUpdatable;

/**
 * A game loop. This is a fork of Paul Speeds class of a same name.
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class GameLoop {

    private static final Logger logger = System.getLogger(GameLoop.class.getName());

    public static final long INTERVAL_FPS_60 = 16666667L;
    private static final String NAME_PREFIX = "Game";

    private final IGameLogicUpdatable gameLoopManager;
    private final Runner loop;

    private final SimulationGate simulationGate;
    private volatile boolean initialized;
    private final long updateRate;
    private final AtomicBoolean pauseFlag = new AtomicBoolean(false);

    public GameLoop(IGameLogicUpdatable gameLoopManager) {
        this(gameLoopManager, INTERVAL_FPS_60); // 60 FPS
    }

    public GameLoop(IGameLogicUpdatable gameLoopManager, long updateRateNanos) {
        this(gameLoopManager, updateRateNanos, "");
    }

    public GameLoop(IGameLogicUpdatable gameLoopManager, long updateRateNanos, String name) {
        this(gameLoopManager, updateRateNanos, name, new SimulationGate());
    }

    public GameLoop(IGameLogicUpdatable gameLoopManager, long updateRateNanos, String name, SimulationGate simulationGate) {
        if (updateRateNanos <= 0) throw new IllegalArgumentException("Update rate must be positive");
        this.simulationGate = java.util.Objects.requireNonNull(simulationGate);
        this.gameLoopManager = java.util.Objects.requireNonNull(gameLoopManager);
        this.updateRate = updateRateNanos;
        this.loop = new Runner(NAME_PREFIX + " " + name);
    }

    /**
     * Starts the background game loop thread and initializes and starts the game system manager (if it hasn't
     * been initialized or started already). The systems will be initialized and started on the game loop
     * background thread.
     */
    public void start() {
        loop.start();
    }

    /**
     * Stops the background game loop thread, stopping and terminating the game systems. This method will wait
     * until the thread has been fully shut down before returning. The systems will be stopped and terminated
     * on the game loop background thread.
     */
    public void stop() {
        loop.close();
    }

    /** Signal shutdown without waiting, so a group can stop all loops before joining any. */
    public void requestStop() {
        loop.go.set(false);
        resume();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void pause() {
        pauseFlag.set(true);
    }

    public void resume() {
        pauseFlag.set(false);
        synchronized (pauseFlag) {
            pauseFlag.notify();
        }
    }

    /**
     * Use our own thread instead of a java executor because we need more control over the update loop.
     * ScheduledThreadPoolExecutor will try to call makeup frames if it gets behind and we'd rather just drop
     * them. Furthermore, this allows us to 'busy wait' for the next 'frame'.
     */
    protected final class Runner extends Thread {

        private final AtomicBoolean go = new AtomicBoolean(true);

        public Runner(String name) {
            super(name);
        }

        public void close() {
            if (Thread.currentThread() == this) {
                throw new IllegalStateException("A game loop cannot wait for its own shutdown; use requestStop");
            }
            requestStop();
            boolean interrupted = false;
            while (isAlive()) {
                try {
                    join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        private long simulationTime() {
            return System.nanoTime() - simulationGate.suspendedNanos();
        }

        @Override
        public void run() {
            simulationGate.mutate(() -> {
                gameLoopManager.start();
                initialized = true;
            });

            try {
                long frameRate = 0;
                simulationGate.enterUpdate();
                long lastTime;
                try {
                    lastTime = simulationTime();
                } finally {
                    simulationGate.leaveUpdate();
                }
                while (go.get()) {
                    // Check pause
                    if (pauseFlag.get()) {
                        synchronized (pauseFlag) {
                            // We are in a while loop here to protect against spurious interrupts
                            while (pauseFlag.get()) {
                                try {
                                    pauseFlag.wait();
                                    // Reset outside the pause monitor after acquiring the gate.
                                    // A snapshot may have overlapped this pause.
                                    lastTime = Long.MIN_VALUE;
                                } catch (InterruptedException e) {
                                    throw new RuntimeException("Interrupted sleeping", e);
                                }
                            }
                            continue;
                        }
                    }

                    simulationGate.enterUpdate();
                    try {
                        long time = simulationTime();
                        if (lastTime == Long.MIN_VALUE) lastTime = time;
                        long delta = time - lastTime;
                        if (delta + frameRate >= updateRate) {
                            lastTime = time;
                            frameRate += delta - updateRate;
                            long tickStart = System.nanoTime();
                            gameLoopManager.processTick(delta / 1_000_000_000f);
                            long tickTime = System.nanoTime() - tickStart;
                            logger.log(tickTime < updateRate ? Level.TRACE : Level.ERROR, "Loop \"{0}\" took {1} ms!",
                                    getName(), TimeUnit.NANOSECONDS.toMillis(tickTime));
                        }
                    } finally {
                        simulationGate.leaveUpdate();
                    }

                    // Wait just a little.  This is an important enough thread
                    // that we'll poll instead of smart-sleep but we also don't
                    // want to consume 100% of a CPU core.
                    // Thread.sleep(0) relies on the operating systems thread
                    // scheduler which will have a minimum granularity.  On Windows,
                    // Thread.sleep(0) can take as long as 15 ms to return.  Thus
                    // note that if we devolve into a case where the delta is more
                    // than the update rate then we never hit this code.
                    //
                    // And if the delta since last poll was kind of large
                    // we won't sleep at all... In this case, if the delta was
                    // more than half the updateRate... we'll skip sleeping.
                    // Which probably means that on Windows, we'll be responsive
                    // every other loop iteration.  It's an imperfect world and
                    // the only alternative is true CPU heating busy-waiting.
                    long sleep = TimeUnit.NANOSECONDS.toMillis(updateRate - frameRate);
                    if (sleep > 0) {
                        try {
                            // More then 0 here and we underflow constantly.
                            // 0 and we gobble up noticable CPU.  Slower update
                            // rates could probably get away with the longer sleep
                            // but 60 FPS can't keep up on Windows.  So we'll let
                            // the sleep time be externally defined for cases
                            // where the caller knows  better.
                            Thread.sleep(sleep);
                        } catch (InterruptedException e) {
                            throw new RuntimeException("Interrupted sleeping", e);
                        }
                    }
                }

            } finally {
                simulationGate.mutate(() -> {
                    initialized = false;
                    gameLoopManager.stop();
                });
            }
        }
    }
}
