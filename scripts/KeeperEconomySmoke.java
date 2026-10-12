/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import com.simsilica.es.base.DefaultEntityData;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.state.session.GameSessionServerService;
import toniarts.openkeeper.game.logic.*;
import toniarts.openkeeper.game.listener.PlayerActionListener;
import toniarts.openkeeper.tools.convert.map.*;
import toniarts.openkeeper.utils.Point;

/** Runs production simulation systems sequentially; no rendering, network transport or background threads. */
class KeeperEconomySmoke {
    public static void main(String[] args) throws Exception {
        String base=Path.of(args[0]).toAbsolutePath()+"/";
        var kwd=KwdFile.load(base,Path.of(base,"Data/editor/maps/TrapTime.kwd"));
        var data=new DefaultEntityData();
        var service=(GameSessionServerService)Proxy.newProxyInstance(GameSessionServerService.class.getClassLoader(),
                new Class<?>[]{GameSessionServerService.class},(p,m,a)->m.getName().equals("getEntityData")
                        ?data:m.getReturnType()==boolean.class?false:null);
        var manager=new GameLoopManager(kwd,service,null);
        var game=manager.getGameController();
        Map<Short,Integer> built=new TreeMap<>();
        game.getGameWorldController().addListener(new PlayerActionListener() {
            public void onSold(short owner,List<Point> tiles) { throw new AssertionError("Unexpected sale"); }
            public void onBuild(short owner,List<Point> tiles) {
                var keeper=game.getPlayerController(owner).getKeeper();
                require(keeper.isAi(),"Human keeper received a construction command");
                var ai=keeper.getPlayer().getAiAttributes();
                var map=game.getGameWorldController().getMapController();
                var room=map.getRoomControllerByCoordinates(tiles.getFirst());
                require(room!=null && room.getOwnerId()==owner,"Room owner mismatch");
                require(keeper.getAvailableRooms().stream().anyMatch(r->r.isDiscovered()&&r.getId()==room.getRoom().getRoomId()),"Undiscovered room built");
                for(Point tile:tiles) require(tile.x>=ai.getBuildAreaStartX() && tile.x<=ai.getBuildAreaEndX()
                        && tile.y>=ai.getBuildAreaStartY() && tile.y<=ai.getBuildAreaEndY(),"Build outside authored bounds");
                built.merge(owner,tiles.size(),Integer::sum);
                System.out.println("BUILD player="+owner+" room="+room.getRoom().getName()+" tiles="+tiles.size()+" at="+tiles.getFirst());
            }
        });
        // Inspect the existing loop composition so the smoke uses the same systems as the app.
        // Invoke children directly: GameLogicManager normally logs exceptions and continues.
        Field loopsField=GameLoopManager.class.getDeclaredField("loops");loopsField.setAccessible(true);
        Field managerField=toniarts.openkeeper.utils.GameLoop.class.getDeclaredField("gameLoopManager");managerField.setAccessible(true);
        Field systemsField=GameLogicManager.class.getDeclaredField("updatables");systemsField.setAccessible(true);
        List<IGameLogicUpdatable[]> loops=new ArrayList<>();
        for(Object loop:(List<?>)loopsField.get(manager)) loops.add((IGameLogicUpdatable[])systemsField.get(managerField.get(loop)));
        long started=System.nanoTime();
        try {
            for(var loop:loops) for(var system:loop) system.start();
            double logicAccumulator=0;
            float logicStep=1f/kwd.getGameLevel().getTicksPerSec();
            for(int frame=0;frame<360*60;frame++) {
                logicAccumulator+=1d/60;
                while(logicAccumulator+1e-8>=logicStep) {
                    tick(loops.getFirst(),logicStep,frame);logicAccumulator-=logicStep;
                }
                for(int i=1;i<loops.size();i++) tick(loops.get(i),1f/60,frame);
            }
            require(built.getOrDefault((short)4,0)>0,"AI player4 did not build within six simulated minutes");
            require(built.getOrDefault((short)3,0)==0,"Human player constructed without input");
            require(game.getPlayerController((short)4).getKeeper().getGoldMined()>0,"AI player4 did not deposit mined gold");
            System.out.println("PASS TrapTime: constructed="+built+"; real mining, excavation, claiming and paid construction; elapsedMs="+(System.nanoTime()-started)/1000000);
        } finally {
            for(var loop:loops) for(var system:loop) system.stop();
            data.close();
        }
        System.out.println("Sequential headless systems only; full strategy, rendering, concurrency, original parity and victory unverified.");
    }
    static void tick(IGameLogicUpdatable[] systems,float dt,int frame) {
        for(var system:systems) try { system.processTick(dt); }
        catch(Exception ex) { throw new RuntimeException("frame="+frame+" system="+system.getClass().getSimpleName(),ex); }
    }
    static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
