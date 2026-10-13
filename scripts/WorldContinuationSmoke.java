/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.logic.*;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.random.SimulationRandom;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.state.session.GameSessionServerService;
import toniarts.openkeeper.tools.convert.map.KwdFile;
import toniarts.openkeeper.utils.GameLoop;

/** Actual server worlds with matched sequential loop input; excludes native scheduling/client UI. */
class WorldContinuationSmoke {
    public static void main(String[] args) throws Exception {
        String base=Path.of(args[0]).toAbsolutePath()+"/";
        for(String name:args.length>1?List.of(args[1]):List.of("Level1","TrapTime","Level17","Level10")) {
            var kwd=KwdFile.load(Path.of(base).toString(),name);
            var data=new GameEntityData();var service=service(data);
            try(var original=new Driver(new GameLoopManager(kwd,service,null,new SimulationRandom(731)))) {
                float logicStep=1f/kwd.getGameLevel().getTicksPerSec();
                for(int i=0;i<12;i++)original.tick(logicStep);
                var saved=SimulationState.capture(original.manager);
                rejectsIsolatedFailures(kwd,saved,original,service);
                original.events.clear();
                var restored=new Driver(diskRoundTrip(kwd,saved).restore(kwd,WorldContinuationSmoke::service));
                try {
                    equal(saved,SimulationState.capture(restored.manager),name+" initial");
                    if(!restored.events.isEmpty())throw new AssertionError("Restore replayed client events: "+restored.events);
                    Build build=null;int builds=0;
                    for(int tick=0;tick<240;tick++) {
                        if(tick==2||tick==21) {build=chooseBuild(original,kwd);if(build!=null)builds++;}
                        if(build!=null&&(tick==2||tick==3||tick==21||tick==22)) {
                            command(original,build,tick==2||tick==21);
                            command(restored,build,tick==2||tick==21);
                            equal(SimulationState.capture(original.manager),SimulationState.capture(restored.manager),name+" command="+tick);
                            if(tick==2||tick==21)restored=reload(restored,name+" pending build="+tick);
                        }
                        original.tick(logicStep);restored.tick(logicStep);
                        equal(SimulationState.capture(original.manager),SimulationState.capture(restored.manager),name+" tick="+tick);
                        if(!original.events.equals(restored.events))throw new AssertionError(name+" client events differ at "+tick+": "+original.events+" / "+restored.events);
                        original.events.clear();restored.events.clear();
                        if(tick%40==20)restored=reload(restored,name+" reload="+tick);
                    }
                    System.out.println("PASS "+name+" full server archive/disk binding, 240 logic steps with 60Hz animation/steering, "+(6+builds)+" reloads and "+builds+" build/sell cycles");
                } finally {restored.close();}
            }
        }
        System.out.println("Server simulation continuation only; native loop phase, client/session restore, complete files and mission completion remain unverified.");
    }
    static SimulationArchive diskRoundTrip(toniarts.openkeeper.tools.convert.map.IKwdFile definition,SimulationState state)throws Exception {
        var archive=new SimulationArchive(SimulationArchive.Definition.capture(definition),state);
        var directory=Files.createTempDirectory(Path.of("work"),"continuation-save-");var file=directory.resolve("world.mksa");
        try {
            SimulationArchives.write(file,archive);var restored=SimulationArchives.read(file);
            if(!Arrays.equals(SimulationArchives.encode(archive),SimulationArchives.encode(restored)))throw new AssertionError("Archive bytes changed after decode");
            equal(state,restored.simulation(),"Disk round trip");return restored;
        } finally {Files.deleteIfExists(file);Files.deleteIfExists(directory);}
    }
    static Driver reload(Driver driver,String stage)throws Exception {
        var state=SimulationState.capture(driver.manager);
        var definition=driver.manager.getGameController().getLevelInfo().getLevelData();
        var next=new Driver(diskRoundTrip(definition,state).restore(definition,WorldContinuationSmoke::service));
        equal(state,SimulationState.capture(next.manager),stage);
        if(!next.events.isEmpty())throw new AssertionError("Reload replayed client events: "+next.events);
        next.events.addAll(driver.events);driver.close();return next;
    }
    static void rejectsIsolatedFailures(toniarts.openkeeper.tools.convert.map.IKwdFile kwd,SimulationState state,Driver active,GameSessionServerService old)throws Exception {
        var staged=new GameEntityData[1];
        try {
            GameLoopManager.restore(kwd,state,data->{staged[0]=data;return old;});
            throw new AssertionError("Accepted service from active world");
        }catch(IllegalArgumentException expected){}
        if(!staged[0].capture().entities().isEmpty())throw new AssertionError("Rejected store was not closed");
        var broken=new SimulationState(state.entities(),state.keepers(),state.players(),List.of(),state.flashes(),state.level(),state.systems(),state.triggers(),state.random(),state.results());
        try {
            GameLoopManager.restore(kwd,broken,data->{staged[0]=data;return service(data);});
            throw new AssertionError("Accepted absent rooms");
        }catch(IllegalArgumentException expected){}
        if(!staged[0].capture().entities().isEmpty())throw new AssertionError("Failed assembler did not close store");
        var discarded=GameLoopManager.restore(kwd,state,WorldContinuationSmoke::service);
        discarded.stop();discarded.stop();discarded.getEntityData().close();
        equal(state,SimulationState.capture(active.manager),"Active world after failed/discarded restore");
    }
    record Build(short owner,short room,int x,int y) {}
    static Build chooseBuild(Driver driver,toniarts.openkeeper.tools.convert.map.IKwdFile kwd) {
        var game=driver.manager.getGameController();var world=(GameWorldController)game.getGameWorldController();
        var map=world.getMapController();var blocked=world.getConstructionBlockingTiles();
        for(var player:game.getPlayerControllers().values().stream().sorted(Comparator.comparingInt(p->p.getKeeper().getId())).toList()) {
            short owner=player.getKeeper().getId();
            if(owner<3)continue;
            for(var room:kwd.getRooms().stream().sorted(Comparator.comparingInt(r->r.getRoomId())).toList()) {
                for(int y=0;y<map.getMapData().getHeight();y++)for(int x=0;x<map.getMapData().getWidth();x++) {
                    var point=new com.jme3.math.Vector2f(x,y);
                    if(RoomPlacementValidator.validate(kwd,map,blocked,point,point,owner,room.getRoomId(),player.getKeeper().getGold()).isValid())
                        return new Build(owner,room.getRoomId(),x,y);
                }
            }
        }
        return null;
    }
    static void command(Driver driver,Build build,boolean construct) {
        driver.manager.runPlayerCommand(()->{
            var game=driver.manager.getGameController();var world=game.getGameWorldController();
            var point=new com.jme3.math.Vector2f(build.x(),build.y());
            if(construct)world.build(point,point,build.owner(),build.room());else world.sell(point,point,build.owner());
            var room=world.getMapController().getRoomControllerByCoordinates(new toniarts.openkeeper.utils.Point(build.x(),build.y()));
            if(construct&&(room==null||room.getRoom().getRoomId()!=build.room()))throw new AssertionError("Build command did not execute: "+build+" gold="+game.getPlayerController(build.owner()).getKeeper().getGold());
            if(!construct&&room!=null)throw new AssertionError("Sell command did not execute: "+build);
            int count=(int)world.getMapController().getRoomControllers().stream().filter(r->r.getOwnerId()==build.owner()).count();
            if(game.getPlayerController(build.owner()).getRoomControl().getTypeCount()!=count)throw new AssertionError("Player room listener lost topology notification");
        });
    }
    static GameSessionServerService service(GameEntityData data) {
        return (GameSessionServerService)Proxy.newProxyInstance(GameSessionServerService.class.getClassLoader(),new Class[]{GameSessionServerService.class},new Session(data));
    }
    static final class Session implements InvocationHandler {
        final GameEntityData data;final List<String> events=new ArrayList<>();
        Session(GameEntityData data){this.data=data;}
        public Object invoke(Object proxy,Method method,Object[] args){
            if(method.getName().equals("getEntityData"))return data;
            if(method.getReturnType()==boolean.class)return false;
            events.add(method.getName()+Arrays.deepToString(args));return null;
        }
    }
    static final class Driver implements AutoCloseable {
        final GameLoopManager manager;final List<String> events;final List<IGameLogicUpdatable[]> groups=new ArrayList<>();
        Driver(GameLoopManager manager)throws Exception {
            this.manager=manager;
            var service=GameLoopManager.class.getDeclaredField("gameService");service.setAccessible(true);
            events=((Session)Proxy.getInvocationHandler(service.get(manager))).events;
            var loops=GameLoopManager.class.getDeclaredField("loops");loops.setAccessible(true);
            var owner=GameLoop.class.getDeclaredField("gameLoopManager");owner.setAccessible(true);
            var systems=GameLogicManager.class.getDeclaredField("updatables");systems.setAccessible(true);
            var ready=GameLoop.class.getDeclaredField("initialized");ready.setAccessible(true);
            try {
                for(Object loop:(List<?>)loops.get(manager)) {
                    var members=(IGameLogicUpdatable[])systems.get(owner.get(loop));groups.add(members);
                    for(var system:members)system.start();
                    // Equivalent readiness for this explicitly single-threaded probe; never starts a Runner.
                    ready.setBoolean(loop,true);
                }
            } catch(Exception|Error failure){close();throw failure;}
        }
        void tick(float logicStep) {
            for(var system:groups.getFirst())system.processTick(logicStep);
            int frames = Math.round(logicStep * 60);
            if (Math.abs(frames / 60f - logicStep) > .000001f) throw new IllegalArgumentException("Probe requires authored tick rate dividing 60");
            for (int frame=0;frame<frames;frame++)
                for(int i=1;i<groups.size();i++)for(var system:groups.get(i))system.processTick(1f/60);
        }
        public void close(){manager.stop();manager.getEntityData().close();}
    }
    static void equal(SimulationState a,SimulationState b,String stage)throws Exception {
        var expected=sections(a);var actual=sections(b);
        if(!expected.keySet().equals(actual.keySet()))throw new AssertionError(stage+" section inventory differs: "+expected.keySet()+" vs "+actual.keySet());
        for(var key:expected.keySet())if(!Arrays.equals(expected.get(key),actual.get(key))) {
            int at=Arrays.mismatch(expected.get(key),actual.get(key));
            throw new AssertionError(stage+" section="+key+" firstDifference="+at+" lengths="+expected.get(key).length+"/"+actual.get(key).length);
        }
    }
    static Map<String,byte[]> sections(SimulationState state)throws Exception {
        var result=new TreeMap<String,byte[]>();var s=state.systems();
        result.put("entities",EntitySnapshots.encode(state.entities()));
        for(var v:state.keepers())result.put("keeper-"+v.id(),KeeperSnapshots.encode(v));
        for(var v:state.players())result.put("player-"+v.playerId(),PlayerControlSnapshots.encode(v));
        result.put("rooms",RoomSnapshots.encode(state.rooms()));result.put("flashes",MapControlSnapshots.encode(state.flashes()));
        result.put("level",LevelSnapshots.encode(state.level()));result.put("random",RandomSnapshots.encode(state.random()));
        result.put("results",ResultSnapshots.encode(state.results()));
        state.triggers().forEach((kind,value)->{try{result.put("trigger-"+kind,TriggerRegistrySnapshots.encode(value));}catch(IOException e){throw new UncheckedIOException(e);}});
        result.put("creature-index",CreatureIndexSnapshots.encode(s.creatures()));result.put("spellbooks",SpellbookIndexSnapshots.encode(s.spellbooks()));
        result.put("chickens",ChickenSnapshots.encode(s.chickens()));result.put("creature-ai",CreatureSystemSnapshots.encode(s.creatureAi()));
        for(var v:s.creatureControllers())result.put("creature-"+v.entityId(),CreatureControlSnapshots.encode(v));
        result.put("parties",PartySnapshots.encode(s.parties()));result.put("tasks",TaskGraphSnapshots.encode(s.taskGraph()));
        result.put("task-manager",TaskManagerSnapshots.encode(s.taskManager()));result.put("mana",ManaSnapshots.encode(s.mana()));
        result.put("experience",ExperienceSnapshots.encode(s.experience()));result.put("economy",KeeperEconomySnapshots.encode(s.economy()));
        result.put("recruitment",CreatureSpawnSnapshots.encode(s.recruitment()));result.put("hatchery",ChickenSpawnSnapshots.encode(s.chickenSpawning()));
        result.put("lifetimes",LifetimeSnapshots.encode(s.lifetimes()));result.put("slaps",SlapSnapshots.encode(s.slaps()));
        result.put("health",HealthSnapshots.encode(s.health()));result.put("animation",AnimationSnapshots.encode(s.animation()));
        result.put("view",CreatureViewSnapshots.encode(s.creatureView()));result.put("movement",MovementSnapshots.encode(s.movement()));
        result.put("spatial",PositionSnapshots.encode(s.positions()));return result;
    }
}
