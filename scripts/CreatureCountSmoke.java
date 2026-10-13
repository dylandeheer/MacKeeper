/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector2f;
import com.simsilica.es.EntityId;
import com.simsilica.es.base.DefaultEntityData;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.state.session.PlayerService;
import toniarts.openkeeper.game.trigger.TriggerGenericData;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerControl;

/** Owned-map count predicates and isolated child actions; not complete mission acceptance. */
class CreatureCountSmoke {
    static final Set<TriggerGeneric.TargetType> TYPES = Set.of(TriggerGeneric.TargetType.PLAYER_CREATURES_AT_LEVEL,
            TriggerGeneric.TargetType.PLAYER_HAPPY_CREATURES, TriggerGeneric.TargetType.PLAYER_ANGRY_CREATURES);
    public static void main(String[] args) throws Exception {
        String base = Path.of(args[0]).toAbsolutePath().normalize() + "/";
        int maps = 0, conditions = 0;
        try (var files = Files.list(Path.of(base, "Data/editor/maps"))) {
            for (var file : files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".kwd")).sorted().toList()) {
                IKwdFile kwd = KwdFile.load(base, file); maps++;
                for (var t : kwd.getTriggers().values()) if (t instanceof TriggerGeneric g && TYPES.contains(g.getType())) {
                    var owners = kwd.getPlayers().values().stream()
                            .filter(p -> contains(kwd, p.getTriggerId(), g.getId(), new HashSet<>())).toList();
                    require(owners.size() == 1, "Unexpected trigger roots");
                    exercise(file.getFileName().toString(), kwd, g, owners.getFirst().getPlayerId()); conditions++;
                }
            }
        }
        require(maps == 66 && conditions == 5, "Map or condition inventory changed: " + maps + "/" + conditions);
        System.out.println("PASS 66 maps inspected; all 5 authored level/mood predicates and their isolated child actions");
        System.out.println("Production spawn, level-up, mood, ownership and death-count checks; no played mission or native audio acceptance.");
    }

    static void exercise(String name, IKwdFile source, TriggerGeneric raw, short owner) throws Exception {
        require(raw.getTargetValueComparison() == TriggerGeneric.ComparisonType.GREATER_OR_EQUAL_TO, "Unexpected authored comparator");
        boolean levelCondition = raw.getType() == TriggerGeneric.TargetType.PLAYER_CREATURES_AT_LEVEL;
        int minLevel = levelCondition ? raw.<Number>getUserData("level").intValue() : 1;
        int required = raw.<Number>getUserData("value").intValue();
        require(required > 0 && required <= 100, "Unbounded authored fixture");
        short filter = levelCondition ? 0 : raw.<Number>getUserData("creatureId").shortValue();
        Creature type = source.getCreatureList().stream().filter(c -> !c.getFlags().contains(Creature.CreatureFlag.IS_WORKER)
                && (filter == 0 || c.getCreatureId() == filter) && c.getAttributes().getUnhappyThreshold() > 0).findFirst().orElseThrow();
        // Preserve the authored child chain but isolate it from sibling mission prerequisites.
        TriggerGeneric isolated = new TriggerGeneric(null);
        isolated.setId(raw.getId()); isolated.setType(raw.getType()); isolated.setRepeatTimes(raw.getRepeatTimes());
        isolated.setTargetValueComparison(raw.getTargetValueComparison()); isolated.setIdChild(raw.getIdChild());
        for (String key : raw.getUserDataKeys()) isolated.setUserData(key, raw.getUserData(key));
        Map<Integer, Trigger> triggers = new HashMap<>(source.getTriggers()); triggers.put(raw.getId(), isolated);
        IKwdFile view = proxy(IKwdFile.class, (m,a) -> switch (m.getName()) {
            case "getThings" -> List.of();
            case "getTriggers" -> triggers;
            default -> m.invoke(source, a);
        });
        int[] score = {0}; List<Integer> speeches = new ArrayList<>();
        PlayerService service = proxy(PlayerService.class, (m,a) -> switch (m.getName()) {
            case "isInTransition" -> false;
            case "playSpeech" -> { require((short)a[4] == owner, "Wrong speech recipient"); speeches.add((int)a[0]); yield null; }
            default -> throw new AssertionError(m.getName());
        });
        ILevelInfo info = proxy(ILevelInfo.class, (m,a) -> switch (m.getName()) {
            case "getLevelData" -> view;
            case "getPaydayCycle" -> 0L;
            case "getLevelScore" -> score[0];
            case "setLevelScore" -> { score[0] = (int)a[0]; yield null; }
            default -> throw new AssertionError(m.getName());
        });
        DefaultEntityData data = new DefaultEntityData();
        try {
            CreaturesController creatures = new CreaturesController(view, data, source.getVariables(), () -> 0, null, null, info);
            for (int i=0; i<required-1; i++) spawn(creatures, data, type.getCreatureId(), owner, minLevel, 0);
            EntityId last = spawn(creatures, data, type.getCreatureId(), owner, levelCondition ? minLevel-1 : 1, levelCondition ? 0 : 65533);
            spawn(creatures, data, source.getImp().getCreatureId(), owner, 10, 0);
            spawn(creatures, data, type.getCreatureId(), (short)(owner+1), 10, 0);
            Control control = new Control(info, creatures, raw.getId(), owner, service);
            require(!control.active(), name + " prematurely satisfied"); tick(control);
            require(score[0] == 0 && speeches.isEmpty(), name + " prematurely ran child action");
            if (levelCondition) creatures.levelUpCreature(last, Math.min(10, minLevel+1), 0);
            else data.setComponent(last, new CreatureMood(0,0,0,0,0,0));
            require(control.active(), name + " threshold not satisfied"); tick(control);
            int expectedScore = switch(name) { case "mpd2.kwd" -> 6000; case "mpd3.kwd" -> 8000; case "mpd4.kwd" -> 11000; default -> 0; };
            List<Integer> expectedSpeech = switch(name) { case "level2.kwd" -> List.of(10); case "Level15a.kwd" -> List.of(13); default -> List.of(); };
            require(score[0] == expectedScore && speeches.equals(expectedSpeech), name + " wrong child action");
            tick(control);
            require(score[0] == expectedScore && speeches.equals(expectedSpeech), name + " duplicated child action");
            data.setComponent(last, new Owner((short)(owner+1), owner));
            require(!new Control(info, creatures, raw.getId(), owner, service).active(), name + " stale owner count");
            data.setComponent(last, new Owner(owner, owner)); data.removeComponent(last, Health.class);
            require(!new Control(info, creatures, raw.getId(), owner, service).active(), name + " dead creature counted");
            System.out.println("PASS " + name + " trigger=" + raw.getId() + " " + raw.getType().name() + " -> score=" + score[0] + " speech=" + speeches);
        } finally { data.close(); }
    }
    static EntityId spawn(CreaturesController c, DefaultEntityData data, short type, short owner, int level, int anger) {
        EntityId id = c.spawnCreature(type, owner, level, new Vector2f(), ICreaturesController.SpawnType.CONJURE);
        data.setComponent(id, new CreatureMood(anger,0,0,0,0,0)); return id;
    }
    static boolean contains(IKwdFile k, int id, int sought, Set<Integer> seen) {
        if (id == 0 || !seen.add(id)) return false;
        if (id == sought) return true;
        Trigger t = k.getTriggers().get(id);
        return contains(k, t.getIdChild(), sought, seen) || contains(k, t.getIdNext(), sought, seen);
    }
    static void tick(PlayerTriggerControl c) { for (int i=0; i<30; i++) c.update(.1f); }
    static class Control extends PlayerTriggerControl {
        Control(ILevelInfo info, ICreaturesController c, int id, short owner, PlayerService service) {
            super(null, info, () -> 0, null, c, id, owner, service);
        }
        boolean active() { return isActive((TriggerGenericData)root.getChild(0)); }
    }
    interface Call { Object invoke(Method m, Object[] a) throws Exception; }
    static <T>T proxy(Class<T> type, Call call) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p,m,a) -> call.invoke(m,a))); }
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
