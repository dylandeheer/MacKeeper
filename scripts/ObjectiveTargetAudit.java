/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.data.ObjectiveType;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.state.loop.GameLoopManager;
import toniarts.openkeeper.game.trigger.*;
import toniarts.openkeeper.game.trigger.creature.CreatureTriggerControl;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerControl;
import toniarts.openkeeper.tools.convert.map.*;

/** Execute shipped objective actions in real map worlds; timing and campaign victory are excluded. */
class ObjectiveTargetAudit {
    public static void main(String[] args) throws Exception {
        String base = Path.of(args[0]).toAbsolutePath() + "/";
        int maps = 0, worlds = 0, keepers = 0, creatures = 0;
        try (var files = Files.list(Path.of(base, "Data/editor/maps"))) {
            for (var file : files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".kwd")).sorted().toList()) {
                var kwd = KwdFile.load(base, file); maps++;
                var actions = kwd.getTriggers().values().stream().filter(t -> t instanceof TriggerAction a
                        && a.getType() == TriggerAction.ActionType.MAKE_OBJECTIVE).map(TriggerAction.class::cast).toList();
                if (actions.isEmpty()) continue;
                var contexts = ObjectiveAudit.contexts(kwd);
                var data = new GameEntityData();
                try (var driver = new WorldContinuationSmoke.Driver(new GameLoopManager(kwd, WorldContinuationSmoke.service(data), null))) {
                    worlds++;
                    var game = driver.manager.getGameController();
                    var fixture = data.createEntity(); data.setComponent(fixture, new CreatureComponent());
                    var bound = ObjectiveAudit.proxy(ICreatureController.class, (name, values) -> {
                        require(name.equals("getEntityId"), "Unexpected creature query " + name); return fixture;
                    });
                    for (var action : actions) {
                        for (var entry : LevelObjectives.targets(data)) data.removeEntity(entry.entryId());
                        data.removeComponent(fixture, PlayerObjective.class);
                        var owners = contexts.getOrDefault(action.getId(), Set.of());
                        require(!owners.isEmpty(), "Unbound action " + action.getId());
                        if (owners.stream().allMatch(s -> s.startsWith("PLAYER:"))) {
                            for (String owner : owners) {
                                short player = Short.parseShort(owner.substring(7));
                                new PlayerAction(game, action.getId(), player).execute();
                                require(LevelObjectives.targets(data).stream().anyMatch(t -> t.playerId() == player && t.creatureId() == null), "Keeper not added");
                            }
                            keepers++;
                        } else {
                            require(owners.stream().noneMatch(s -> s.startsWith("PLAYER:") || s.equals("LEVEL")), "Mixed objective context " + owners);
                            short argument = action.<Number>getUserData("targetId").shortValue();
                            if (argument == 0) game.markCreatureObjective(fixture, ObjectiveType.KILL);
                            var control = new CreatureTriggerControl(game, game.getLevelInfo(), game.getGameTimer(), null, null, action.getId(), (short) 3, null);
                            var bind = AbstractThingTriggerControl.class.getDeclaredMethod("setThing", Object.class); bind.setAccessible(true); bind.invoke(control, bound);
                            var rootField = TriggerControl.class.getDeclaredField("root"); rootField.setAccessible(true);
                            var command = ((TriggerGenericData) rootField.get(control)).getChild(0);
                            var execute = CreatureTriggerControl.class.getDeclaredMethod("doAction", TriggerActionData.class); execute.setAccessible(true); execute.invoke(control, command);
                            var targets = LevelObjectives.targets(data);
                            if (argument == 0) require(targets.isEmpty() && data.getComponent(fixture, PlayerObjective.class) == null, "Off did not remove target");
                            else require(targets.size() == 1 && targets.getFirst().creatureId().equals(fixture)
                                    && targets.getFirst().objective() == switch(argument) {case 1 -> ObjectiveType.KILL; case 2 -> ObjectiveType.IMPRISON; case 3 -> ObjectiveType.CONVERT; default -> throw new AssertionError(argument);}, "Creature target mismatch");
                            creatures++;
                        }
                        LevelObjectives.validate(data, game.getLevelInfo().getPlayers().keySet());
                    }
                    // Remove the fixture so the full world assembler never sees a synthetic creature.
                    for (var entry : LevelObjectives.targets(data)) data.removeEntity(entry.entryId());
                    data.removeEntity(fixture);
                    for (short player : game.getLevelInfo().getPlayers().keySet().stream().sorted().limit(2).toList()) game.markPlayerObjective(player);
                    var saved = SimulationState.capture(driver.manager);
                    try (var restored = new WorldContinuationSmoke.Driver(WorldContinuationSmoke.diskRoundTrip(kwd, saved).restore(kwd, WorldContinuationSmoke::service))) {
                        WorldContinuationSmoke.equal(saved, SimulationState.capture(restored.manager), file.getFileName() + " objective archive");
                    }
                    System.out.println("OBJECTIVES " + file.getFileName() + " actions=" + actions.size() + " world restore=PASS");
                }
            }
        }
        require(maps > 0 && keepers > 0 && creatures > 0, "Empty objective audit");
        System.out.println("PASS " + maps + " maps inventoried; " + worlds + " real map worlds; " + keepers + " keeper and " + creatures + " creature actions dispatched; full server archive restoration checked per world.");
        System.out.println("Creature dispatch binds an explicit fixture entity; authored trigger timing, party spawn identity, full mission completion and native parity are not proved.");
    }
    static class PlayerAction extends PlayerTriggerControl {
        PlayerAction(IGameController game, int trigger, short owner) {super(game, game.getLevelInfo(), game.getGameTimer(), null, null, trigger, owner, null);}
        void execute() {doAction((TriggerActionData) root.getChild(0));}
    }
    static void require(boolean value, String message) {if (!value) throw new AssertionError(message);}
}
