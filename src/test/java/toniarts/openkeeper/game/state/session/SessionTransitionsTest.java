/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

import com.jme3.math.Vector3f;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import toniarts.openkeeper.game.save.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SessionTransitionsTest {
    @Test void localPendingRequestSurvivesRepeatedDetachedLoadsWithoutCallbackReplay() throws Exception {
        var session=new LocalGameSession();var calls=new AtomicInteger();
        session.addGameSessionListener(listener((name,args)->{if(name.equals("onDoTransition"))calls.incrementAndGet();}));
        session.doTransition((short)7,new Vector3f(1,2,3),(short)4);assertFalse(session.isInTransition());
        session.doTransition((short)7,new Vector3f(1,2,3),(short)3);assertTrue(session.isInTransition());assertEquals(1,calls.get());
        var saved=session.getTransitions().gate().snapshot(()->session.getTransitions().capture());
        assertEquals(Set.of((short)3),saved.pendingPlayers());
        for(int i=0;i<5;i++) {
            var data=new GameEntityData();var restored=new LocalGameSession(data);
            assertSame(data,restored.getEntityData());
            restored.addGameSessionListener(listener((name,args)->fail("Restore replayed "+name)));
            restored.restoreTransitions(TransitionSnapshots.decode(TransitionSnapshots.encode(saved)));
            assertTrue(restored.isInTransition());assertEquals(saved,restored.getTransitions().capture());
            restored.transitionEnd();assertFalse(restored.isInTransition());
            assertTrue(session.isInTransition());assertEquals(Set.of((short)3),saved.pendingPlayers());data.close();
        }
        session.transitionEnd();session.transitionEnd();assertFalse(session.isInTransition());
    }

    @Test void transitionDeliveryAndAcknowledgementAreExcludedByWorldCapture() throws Exception {
        var local=new LocalGameSession();var owner=local.getTransitions();var delivered=new AtomicInteger();
        local.addGameSessionListener(listener((name,args)->{if(name.equals("onDoTransition"))delivered.incrementAndGet();}));
        try(var threads=Executors.newFixedThreadPool(2)) {
            for(boolean acknowledge:new boolean[]{false,true}) {
                var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
                var capture=threads.submit(()->owner.gate().snapshot(()->{
                    var before=owner.capture();entered.countDown();await(release);assertEquals(before,owner.capture());return before;
                }));
                await(entered);
                try {
                    var command=threads.submit(()->{if(acknowledge)local.transitionEnd();else local.doTransition((short)1,new Vector3f(),(short)3);});
                    assertThrows(TimeoutException.class,()->command.get(50,TimeUnit.MILLISECONDS));
                    assertEquals(acknowledge?1:0,delivered.get());
                    release.countDown();capture.get();command.get();assertEquals(!acknowledge,local.isInTransition());
                } finally {release.countDown();}
            }
        }
    }

    @Test void snapshotWaitsForInFlightDeliveryAndReentrantAcknowledgementWorks() throws Exception {
        var local=new LocalGameSession();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        local.addGameSessionListener(listener((name,args)->{
            if(name.equals("onDoTransition")){assertTrue(local.isInTransition());entered.countDown();await(release);local.transitionEnd();}
        }));
        try(var threads=Executors.newFixedThreadPool(2)) {
            try {
                var request=threads.submit(()->local.doTransition((short)2,new Vector3f(),(short)3));await(entered);
                var capture=threads.submit(()->local.getTransitions().gate().snapshot(()->local.getTransitions().capture()));
                assertThrows(TimeoutException.class,()->capture.get(50,TimeUnit.MILLISECONDS));
                release.countDown();request.get();assertEquals(Set.of(),capture.get().pendingPlayers());
            } finally {release.countDown();}
        }
    }

    @Test void missingRecipientAndMalformedSectionsFailWithoutChangingCurrentState() throws Exception {
        var local=new LocalGameSession();local.doTransition((short)1,new Vector3f(),(short)3);
        var state=local.getTransitions().capture();
        assertThrows(IllegalArgumentException.class,()->local.restoreTransitions(new SessionTransitions.State(Set.of((short)4))));
        assertEquals(state,local.getTransitions().capture());
        var bytes=TransitionSnapshots.encode(new SessionTransitions.State(Set.of((short)3,(short)4)));
        for(int i=0;i<bytes.length;i++){var truncated=Arrays.copyOf(bytes,i);assertThrows(java.io.IOException.class,()->TransitionSnapshots.decode(truncated));}
        var duplicate=bytes.clone();duplicate[15]=3;assertThrows(java.io.IOException.class,()->TransitionSnapshots.decode(duplicate));
        assertThrows(java.io.IOException.class,()->TransitionSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
        for(int offset:new int[]{0,4,8}) {
            var corrupt=bytes.clone();java.nio.ByteBuffer.wrap(corrupt).putInt(offset,Integer.MAX_VALUE);
            assertThrows(java.io.IOException.class,()->TransitionSnapshots.decode(corrupt));
        }
        var invalid=bytes.clone();invalid[12]=-1;assertThrows(java.io.IOException.class,()->TransitionSnapshots.decode(invalid));
        assertThrows(IllegalArgumentException.class,()->new SessionTransitions.State(Set.of((short)256)));
    }

    @Test void failedDeliveryRetainsThePendingFlagAndOwnerStateIsImmutable() {
        var owner=new SessionTransitions();var failure=new IllegalStateException("delivery");
        assertSame(failure,assertThrows(IllegalStateException.class,()->owner.begin((short)3,()->{throw failure;})));
        var saved=owner.capture();assertThrows(UnsupportedOperationException.class,()->saved.pendingPlayers().clear());
        owner.begin((short)4,()->{});owner.disconnect((short)3);assertTrue(owner.isInTransition());
        owner.complete((short)4,()->{});assertFalse(owner.isInTransition());assertEquals(Set.of((short)3),saved.pendingPlayers());
    }

    private interface Event {void accept(String name,Object[] args);}
    private static GameSessionListener listener(Event event) {
        return (GameSessionListener)Proxy.newProxyInstance(GameSessionListener.class.getClassLoader(),new Class[]{GameSessionListener.class},(proxy,method,args)->{
            if(method.getName().equals("equals"))return proxy==args[0];event.accept(method.getName(),args);return null;
        });
    }
    static void await(CountDownLatch latch) {try{assertTrue(latch.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}
}
