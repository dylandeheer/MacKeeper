/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.nio.file.*;
import java.util.*;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.trigger.TriggerGenericData;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerControl;
import toniarts.openkeeper.tools.convert.map.*;

/** Parsed campaign predicates in real worlds; injected defenses do not prove workshop or placement. */
class DefenseConditionAudit {
    public static void main(String[] args) throws Exception {
        String base = Path.of(args[0]).toAbsolutePath() + "/";
        int maps = 0, worlds = 0, conditions = 0;
        try (var files = Files.list(Path.of(base,"Data/editor/maps"))) {
            for (var file : files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".kwd")).sorted().toList()) {
                var kwd = KwdFile.load(base,file);maps++;
                var targets = kwd.getTriggers().values().stream().filter(t -> t instanceof TriggerGeneric g
                        && (g.getType() == TriggerGeneric.TargetType.PLAYER_DOORS || g.getType() == TriggerGeneric.TargetType.PLAYER_TRAPS)).map(TriggerGeneric.class::cast).toList();
                if (targets.isEmpty()) continue;
                var contexts = ObjectiveAudit.contexts(kwd);
                var data = new GameEntityData();
                try (var driver = new WorldContinuationSmoke.Driver(new GameLoopManager(kwd,WorldContinuationSmoke.service(data),null))) {
                    worlds++;
                    var game = driver.manager.getGameController();
                    var saved = SimulationState.capture(driver.manager);
                    try (var restored = new WorldContinuationSmoke.Driver(WorldContinuationSmoke.diskRoundTrip(kwd,saved).restore(kwd,WorldContinuationSmoke::service))) {
                        WorldContinuationSmoke.equal(saved,SimulationState.capture(restored.manager),file.getFileName()+" defenses archive");
                        for (var target : targets) for (var owner : contexts.getOrDefault(target.getId(),Set.of())) {
                            require(owner.startsWith("PLAYER:"),"Unexpected defense context "+owner);
                            short player = Short.parseShort(owner.substring(7));
                            require(new Condition(game,target.getId(),player).active() == new Condition(restored.manager.getGameController(),target.getId(),player).active(),"Changed predicate after restore");
                        }
                    }
                    for (var target : targets) {
                        var owners = contexts.getOrDefault(target.getId(),Set.of());require(!owners.isEmpty(),"Unbound condition");
                        for (var owner : owners) {
                            require(owner.startsWith("PLAYER:"),"Unexpected context");short player = Short.parseShort(owner.substring(7));
                            boolean doors = target.getType() == TriggerGeneric.TargetType.PLAYER_DOORS;
                            // Isolate owned defense state without advancing controllers or claiming construction acceptance.
                            for (var id : data.findEntities(null,DoorComponent.class)) data.removeEntity(id);
                            for (var id : data.findEntities(null,TrapComponent.class)) data.removeEntity(id);
                            for (var id : data.findEntities(null,ObjectComponent.class)) if (data.getComponent(id,ObjectComponent.class).objectId == 74) data.removeEntity(id);
                            var check = new Condition(game,target.getId(),player);
                            var comparison = target.getTargetValueComparison();
                            int threshold = target.<Number>getUserData("value").intValue();
                            int flags = target.<Number>getUserData("flag").intValue();
                            short type = target.<Number>getUserData("targetId").shortValue();
                            if (type == 0) type = doors ? kwd.getDoors().getFirst().getDoorId() : kwd.getTraps().getFirst().getTrapId();
                            require(comparison == TriggerGeneric.ComparisonType.EQUAL_TO && threshold == 0
                                    || comparison == TriggerGeneric.ComparisonType.GREATER_OR_EQUAL_TO && threshold > 0,"New authored semantics need audit extension");
                            boolean empty = comparison == TriggerGeneric.ComparisonType.EQUAL_TO;
                            require(check.active() == empty,"Empty predicate wrong "+target.getId());
                            var inserted = new ArrayList<com.simsilica.es.EntityId>();
                            int number = Math.max(1,threshold);
                            for (int i=0;i<number;i++) {
                                var id = data.createEntity();inserted.add(id);data.setComponent(id,new Owner(player,player));
                                if (doors) data.setComponent(id,new DoorComponent(type,false,(flags & 8) != 0));
                                else data.setComponents(id,new TrapComponent(type),new TrapViewState(type,(flags & 8) != 0));
                            }
                            require(check.active() != empty,"Threshold predicate wrong "+target.getId());
                            for (var id : inserted) data.removeEntity(id);
                            require(check.active() == empty,"Removed defense retained in predicate");
                        }
                        conditions++;
                    }
                    System.out.println("DEFENSES "+file.getFileName()+" conditions="+targets.size()+" empty/threshold/removal and pre-fixture archive=PASS");
                }
            }
        }
        require(maps>0&&conditions>0,"Empty audit");
        System.out.println("PASS "+maps+" maps; "+conditions+" authored conditions; "+worlds+" real worlds. Predicate and archive evidence only; workshop, placement, child-action timing and mission completion remain unverified.");
    }
    static class Condition extends PlayerTriggerControl {
        Condition(IGameController game,int trigger,short owner){super(game,game.getLevelInfo(),game.getGameTimer(),null,null,trigger,owner,null);}
        boolean active(){return isActive((TriggerGenericData)root.getChild(0));}
    }
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
