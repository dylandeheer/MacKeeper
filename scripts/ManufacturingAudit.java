/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.nio.file.*;
import java.util.*;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.player.PlayerManufacturingControl;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.tools.convert.map.*;
import toniarts.openkeeper.utils.Point;

/** Owned-data queue/definition/archive audit. Does not prove creature production or delivery. */
class ManufacturingAudit {
    public static void main(String[] args) throws Exception {
        String base=Path.of(args[0]).toAbsolutePath()+"/";
        int maps=0,definitions=0;
        try(var files=Files.list(Path.of(base,"Data/editor/maps"))) {
            for(var file:files.filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".kwd")).sorted().toList()) {
                var kwd=KwdFile.load(base,file);maps++;
                Set<Short> doorCrates=new HashSet<>();
                for(var door:kwd.getDoors()) {
                    validate(kwd,door.getManufCrateObjectId(),door.getManufToBuild());
                    doorCrates.add(door.getManufCrateObjectId());definitions++;
                }
                for(var trap:kwd.getTraps()) {
                    validate(kwd,trap.getManufCrateObjectId(),trap.getManufToBuild());definitions++;
                    require(!doorCrates.contains(trap.getManufCrateObjectId()),"Cross-kind shared crate needs explicit identity: "+file);
                }
            }
        }
        var kwd=KwdFile.load(base,Path.of(base,"Data/editor/maps/Level3.kwd"));
        var data=new GameEntityData();
        try(var driver=new WorldContinuationSmoke.Driver(new GameLoopManager(kwd,WorldContinuationSmoke.service(data),null))) {
            var game=driver.manager.getGameController();
            var queue=game.getPlayerController((short)3).getManufacturingControl();
            require(!queue.hasWork(),"Unexpected new-world manufacturing work");
            // Fixtures create demand; placement and charging are deliberately outside this audit.
            var door=data.createEntity();data.setComponents(door,new Owner((short)3,(short)3),new DoorComponent((short)1,false,true));
            var trap=data.createEntity();data.setComponents(trap,new Owner((short)3,(short)3),new TrapComponent((short)3),new TrapViewState((short)3,true));
            require(queue.request(false,(short)1,new Point(20,20)),"Door request failed");
            require(queue.request(true,(short)3,new Point(21,20)),"Trap request failed");
            queue.contribute(100);
            var saved=SimulationState.capture(driver.manager);
            try(var restored=new WorldContinuationSmoke.Driver(WorldContinuationSmoke.diskRoundTrip(kwd,saved).restore(kwd,WorldContinuationSmoke::service))) {
                WorldContinuationSmoke.equal(saved,SimulationState.capture(restored.manager),"manufacturing queue archive");
                var resumed=restored.manager.getGameController().getPlayerController((short)3).getManufacturingControl();
                require(queue.getOrders().equals(resumed.getOrders()),"Queue changed during archive restore");
                for(var q:List.of(queue,resumed)) {
                    var ready=q.contribute(Integer.MAX_VALUE);require(ready.isComplete(),"Head failed to complete");
                    require(q.getOrders().size()==2,"Completed head dropped before crate commit");
                    require(q.complete(ready.id()),"Ready head commit failed");
                    require(!q.complete(ready.id()),"Repeated commit removed another order");
                    require(q.getOrders().getFirst().completedWork()==0,"Work spilled into next order");
                    q.contribute(250);
                }
                WorldContinuationSmoke.equal(SimulationState.capture(driver.manager),SimulationState.capture(restored.manager),"manufacturing continuation");
                var waiting=SimulationState.capture(restored.manager);
                try(var second=new WorldContinuationSmoke.Driver(WorldContinuationSmoke.diskRoundTrip(kwd,waiting).restore(kwd,WorldContinuationSmoke::service))) {
                    WorldContinuationSmoke.equal(waiting,SimulationState.capture(second.manager),"second manufacturing archive");
                }
            }
        }
        require(maps>0&&definitions>0,"Empty inventory");
        System.out.println("PASS "+maps+" maps; "+definitions+" defense definitions; Level3 queue full archive and continuation. Fixture demand and explicit work/commit only; no creature production, placement, delivery or mission-completion claim.");
    }
    private static void validate(IKwdFile kwd,short crate,int work) {
        var object=kwd.getObject(crate);
        require(object!=null&&object.getFlags().contains(GameObject.ObjectFlag.OBJECT_TYPE_CRATE),"Missing manufacture crate "+crate);
        require(work>=0&&work<=65535,"Invalid work "+work);
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
