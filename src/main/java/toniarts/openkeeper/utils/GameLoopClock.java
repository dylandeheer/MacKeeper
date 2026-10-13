/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.utils;

import java.util.OptionalLong;

/** Relative scheduler phase, independent of a process's monotonic-clock origin. */
public final class GameLoopClock {
    // Reserve half the signed monotonic range for elapsed time after restoration.
    public static final long MAX_PENDING_NANOS = Long.MAX_VALUE / 2;

    public record State(long intervalNanos, long remainderNanos, long pendingNanos, boolean paused) {
        public State {
            if (intervalNanos <= 0 || remainderNanos < 0 || remainderNanos >= intervalNanos || pendingNanos < 0 || pendingNanos > MAX_PENDING_NANOS)
                throw new IllegalArgumentException("Invalid game loop phase");
        }
    }
    private final long interval;
    private long remainder, pending, lastTick;
    private boolean paused, started;

    public GameLoopClock(State state) {
        interval = state.intervalNanos();remainder = state.remainderNanos();pending = state.pendingNanos();paused = state.paused();
    }
    public synchronized void start(long now) {
        if (started) throw new IllegalStateException("Game clock already started");
        lastTick = now - pending;started = true;
    }
    public synchronized State capture(long now) {
        requireStarted();return new State(interval, remainder, paused ? pending : elapsed(now), paused);
    }
    /** A delayed frame consumes elapsed time once, dropping missed scheduling slots instead of replaying zero-time ticks. */
    public synchronized OptionalLong poll(long now) {
        requireStarted();if (paused) return OptionalLong.empty();
        long elapsed = elapsed(now);
        if (elapsed < interval - remainder) return OptionalLong.empty();
        long extra = elapsed % interval;
        // Modular addition without overflowing, even for a long interval.
        remainder = remainder >= interval - extra ? remainder - (interval - extra) : remainder + extra;
        lastTick = now;return OptionalLong.of(elapsed);
    }
    public synchronized void pause(long now) {
        if (!paused) {if (started) pending = elapsed(now);paused = true;}
    }
    public synchronized void resume(long now) {
        if (paused) {if (started) lastTick = now - pending;paused = false;}
    }
    public synchronized boolean isPaused() {return paused;}
    public synchronized long sleepNanos(long now) {
        requireStarted();
        long remaining = interval - remainder;
        long elapsed = paused ? pending : elapsed(now);
        return elapsed >= remaining ? 0 : remaining - elapsed;
    }
    public long intervalNanos() {return interval;}
    private long elapsed(long now) {
        long result = now - lastTick;
        if (result < 0) throw new IllegalArgumentException("Game clock moved backwards");
        return result;
    }
    private void requireStarted() {if (!started) throw new IllegalStateException("Game clock has not started");}
}
