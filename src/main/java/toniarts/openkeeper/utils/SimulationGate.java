/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.utils;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Coordinates simulation updates with an exclusive, in-memory snapshot.
 * Updates retain their existing concurrency. A snapshot waits for all updates,
 * then prevents new updates until the capture is finished. File I/O belongs
 * outside this boundary. Snapshot callbacks must return detached data.
 */
public final class SimulationGate {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private long suspendedNanos;
    private long captureStarted;

    public void mutate(Runnable action) {
        Objects.requireNonNull(action);
        enterUpdate();
        try {
            action.run();
        } finally {
            leaveUpdate();
        }
    }

    public <T> T snapshot(Supplier<T> capture) {
        Objects.requireNonNull(capture);
        if (lock.getReadHoldCount() != 0 || lock.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException("Snapshot capture must start outside simulation updates and other captures");
        }
        lock.writeLock().lock();
        long started = System.nanoTime();
        captureStarted = started;
        try {
            return capture.get();
        } finally {
            suspendedNanos += System.nanoTime() - started;
            lock.writeLock().unlock();
        }
    }

    void enterUpdate() {
        if (lock.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException("Snapshot capture cannot issue simulation commands");
        }
        lock.readLock().lock();
    }

    void leaveUpdate() {
        lock.readLock().unlock();
    }

    /** One common logical timestamp for every loop captured inside the exclusive callback. */
    long snapshotTime() {
        if (!lock.isWriteLockedByCurrentThread()) throw new IllegalStateException("Loop phase capture requires the snapshot boundary");
        return captureStarted - suspendedNanos;
    }

    /** Read while holding an update lock; callers subtract this from wall time. */
    long suspendedNanos() {
        return suspendedNanos;
    }
}
