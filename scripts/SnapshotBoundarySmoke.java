/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.simsilica.es.EntityId;
import com.simsilica.es.base.DefaultEntityData;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.IGameController;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.state.session.GameSessionServerService;
import toniarts.openkeeper.tools.convert.map.KwdFile;
import toniarts.openkeeper.utils.GameLoop;
import toniarts.openkeeper.utils.SimulationGate;
import toniarts.openkeeper.game.logic.IGameLogicUpdatable;

/** Live production loop boundary probe. This does not write or restore a saved game. */
class SnapshotBoundarySmoke {
    public static void main(String[] args) throws Exception {
        String base=Path.of(args[0]).toAbsolutePath().normalize()+"/";
        var kwd=KwdFile.load(base,Path.of(base,"Data/editor/maps/level1.kwd"));
        var data=new DefaultEntityData();
        var service=(GameSessionServerService)Proxy.newProxyInstance(GameSessionServerService.class.getClassLoader(),
                new Class<?>[]{GameSessionServerService.class},(p,m,a)->m.getName().equals("getEntityData")?data:m.getReturnType()==boolean.class?false:null);
        var manager=new GameLoopManager(kwd,service,null);
        System.out.println("Level1 logic ticks per second="+kwd.getGameLevel().getTicksPerSec());
        try {
            expectNotReady(manager);
            manager.start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            for(;;) {
                try{manager.captureSnapshot(game->game.getGameTimer().getGameTime());break;}
                catch(IllegalStateException e){if(System.nanoTime()>deadline)throw e;Thread.sleep(10);}
            }
            Thread.sleep(500);
            for(int i=0;i<10;i++) {
                int iteration=i;
                manager.captureSnapshot(game->{
                    String before=digest(game,data);
                    sleep(100);
                    require(before.equals(digest(game,data)),"State changed during capture "+iteration);
                    return before; // Detached string, no live state escapes.
                });
                Thread.sleep(50);
            }
            double before=manager.captureSnapshot(game->game.getGameTimer().getGameTime());
            manager.captureSnapshot(game->{sleep(1000);return null;});
            double after=before;
            long nextTickDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(after==before&&System.nanoTime()<nextTickDeadline) {
                Thread.sleep(10);
                after=manager.captureSnapshot(game->game.getGameTimer().getGameTime());
            }
            double expectedStep=1d/kwd.getGameLevel().getTicksPerSec();
            require(after>before&&after-before<expectedStep+.5,"Capture advanced simulation time: "+(after-before));
            manager.pause();Thread.sleep(100);
            manager.captureSnapshot(game->{String frozen=digest(game,data);sleep(100);require(frozen.equals(digest(game,data)),"Paused capture mutated");return null;});
            manager.resume();
            try(var executor=Executors.newSingleThreadExecutor()) {
                var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
                Future<?> holding=executor.submit(()->manager.captureSnapshot(game->{entered.countDown();await(release);return null;}));
                await(entered);
                try(var commands=Executors.newSingleThreadExecutor()) {
                    try {
                        Future<?> command=commands.submit(()->manager.runPlayerCommand(()->manager.getGameController().getLevelInfo().setFlag(127,42)));
                        try{command.get(50,TimeUnit.MILLISECONDS);throw new AssertionError("Command bypassed snapshot boundary");}catch(TimeoutException expected){}
                        release.countDown();holding.get();command.get();
                        require(manager.captureSnapshot(game->game.getLevelInfo().getFlag(127))==42,"Command lost");
                    } finally {release.countDown();}
                }
            }
            System.out.println("PASS Level1 actual Logic/Animation/Steering loops: ten stable captures, capture clock exclusion, pause/resume and blocked player command.");
            verifyShutdown(manager);
        } finally {manager.stop();data.close();}
        expectNotReady(manager);
        System.out.println("PASS rejected before-start/after-stop capture; no save file or restore acceptance claimed.");
    }
    @SuppressWarnings("unchecked")
    static void verifyShutdown(GameLoopManager manager) throws Exception {
        // Append a delayed worker to exercise joins outside the gate. It has no
        // game state; the three production loops still stop through the manager.
        Field gateField=GameLoopManager.class.getDeclaredField("simulationGate");gateField.setAccessible(true);
        Field loopsField=GameLoopManager.class.getDeclaredField("loops");loopsField.setAccessible(true);
        Field threadField=GameLoop.class.getDeclaredField("loop");threadField.setAccessible(true);
        var cleaned=new AtomicBoolean();var preserved=new AtomicBoolean();
        var delayed=new GameLoop(new IGameLogicUpdatable(){
            public void start(){}public void processTick(float tpf){}public void stop(){cleaned.set(true);}
        },TimeUnit.SECONDS.toNanos(2),"DelayedShutdownProbe",(SimulationGate)gateField.get(manager));
        manager.captureSnapshot(game->{
            try{((List<GameLoop>)loopsField.get(manager)).add(delayed);}catch(IllegalAccessException e){throw new AssertionError(e);}
            return null;
        });
        delayed.start();var thread=(Thread)threadField.get(delayed);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(thread.getState()!=Thread.State.TIMED_WAITING) {
            require(System.nanoTime()<deadline,"Delayed worker never reached sleep");Thread.sleep(1);
        }
        try(var executor=Executors.newFixedThreadPool(2)) {
            Future<?> first=executor.submit(()->{
                Thread.currentThread().interrupt();manager.stop();
                preserved.set(Thread.currentThread().isInterrupted());Thread.interrupted();
            });
            for(;;) {
                try{manager.runPlayerCommand(()->{});}catch(IllegalStateException closed){break;}
                require(System.nanoTime()<deadline,"Shutdown did not close admission");Thread.sleep(1);
            }
            Future<?> second=executor.submit(manager::stop);
            for(Future<?> closing:List.of(first,second)) {
                try{closing.get(50,TimeUnit.MILLISECONDS);throw new AssertionError("Stop returned before delayed worker cleanup");}
                catch(TimeoutException expected){}
            }
            first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);
            require(cleaned.get()&&preserved.get(),"Shutdown lost cleanup or interrupt status");
            require(!thread.isAlive(),"Worker still alive after stop");
        }
        System.out.println("PASS interrupted and concurrent manager.stop callers await all worker cleanup; interrupt preserved.");
    }
    static String digest(IGameController game,DefaultEntityData data) {
        var b=new StringBuilder().append(game.getGameTimer().getGameTime());
        var info=game.getLevelInfo();for(int i=0;i<128;i++)b.append('|').append(info.getFlag(i));
        for(int i=0;i<16;i++)b.append('|').append(info.getTimer(i).getTime()).append(':').append(info.getTimer(i).isActive());
        info.getPlayers().values().forEach(p->b.append('|').append(p.getId()).append(':').append(p.getGold()).append(':').append(p.getMana()).append(':').append(p.getCreaturesLost()));
        var ids=new TreeSet<EntityId>();ids.addAll(data.findEntities(null,MapTile.class));ids.addAll(data.findEntities(null,Position.class));ids.addAll(data.findEntities(null,Health.class));
        for(EntityId id:ids) {
            b.append('|').append(id.getId());
            var tile=data.getComponent(id,MapTile.class);if(tile!=null)b.append('m').append(tile.terrainId).append(':').append(tile.room).append(':').append(tile.selection);
            var pos=data.getComponent(id,Position.class);if(pos!=null)b.append('p').append(pos.rotation).append(':').append(pos.position);
            var health=data.getComponent(id,Health.class);if(health!=null)b.append('h').append(health.health).append(':').append(health.maxHealth);
            var owner=data.getComponent(id,Owner.class);if(owner!=null)b.append('o').append(owner.ownerId).append(':').append(owner.controlId);
            var ai=data.getComponent(id,CreatureAi.class);if(ai!=null)b.append('a').append(ai.stateStartTime).append(':').append(ai.getCreatureState());
        }
        return b.toString();
    }
    static void expectNotReady(GameLoopManager manager){try{manager.captureSnapshot(game->1);throw new AssertionError("Captured unready game");}catch(IllegalStateException expected){}}
    static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    static void await(CountDownLatch latch){try{require(latch.await(5,TimeUnit.SECONDS),"Latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
