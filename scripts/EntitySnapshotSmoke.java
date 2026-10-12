/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.state.session.GameSessionServerService;
import toniarts.openkeeper.tools.convert.map.KwdFile;

/** Real live ECS capture/codec/staged-store round trips, not a restored game session. */
class EntitySnapshotSmoke {
    public static void main(String[] args) throws Exception {
        String base=Path.of(args[0]).toAbsolutePath().normalize()+"/";
        Set<String> observed=new TreeSet<>();long totalComponents=0;
        for(String name:List.of("level1","level17","TrapTime","level10")) {
            var kwd=KwdFile.load(base,Path.of(base,"Data/editor/maps/"+name+".kwd"));
            var data=new GameEntityData();
            var service=(GameSessionServerService)Proxy.newProxyInstance(GameSessionServerService.class.getClassLoader(),
                    new Class<?>[]{GameSessionServerService.class},(p,m,a)->m.getName().equals("getEntityData")?data:m.getReturnType()==boolean.class?false:null);
            var manager=new GameLoopManager(kwd,service,null);
            try {
                manager.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                for(;;) {
                    try {if(manager.captureSnapshot(game->game.getGameTimer().getGameTime())>0)break;}
                    catch(IllegalStateException initializing){}
                    require(System.nanoTime()<deadline,"World did not start: "+name);Thread.sleep(20);
                }
                for(int round=0;round<3;round++) {
                    long started=System.nanoTime();
                    var snapshot=manager.captureSnapshot(game->{try{return data.capture();}catch(IOException e){throw new UncheckedIOException(e);}});
                    long captured=System.nanoTime();
                    byte[] bytes=EntitySnapshots.encode(snapshot); // Outside the simulation gate.
                    var decoded=EntitySnapshots.decode(bytes);var restored=GameEntityData.restore(decoded);
                    try {
                        byte[] again=EntitySnapshots.encode(restored.capture());
                        require(Arrays.equals(bytes,again),"ECS state differs after round trip: "+name+" round "+round);
                        require(restored.createEntity().getId()==snapshot.nextEntityId(),"Allocator resumed at wrong ID");
                        // Neither continued live play nor allocation in the
                        // staged store may alter the detached snapshot.
                        Thread.sleep(300);
                        require(Arrays.equals(bytes,EntitySnapshots.encode(snapshot)),"Detached snapshot changed");
                        long components=snapshot.entities().stream().mapToLong(e->e.components().size()).sum();totalComponents+=components;
                        for(var entity:snapshot.entities())for(var component:entity.components())observed.add(component.type());
                        System.out.println("PASS "+name+" round="+round+" entities="+snapshot.entities().size()+" components="+components
                                +" bytes="+bytes.length+" captureMs="+(captured-started)/1_000_000+" nextId="+snapshot.nextEntityId());
                    } finally {restored.close();}
                }
            } finally {manager.stop();data.close();}
        }
        System.out.println("PASS 12 owned-map ECS round trips; component records="+totalComponents+" observedTypes="+observed.size()+" "+observed);
        System.out.println("Entity records and staged stores only: controllers, tasks, triggers, RNG, session restore and full saved games remain unverified.");
    }
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
