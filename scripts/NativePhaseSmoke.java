/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import toniarts.openkeeper.game.random.SimulationRandom;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.state.session.*;
import toniarts.openkeeper.tools.convert.map.KwdFile;

/** Live native server loop restoration; no rendered-client or mission-completion claim. */
class NativePhaseSmoke {
    public static void main(String[] args)throws Exception {
        var failure=new AtomicReference<Throwable>();var previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{failure.compareAndSet(null,error);error.printStackTrace();});
        var handler=new Handler(){
            public void publish(LogRecord record){if(record.getThrown()!=null)failure.compareAndSet(null,record.getThrown());}
            public void flush(){}public void close(){}
        };
        Logger.getLogger("").addHandler(handler);
        try {
            for(String name:List.of("Level1","TrapTime","Level17","Level10")) {
                var definition=KwdFile.load(Path.of(args[0]).toAbsolutePath().toString(),name);
                var callbacks=new AtomicInteger();var data=new GameEntityData();
                var session=service(data,callbacks);
                var manager=new GameLoopManager(definition,session,null,new SimulationRandom(731));
                try {
                    manager.start();ready(manager);Thread.sleep(650);
                    for(int iteration=0;iteration<3;iteration++) {
                        manager.pause();
                        var receiver=session;
                        manager.runPlayerCommand(()->receiver.doTransition((short)1,new com.jme3.math.Vector3f(),(short)3));
                        var saved=SimulationState.capture(manager);
                        if(!saved.transitions().pendingPlayers().equals(Set.of((short)3)))throw new AssertionError("Missing pending cinematic");
                        if(saved.loops().stream().anyMatch(phase->!phase.paused()))throw new AssertionError("Live pause not captured");
                        if(saved.loops().stream().allMatch(phase->phase.pendingNanos()==0))throw new AssertionError("No fractional phase exercised");
                        var archive=WorldContinuationSmoke.diskRoundTrip(definition,saved);
                        var rejected=new GameEntityData[1];
                        try {
                            archive.restore(definition,store->{rejected[0]=store;return WorldContinuationSmoke.service(store);});
                            throw new AssertionError("Pending transition restored without receiving session");
                        } catch(IllegalArgumentException expected) {}
                        if(!rejected[0].capture().entities().isEmpty())throw new AssertionError("Rejected store leaked");
                        callbacks.set(0);var receiving=new LocalGameSession[1];
                        var next=archive.restore(definition,store->{receiving[0]=service(store,callbacks);return receiving[0];});
                        try {
                            // Neither staging delay nor paused native worker time may advance restored state.
                            Thread.sleep(100);next.start();ready(next);Thread.sleep(100);
                            WorldContinuationSmoke.equal(saved,SimulationState.capture(next),name+" native paused restore="+iteration);
                            if(callbacks.get()!=0)throw new AssertionError("Restoration replayed client events");
                            try(var thread=Executors.newSingleThreadExecutor()) {
                                var ack=next.captureSnapshot(game->{
                                    var pending=thread.submit(receiving[0]::transitionEnd);
                                    try {pending.get(50,TimeUnit.MILLISECONDS);throw new AssertionError("Acknowledgement bypassed world capture");}
                                    catch(TimeoutException expected) {}catch(Exception error){throw new AssertionError(error);}
                                    if(!receiving[0].isInTransition())throw new AssertionError("Pending flag cleared inside capture");
                                    return pending;
                                });
                                ack.get();
                            }
                            if(receiving[0].isInTransition())throw new AssertionError("Acknowledgement was lost");
                            if(!session.isInTransition())throw new AssertionError("Restored acknowledgement mutated original session");
                        } catch(Exception|Error error){next.stop();next.getEntityData().close();throw error;}
                        manager.stop();manager.getEntityData().close();manager=next;session=receiving[0];
                        double before=saved.level().clock().elapsedSeconds();
                        manager.resume();Thread.sleep(300);
                        double after=manager.captureSnapshot(game->game.getGameTimer().getGameTime());
                        if(!(after>before&&after-before<1))throw new AssertionError("Native resume stalled or included loading delay: "+(after-before));
                        if(failure.get()!=null)throw new AssertionError("Native simulation failed",failure.get());
                    }
                    System.out.println("PASS "+name+" 3 native paused archive restores, exact phase/server state, pending cinematic/ack boundary, no callback replay, resumed clocks advance");
                } finally {manager.stop();manager.getEntityData().close();}
            }
        } finally {Logger.getLogger("").removeHandler(handler);Thread.setDefaultUncaughtExceptionHandler(previous);}
        if(failure.get()!=null)throw new AssertionError("Native simulation failed",failure.get());
        System.out.println("Native server phase evidence only; concurrent scheduling equality, rendered clients and mission completion are not established.");
    }
    static void ready(GameLoopManager manager)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        for(;;) {
            try{manager.captureSnapshot(game->null);return;}
            catch(IllegalStateException unready){if(System.nanoTime()>deadline)throw unready;Thread.sleep(5);}
        }
    }
    static LocalGameSession service(GameEntityData data,AtomicInteger callbacks) {
        var local=new LocalGameSession(data);
        local.addGameSessionListener((GameSessionListener)Proxy.newProxyInstance(GameSessionListener.class.getClassLoader(),new Class[]{GameSessionListener.class},(proxy,method,args)->{
            if(method.getName().equals("equals"))return proxy==args[0];
            callbacks.incrementAndGet();return null;
        }));
        return local;
    }
}
