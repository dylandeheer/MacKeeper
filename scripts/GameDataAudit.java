/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.nio.file.*;
import java.util.*;
import toniarts.openkeeper.tools.convert.map.*;

class GameDataAudit {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: GameDataAudit <DK2 data directory>");
        String base = Path.of(args[0]).toAbsolutePath().normalize() + "/";
        Path maps = Path.of(base, "Data/editor/maps");
        int failed = 0;
        int inspected = 0;
        try (var files = Files.list(maps)) {
            for (Path path : files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".kwd")).sorted().toList()) {
                inspected++;
                try {
                    IKwdFile level = KwdFile.load(base, path);
                    Map<String,Integer> counts = new TreeMap<>();
                    for (Trigger trigger : level.getTriggers().values()) {
                        String kind = switch (trigger) {
                            case TriggerAction action -> "ACTION:" + action.getType().name();
                            case TriggerGeneric generic -> "TARGET:" + generic.getType().name();
                            default -> throw new IllegalStateException("Unknown trigger " + trigger.getId());
                        };
                        counts.merge(kind, 1, Integer::sum);
                        if (trigger instanceof TriggerAction action && action.getType() == TriggerAction.ActionType.MAKE) {
                            counts.merge("MAKE:" + trigger.getUserData("type"), 1, Integer::sum);
                        }
                        for (int link : new int[]{trigger.getIdChild(), trigger.getIdNext()}) {
                            if (link != 0 && !level.getTriggers().containsKey(link)) {
                                throw new IllegalStateException("Missing linked trigger " + link + " from " + trigger.getId());
                            }
                        }
                    }
                    validateGraph(level.getTriggers());
                    System.out.println(path.getFileName()+"\tMAP\t"+level.getTriggers().size());
                    counts.forEach((kind,count)->System.out.println(path.getFileName()+"\t"+kind+"\t"+count));
                } catch (Exception e) {
                    failed++;
                    System.err.println("AUDIT FAILED " + path.getFileName() + ": " + e);
                }
            }
        }
        if (inspected == 0) throw new IllegalStateException("No KWD maps found");
        if (failed > 0) throw new IllegalStateException(failed + " maps failed audit");
    }

    // Trigger repetitions are runtime counters; child/sibling links themselves must be acyclic.
    private static void validateGraph(Map<Integer, Trigger> triggers) {
        Set<Integer> completed = new HashSet<>();
        Set<Integer> visiting = new HashSet<>();
        for (int id : triggers.keySet()) visit(id, triggers, visiting, completed);
    }

    private static void visit(int id, Map<Integer, Trigger> triggers, Set<Integer> visiting, Set<Integer> completed) {
        if (id == 0 || completed.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalStateException("Cyclic trigger link at " + id);
        Trigger trigger = triggers.get(id);
        visit(trigger.getIdChild(), triggers, visiting, completed);
        visit(trigger.getIdNext(), triggers, visiting, completed);
        visiting.remove(id);
        completed.add(id);
    }
}
