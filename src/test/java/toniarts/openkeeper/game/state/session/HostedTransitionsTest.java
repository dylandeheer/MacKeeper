/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

import com.jme3.math.Vector3f;
import com.jme3.network.HostedConnection;
import com.jme3.network.service.rmi.*;
import com.jme3.network.service.rpc.RpcConnection;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.network.game.GameHostedService;
import toniarts.openkeeper.game.state.lobby.ClientInfo;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class HostedTransitionsTest {
    @Test void pendingPlayersRestoreWithoutSendingAndCompleteIndependently() throws Exception {
        var host=host();var a=connect(host,(short)3);var b=connect(host,(short)4);
        host.doTransition((short)7,new Vector3f(),(short)3);host.doTransition((short)8,new Vector3f(),(short)4);
        assertEquals(Set.of((short)3,(short)4),host.getTransitions().capture().pendingPlayers());
        assertEquals(1,a.deliveries.get());assertEquals(1,b.deliveries.get());
        var saved=host.getTransitions().capture();a.session.transitionEnd();assertTrue(host.isInTransition());
        b.session.transitionEnd();assertFalse(host.isInTransition());
        for(int i=0;i<3;i++) {
            host.restoreTransitions(saved);assertTrue(host.isInTransition());
            a.session.transitionEnd();assertEquals(Set.of((short)4),host.getTransitions().capture().pendingPlayers());
            b.session.transitionEnd();assertFalse(host.isInTransition());
        }
        assertEquals(1,a.deliveries.get());assertEquals(1,b.deliveries.get());
        assertThrows(IllegalArgumentException.class,()->host.restoreTransitions(new SessionTransitions.State(Set.of((short)7))));
        assertFalse(host.isInTransition());
    }

    @Test void disconnectFindsItsSessionAndStaleCallsCannotClearReplacement() throws Exception {
        var host=host();var old=connect(host,(short)3);
        host.doTransition((short)1,new Vector3f(),(short)3);assertTrue(host.isInTransition());
        assertNotNull(old.connection.getAttribute("game.session"));
        host.stopHostingOnConnection(old.connection);host.stopHostingOnConnection(old.connection);
        assertNull(old.connection.getAttribute("game.session"));assertFalse(host.isInTransition());
        var replacement=connect(host,(short)3);host.doTransition((short)2,new Vector3f(),(short)3);
        old.session.transitionEnd();((GameSessionListener)old.session).onDoTransition((short)3,new Vector3f());
        host.stopHostingOnConnection(old.connection);
        assertTrue(host.isInTransition());assertEquals(1,old.deliveries.get());assertEquals(1,replacement.deliveries.get());
        replacement.session.transitionEnd();assertFalse(host.isInTransition());
        assertThrows(IllegalArgumentException.class,()->connect(host,(short)3));
    }

    @Test void failedPublicationRollsBackRegistrationAndAllowsRetry() throws Exception {
        var host=host();var live=connect(host,(short)3);host.doTransition((short)1,new Vector3f(),(short)3);
        var connection=connection();var registry=registry(connection);registry.fail=true;install(host,registry);
        var info=info((short)4);
        assertThrows(IllegalStateException.class,()->host.startHostingOnConnection(connection,info));
        assertNull(connection.getAttribute("game.session"));assertEquals(Set.of((short)3),host.getTransitions().capture().pendingPlayers());
        registry.fail=false;host.startHostingOnConnection(connection,info);
        var session=registry.session;host.startHostingOnConnection(connection,info);assertSame(session,registry.session);
        host.doTransition((short)2,new Vector3f(),(short)4);assertEquals(Set.of((short)3,(short)4),host.getTransitions().capture().pendingPlayers());
        host.stopHostingOnConnection(connection);live.session.transitionEnd();assertFalse(host.isInTransition());
    }

    @Test void captureBlocksDisconnectAndLateNetworkAcknowledgement() throws Exception {
        var host=host();var peer=connect(host,(short)3);host.doTransition((short)1,new Vector3f(),(short)3);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var threads=Executors.newFixedThreadPool(3)) {
            try {
                var capture=threads.submit(()->host.getTransitions().gate().snapshot(()->{
                    var saved=host.getTransitions().capture();entered.countDown();SessionTransitionsTest.await(release);
                    assertEquals(saved,host.getTransitions().capture());return saved;
                }));
                SessionTransitionsTest.await(entered);
                var ack=threads.submit(peer.session::transitionEnd);var disconnect=threads.submit(()->host.stopHostingOnConnection(peer.connection));
                assertThrows(TimeoutException.class,()->ack.get(50,TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class,()->disconnect.get(50,TimeUnit.MILLISECONDS));
                release.countDown();assertEquals(Set.of((short)3),capture.get().pendingPlayers());ack.get();disconnect.get();
                assertFalse(host.isInTransition());assertNull(peer.connection.getAttribute("game.session"));
            } finally {release.countDown();}
        }
    }

    record Peer(HostedConnection connection,GameSession session,AtomicInteger deliveries) {}
    static final class Registry extends RmiRegistry {
        final HostedConnection connection;GameSession session;boolean fail;final AtomicInteger deliveries=new AtomicInteger();final GameSessionListener callback;
        Registry(HostedConnection connection) {
            super(new RpcConnection(connection),(short)1,(byte)0);this.connection=connection;
            callback=(GameSessionListener)Proxy.newProxyInstance(GameSessionListener.class.getClassLoader(),new Class[]{GameSessionListener.class},(p,m,a)->{
                if(m.getName().equals("onDoTransition"))deliveries.incrementAndGet();return null;
            });
        }
        @Override public <T> void share(byte channel,T value,Class<? super T> type) {
            if(fail)throw new IllegalStateException("injected publication failure");session=(GameSession)value;
        }
        @Override public <T> T getRemoteObject(Class<T> type) {return type.cast(callback);}
    }
    static GameHostedService host() {return new GameHostedService();}
    static Peer connect(GameHostedService host,short player)throws Exception {
        var connection=connection();var registry=registry(connection);install(host,registry);
        host.startHostingOnConnection(connection,info(player));return new Peer(connection,registry.session,registry.deliveries);
    }
    static Registry registry(HostedConnection connection) {return new Registry(connection);}
    static void install(GameHostedService host,Registry registry)throws Exception {
        var field=GameHostedService.class.getDeclaredField("rmiService");field.setAccessible(true);
        if(field.get(host)==null)field.set(host,new Transport());
        ((Transport)field.get(host)).registries.put(registry.connection,registry);
    }
    static final class Transport extends RmiHostedService {
        final Map<HostedConnection,Registry> registries=new ConcurrentHashMap<>();
        @Override public RmiRegistry getRmiRegistry(HostedConnection connection){return registries.get(connection);}
    }

    static ClientInfo info(short player) {var info=new ClientInfo(0,"test",player);info.setKeeper(new Keeper(false,player));return info;}
    static HostedConnection connection() {
        var attributes=new ConcurrentHashMap<String,Object>();
        return (HostedConnection)Proxy.newProxyInstance(HostedConnection.class.getClassLoader(),new Class[]{HostedConnection.class},(p,m,a)->switch(m.getName()) {
            case "getAttribute"->attributes.get(a[0]);
            case "setAttribute"->a[1]==null?attributes.remove(a[0]):attributes.put((String)a[0],a[1]);
            case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];case "toString"->"TestConnection";
            case "attributeNames"->attributes.keySet();case "getId"->1;case "getAddress"->"test";
            default->null;
        });
    }
}
