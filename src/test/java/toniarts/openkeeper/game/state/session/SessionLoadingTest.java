/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.state.session;

import com.jme3.app.state.AppStateManager;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import toniarts.openkeeper.Main;
import toniarts.openkeeper.game.state.GameServerState;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SessionLoadingTest {
    @Test void disabledCommandsCannotMutatePreparedWorldButLifecycleCanCancelIt() {
        var service = new LocalGameSession();var commands = new ArrayList<String>();
        service.addGameSessionServiceListener((GameSessionServiceListener) Proxy.newProxyInstance(
                GameSessionServiceListener.class.getClassLoader(), new Class[]{GameSessionServiceListener.class},
                (proxy, method, args) -> {if(method.getName().equals("equals")) return proxy == args[0];commands.add(method.getName());return null;}));
        try {
            service.setCommandAdmission(false);
            service.build(null,null,(short)1);service.sell(null,null);service.selectTiles(null,null,true);
            service.interact(null);service.pickUp(null);service.drop(null,null,null,null);service.getGold(100);
            service.pauseGame();service.resumeGame();service.triggerCheat(null);service.castKeeperSpell((short)1,null,null,null);
            service.placeDoor((short)1,null);service.placeTrap((short)1,null);
            assertTrue(commands.isEmpty());
            service.exitGame();assertEquals(List.of("onExitGame"),commands);
            service.setCommandAdmission(true);service.pauseGame();service.getGold(100);
            assertEquals(List.of("onExitGame","onPauseRequest","onGetGold"),commands);
        } finally {service.getEntityData().close();}
    }

    @Test void unattachedAndCancelledServerNeverStartsPreparingOrRemainsRegistered() throws Exception {
        var service = new LocalGameSession();
        IKwdFile unavailable = definition(() -> {throw new AssertionError("Cancelled server read a definition");});
        var server = new GameServerState(unavailable,List.of(),false,service,null);
        var app=app();var states=new AppStateManager(app);
        assertFalse(server.loadingCompletion().toCompletableFuture().isDone());
        states.attach(server);server.detach();server.detach();
        assertNull(states.getState(GameServerState.class));
        assertTrue(server.loadingCompletion().toCompletableFuture().isCompletedExceptionally());
        service.startGame();service.getGold(1);service.exitGame();
        states.update(0);assertFalse(server.isInitialized());
        service.getEntityData().close();
    }

    @Test void failedServerPreparationSignalsClientAndCompletesExceptionallyInsteadOfWaitingForStart() throws Exception {
        var service=new LocalGameSession();var failed=new CompletableFuture<String>();
        service.addGameSessionListener(PresentationQueueTest.target((name,args)->{
            if(name.equals("onLoadFailed"))failed.complete((String)args[0]);
            else if(!name.equals("onGameStarted")) fail("Unexpected lifecycle "+name);
        }));
        var expected=new IllegalStateException("Cannot prepare test world");
        var server=new GameServerState(definition(()->{throw expected;}),List.of(),false,service,null);
        var app=app();var states=new AppStateManager(app);states.attach(server);
        try {
            states.update(0);
            assertEquals(expected.getMessage(),failed.get(2,TimeUnit.SECONDS));
            var failure=assertThrows(CompletionException.class,()->server.loadingCompletion().toCompletableFuture().join());
            assertSame(expected,failure.getCause());
            service.startGame();service.getGold(1); // Failed owner never admits commands.
        } finally {server.detach();states.update(0);service.getEntityData().close();}
    }

    private static IKwdFile definition(Runnable access) {
        return (IKwdFile)Proxy.newProxyInstance(IKwdFile.class.getClassLoader(),new Class[]{IKwdFile.class},
                (proxy,method,args)->{access.run();return null;});
    }
    private static Main app() throws Exception {
        var constructor=Main.class.getDeclaredConstructor();constructor.setAccessible(true);return constructor.newInstance();
    }
}
