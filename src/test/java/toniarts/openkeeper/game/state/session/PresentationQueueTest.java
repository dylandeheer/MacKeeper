/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

import com.jme3.math.Vector3f;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.save.PresentationSnapshots;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.game.state.session.PresentationEvent.*;

@Timeout(10)
class PresentationQueueTest {
    @Test void callbackPayloadsAreDetachedAtReceiptAndEveryDeliveryGetsFreshMutableValues() throws Exception {
        var queue = new PresentationQueue();
        var listener = new QueuedGameSessionListener(queue, target((name, args) -> fail(name)));
        var point = new Vector3f(1, 2, 3);
        var tiles = new ArrayList<>(List.of(new Point(4, 5)));
        var spell = new PlayerSpell((short) 7, true);spell.setResearch(52);spell.setUpgraded(true);
        listener.onZoomViewToPoint(point);listener.onTilesChange(tiles);listener.onResearchStatusChanged((short) 3, spell);
        point.set(99, 99, 99);tiles.clear();spell.setResearch(0);spell.setDiscovered(false);
        var saved = queue.capture();
        assertEquals(new ZoomPoint(new Vec3(1, 2, 3)), saved.events().get(0));
        assertEquals(new ChangedTiles(List.of(new Point(4, 5))), saved.events().get(1));
        assertEquals(new ResearchUpdate(ResearchAction.CHANGED, (short) 3,
                new KeeperState.Research((short) 7, ResearchableType.SPELL, true, true, 52, true)), saved.events().get(2));
        byte[] before = PresentationSnapshots.encode(saved);
        var receiver = target((name, args) -> {
            switch (name) {
                case "onZoomViewToPoint" -> ((Vector3f) args[0]).set(10, 20, 30);
                case "onTilesChange" -> assertThrows(UnsupportedOperationException.class, () -> ((List<?>) args[0]).clear());
                case "onResearchStatusChanged" -> ((ResearchableEntity) args[1]).setResearch(400);
                default -> fail(name);
            }
        });
        queue.drain(() -> true, event -> event.deliver(receiver));
        assertArrayEquals(before, PresentationSnapshots.encode(saved));
        queue.restore(saved);queue.drain(() -> true, event -> event.deliver(receiver));
        assertArrayEquals(before, PresentationSnapshots.encode(saved));
    }

    @Test void readinessPreservesFifoAndReentrantProducersWaitForNextFrame() {
        var queue = new PresentationQueue();queue.add(new Message(1));queue.add(new Message(2));
        var seen = new ArrayList<PresentationEvent>();
        queue.drain(() -> false, seen::add);assertTrue(seen.isEmpty());
        queue.drain(() -> true, event -> {seen.add(event);queue.add(new Message(3));});
        assertEquals(List.of(new Message(1), new Message(2)), seen);
        assertEquals(List.of(new Message(3), new Message(3)), queue.capture().events());
        queue.drain(() -> seen.size() < 3, seen::add);
        assertEquals(List.of(new Message(3)), queue.capture().events());
        assertEquals(3, seen.size());
    }

    @Test void captureAndReplacementRejectInFlightDispatchButConcurrentProducerNeverWaitsForCallback() throws Exception {
        var queue = new PresentationQueue();queue.add(new Message(1));
        var entered = new CountDownLatch(1);var release = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var drain = threads.submit(() -> queue.drain(() -> true, event -> {
                assertFalse(Thread.holdsLock(queue));entered.countDown();SessionTransitionsTest.await(release);
            }));
            SessionTransitionsTest.await(entered);
            try {
                assertThrows(IllegalStateException.class, queue::capture);
                assertThrows(IllegalStateException.class, () -> queue.restore(new PresentationQueue.State(List.of())));
                threads.submit(() -> queue.add(new Message(2))).get(1, TimeUnit.SECONDS);
                assertThrows(IllegalStateException.class, () -> queue.drain(() -> true, event -> fail()));
            } finally {release.countDown();}
            drain.get();
        }
        assertEquals(List.of(new Message(2)), queue.capture().events());
    }

    @Test void closeDiscardsBacklogAndLateCallbacksWhileFailedDispatchIsAttemptedOnlyOnce() {
        var queue = new PresentationQueue();queue.add(new Message(1));queue.add(new Message(2));
        assertThrows(IllegalStateException.class, () -> queue.drain(() -> true, event -> {throw new IllegalStateException("receiver failed after possible side effects");}));
        assertEquals(List.of(new Message(2)), queue.capture().events());
        assertThrows(NullPointerException.class, () -> queue.restore(null));
        assertEquals(List.of(new Message(2)), queue.capture().events());
        queue.drain(() -> true, event -> {queue.close();queue.add(new Message(3));});
        queue.add(new Message(4));queue.drain(() -> true, event -> fail("Closed owner dispatched"));
        assertThrows(IllegalStateException.class, queue::capture);
        assertThrows(IllegalStateException.class, () -> queue.restore(new PresentationQueue.State(List.of())));
    }

    @Test void lifecyclePassesThroughWhileRuntimeNotificationsWaitForRenderConsumer() {
        var queue = new PresentationQueue();var calls = new ArrayList<String>();
        var listener = new QueuedGameSessionListener(queue, target((name, args) -> calls.add(name)));
        listener.onGameDataLoaded(List.of());listener.onLoadComplete((short) 3);
        listener.onLoadStatusUpdate(.25f, (short) 3);listener.onGameStarted();listener.onLoadFailed("preparation failed");
        listener.onGoldChange((short) 3, 7_000_000_000L, 100);listener.onGamePaused();listener.onGameResumed();
        assertEquals(List.of("onGameDataLoaded", "onLoadComplete", "onLoadStatusUpdate", "onGameStarted", "onLoadFailed"), calls);
        assertEquals(List.of(new Gold((short) 3, 7_000_000_000L, 100), new Paused(), new Resumed()), queue.capture().events());
    }

    @Test void pendingTransitionCanBeCapturedWithItsServerFlagAndAcknowledgedOnlyAfterRender() throws Exception {
        var session = new LocalGameSession();var queue = new PresentationQueue();
        session.addGameSessionListener(new QueuedGameSessionListener(queue, target((name, args) -> fail(name))));
        session.doTransition((short) 12, new Vector3f(2, 3, 4), (short) 3);
        var saved = session.getTransitions().gate().snapshot(() -> {
            assertTrue(session.isInTransition());return queue.capture();
        });
        queue.restore(PresentationSnapshots.decode(PresentationSnapshots.encode(saved)));
        assertTrue(session.isInTransition());
        queue.drain(() -> true, event -> event.deliver(target((name, args) -> {
            assertEquals("onDoTransition", name);assertEquals((short) 12, args[0]);assertEquals(new Vector3f(2, 3, 4), args[1]);
            session.transitionEnd();
        })));
        assertFalse(session.isInTransition());assertTrue(queue.capture().events().isEmpty());
        session.getEntityData().close();
    }

    interface Call {void accept(String name, Object[] args);}
    static Target target(Call action) {
        return (Target) Proxy.newProxyInstance(Target.class.getClassLoader(), new Class[]{Target.class}, (proxy, method, args) -> {
            if (method.getName().equals("equals")) return proxy == args[0];
            action.accept(method.getName(), args);return null;
        });
    }
}
