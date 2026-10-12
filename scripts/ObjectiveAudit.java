/* SPDX-License-Identifier: GPL-3.0-or-later */
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import toniarts.openkeeper.game.controller.ILevelInfo;
import toniarts.openkeeper.game.data.ActionPoint;
import toniarts.openkeeper.game.data.ITriggerable;
import toniarts.openkeeper.game.state.session.PlayerService;
import toniarts.openkeeper.game.trigger.TriggerActionData;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerControl;
import toniarts.openkeeper.tools.convert.map.*;

/** Resolve objective commands to their actual trigger roots; validate display dispatch and dictionary IDs. */
class ObjectiveAudit {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ObjectiveAudit <original data> <converted assets>");
        String base = Path.of(args[0]).toAbsolutePath().normalize() + "/";
        int maps = 0, displays = 0;
        Map<String, Integer> markers = new TreeMap<>();
        try (var files = Files.list(Path.of(base, "Data/editor/maps"))) {
            for (var file : files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".kwd")).sorted().toList()) {
                IKwdFile kwd = KwdFile.load(base, file);
                maps++;
                Map<Integer, Set<String>> contexts = contexts(kwd);
                for (var trigger : kwd.getTriggers().values()) {
                    if (!(trigger instanceof TriggerAction action)) continue;
                    if (action.getType() != TriggerAction.ActionType.MAKE_OBJECTIVE
                            && action.getType() != TriggerAction.ActionType.DISPLAY_OBJECTIVE) continue;
                    Set<String> owners = contexts.getOrDefault(action.getId(), Set.of());
                    require(!owners.isEmpty(), "Unreachable objective " + file + ":" + action.getId());
                    if (action.getType() == TriggerAction.ActionType.MAKE_OBJECTIVE) {
                        String category = owners.stream().allMatch(o -> o.startsWith("PLAYER:")) ? "PLAYER" : "CREATURE_OR_OTHER";
                        markers.merge(category + ":" + action.getUserData("targetId"), 1, Integer::sum);
                    } else {
                        int objective = action.<Number>getUserData("objectiveId").intValue();
                        require(objective >= 0 && objective <= 32767, "Invalid objective ID");
                        String dictionary = kwd.getGameLevel().getTextTableId().getLevelDictFile();
                        try (var reader = Files.newBufferedReader(Path.of(args[1], "Interface/Texts", dictionary + ".properties"))) {
                            var text = new PropertyResourceBundle(reader);
                            require(!text.getString(Integer.toString(objective)).isBlank(), "Missing or empty objective text");
                        }
                        for (String owner : owners) {
                            require(owner.startsWith("PLAYER:"), "Display needs semantic review for context " + owner);
                            short player = Short.parseShort(owner.substring("PLAYER:".length()));
                            List<String> calls = new ArrayList<>();
                            PlayerService service = proxy(PlayerService.class, (name, values) -> {
                                require((short) values[1] == player, "Wrong display recipient");
                                if (name.equals("showObjective")) {
                                    require((int) values[0] == objective, "Wrong objective text ID");
                                } else require(name.equals("zoomViewToPoint"), "Unexpected action " + name);
                                calls.add(name);
                                return null;
                            });
                            ILevelInfo info = proxy(ILevelInfo.class, (name, values) -> switch (name) {
                                case "getLevelData" -> kwd;
                                case "getActionPoint" -> kwd.getThings(Thing.ActionPoint.class).stream()
                                        .filter(p -> p.getId() == ((Number) values[0]).intValue()).findFirst().map(ActionPoint::new).orElse(null);
                                default -> throw new AssertionError(name);
                            });
                            new DisplayControl(info, action.getId(), player, service).execute();
                            require(!calls.isEmpty() && calls.getFirst().equals("showObjective"), "Display not dispatched");
                        }
                        displays++;
                    }
                    System.out.println(file.getFileName() + "\t" + action.getId() + "\t" + action.getType().name() + "\t" + owners);
                }
            }
        }
        require(maps > 0 && displays > 0, "No maps or display commands inspected");
        System.out.println("PASS " + maps + " maps; " + displays + " display commands dispatched with valid text; markers=" + markers);
        System.out.println("Context/dispatch audit only; marker semantics, trigger timing, mission completion and network transport not verified.");
    }

    static Map<Integer, Set<String>> contexts(IKwdFile kwd) {
        Map<Integer, Set<String>> result = new HashMap<>();
        walk(kwd, kwd.getGameLevel().getTriggerId(), "LEVEL", result, new HashSet<>());
        for (var p : kwd.getPlayers().values()) walk(kwd, p.getTriggerId(), "PLAYER:" + p.getPlayerId(), result, new HashSet<>());
        for (var type : Thing.class.getDeclaredClasses()) {
            if (!Thing.class.isAssignableFrom(type)) continue;
            for (var thing : kwd.getThings(type.asSubclass(Thing.class))) {
                if (thing instanceof ITriggerable t) walk(kwd, t.getTriggerId(), thing.getClass().getSimpleName(), result, new HashSet<>());
                if (thing instanceof Thing.HeroParty p) for (var member : p.getHeroPartyMembers())
                    walk(kwd, member.getTriggerId(), "PARTY_CREATURE", result, new HashSet<>());
            }
        }
        return result;
    }

    static void walk(IKwdFile kwd, int id, String label, Map<Integer, Set<String>> contexts, Set<Integer> seen) {
        if (id == 0 || !seen.add(id)) return;
        contexts.computeIfAbsent(id, ignored -> new TreeSet<>()).add(label);
        Trigger trigger = kwd.getTriggers().get(id);
        require(trigger != null, "Missing trigger " + id);
        walk(kwd, trigger.getIdChild(), label, contexts, seen);
        walk(kwd, trigger.getIdNext(), label, contexts, seen);
    }

    static class DisplayControl extends PlayerTriggerControl {
        DisplayControl(ILevelInfo level, int id, short player, PlayerService service) {
            super(null, level, () -> 0, null, null, id, player, service);
        }
        void execute() {
            TriggerActionData action = (TriggerActionData) root.getChild(0);
            require(action.getType() == TriggerAction.ActionType.DISPLAY_OBJECTIVE, "Wrong loaded action");
            doAction(action);
        }
    }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    interface Call { Object invoke(String name, Object[] args); }
    static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p,m,a) -> call.invoke(m.getName(),a)));
    }
}
