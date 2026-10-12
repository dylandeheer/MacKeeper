/* SPDX-License-Identifier: GPL-3.0-or-later */
import com.jme3.math.Vector2f;
import com.simsilica.es.base.DefaultEntityData;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.ICreaturesController.SpawnType;
import toniarts.openkeeper.tools.convert.map.*;

/** Exercise production creature spawning and level changes against locally owned map definitions. */
class CreatureProgressionAudit {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: CreatureProgressionAudit <original data>");
        String base = Path.of(args[0]).toAbsolutePath().normalize() + "/";
        int maps = 0, definitions = 0, levels = 0;
        try (var files = Files.list(Path.of(base, "Data/editor/maps"))) {
            for (var file : files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".kwd")).sorted().toList()) {
                IKwdFile source = KwdFile.load(base, file);
                IKwdFile kwd = (IKwdFile) Proxy.newProxyInstance(IKwdFile.class.getClassLoader(), new Class<?>[]{IKwdFile.class},
                        (p, m, a) -> m.getName().equals("getThings") ? List.of() : m.invoke(source, a));
                IMapController map = (IMapController) Proxy.newProxyInstance(IMapController.class.getClassLoader(), new Class<?>[]{IMapController.class},
                        (p, m, a) -> { throw new AssertionError("Unexpected map access: " + m); });
                DefaultEntityData data = new DefaultEntityData();
                try {
                    var controller = new CreaturesController(kwd, data, source.getVariables(), () -> 0, null, map, null);
                    for (var creature : source.getCreatureList()) {
                        var id = controller.spawnCreature(creature.getId(), (short) 3, 1, new Vector2f(), SpawnType.CONJURE);
                        data.setComponent(id, new Gold(12345, data.getComponent(id, Gold.class).maxGold));
                        for (int level = 1; level <= 10; level++) {
                            controller.levelUpCreature(id, level, 0);
                            var stats = source.getCreatureStats(level);
                            var actual = data.getComponent(id, CreatureComponent.class);
                            require(actual.pay == (long) creature.getAttributes().getPay() * percent(stats, Variable.CreatureStats.StatType.PAY) / 100,
                                    file + " creature " + creature.getId() + " level " + level + " wages");
                            require(data.getComponent(id, Gold.class).gold == 12345, "Carried gold changed");
                            require(data.getComponent(id, Mobile.class).maxSpeed == actual.speed, "Movement speed stale");
                            var spells = data.getComponent(id, CreatureSpells.class);
                            Set<Short> actualSpells = spells == null ? Set.of() : spells.creatureSpells.stream()
                                    .map(e -> data.getComponent(e, toniarts.openkeeper.game.component.CreatureSpell.class).creatureSpellId).collect(Collectors.toSet());
                            final int currentLevel = level;
                            Set<Short> expected = creature.getSpells().stream().filter(s -> s.getLevelAvailable() <= currentLevel)
                                    .map(Creature.Spell::getCreatureSpellId).collect(Collectors.toSet());
                            require(actualSpells.equals(expected), "Spell unlock mismatch");
                            levels++;
                        }
                        definitions++;
                    }
                } finally { data.close(); }
                maps++;
                System.out.println(file.getFileName() + " PASS");
            }
        }
        require(maps > 0 && definitions > 0, "No creature definitions inspected");
        System.out.println("PASS " + maps + " maps; " + definitions + " creature definitions; " + levels + " level transitions");
        System.out.println("Definition and ECS checks only; combat balance, navigation and original-engine rounding parity remain unverified.");
    }
    static int percent(Map<Variable.CreatureStats.StatType, Variable.CreatureStats> stats, Variable.CreatureStats.StatType type) {
        return stats == null || !stats.containsKey(type) ? 100 : stats.get(type).getValue();
    }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
