/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.nio.file.Path;
import java.util.*;
import java.lang.reflect.*;
import com.simsilica.es.base.DefaultEntityData;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.state.session.GameSessionServerService;
import toniarts.openkeeper.game.logic.*;
import toniarts.openkeeper.tools.convert.map.KwdFile;

/** Repeatable Level 10 headless workload. Requires locally owned game data. */
class NavigationBenchmark {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: NavigationBenchmark.java <owned-data-directory>");
        String base=Path.of(args[0]).toAbsolutePath()+"/";
        var kwd=KwdFile.load(base,Path.of(base,"Data/editor/maps/Level10.kwd"));
        var data=new DefaultEntityData();
        var service=(GameSessionServerService)Proxy.newProxyInstance(GameSessionServerService.class.getClassLoader(),
                new Class<?>[]{GameSessionServerService.class},(p,m,a)->m.getName().equals("getEntityData")
                        ?data:m.getReturnType()==boolean.class?false:null);
        var manager=new GameLoopManager(kwd,service,null,new toniarts.openkeeper.game.random.SimulationRandom(0));
        Field loopsField=GameLoopManager.class.getDeclaredField("loops");loopsField.setAccessible(true);
        Field managerField=toniarts.openkeeper.utils.GameLoop.class.getDeclaredField("gameLoopManager");managerField.setAccessible(true);
        Field systemsField=GameLogicManager.class.getDeclaredField("updatables");systemsField.setAccessible(true);
        List<IGameLogicUpdatable[]> loops=new ArrayList<>();
        for(Object loop:(List<?>)loopsField.get(manager)) loops.add((IGameLogicUpdatable[])systemsField.get(managerField.get(loop)));
        try {
            for(var loop:loops) for(var system:loop) system.start();
            long started=System.nanoTime();
            double logicAccumulator=0;
            float logicStep=1f/kwd.getGameLevel().getTicksPerSec();
            for(int frame=0;frame<120*60;frame++) {
                logicAccumulator+=1d/60;
                while(logicAccumulator+1e-8>=logicStep) {
                    tick(loops.getFirst(),logicStep,frame);logicAccumulator-=logicStep;
                }
                for(int i=1;i<loops.size();i++) tick(loops.get(i),1f/60,frame);
            }
            long elapsed=(System.nanoTime()-started)/1_000_000;
            var player=manager.getGameController().getPlayerController((short)4);
            System.out.println("Level10 seed=0 simulatedSeconds=120 elapsedMs="+elapsed
                    +" keeperGold="+player.getKeeper().getGold()+" creatures="+player.getCreatureControl().getTypeCount()
                    +" imps="+player.getCreatureControl().getImpCount());
        } finally {
            for(var loop:loops) for(var system:loop) system.stop();
            data.close();
        }
        System.out.println("Sequential simulation only; excludes compilation/loading and rendering; not a frame-rate or mission-completion benchmark.");
    }

    static void tick(IGameLogicUpdatable[] systems,float dt,int frame) {
        for(var system:systems) try { system.processTick(dt); }
        catch(Exception ex) { throw new RuntimeException("frame="+frame+" system="+system.getClass().getSimpleName(),ex); }
    }
}
