/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.data;

import java.util.*;
import toniarts.openkeeper.tools.convert.map.AI.AIType;
import toniarts.openkeeper.utils.Point;

/** Detached authoritative keeper values; the map Player definition is bound separately. */
public record KeeperState(short id, boolean ai, AIType aiType,
        long gold, int goldReserve, int goldMined, int mana, int manaGain, int manaLoose, int maxMana,
        Point dungeonHeartLocation, boolean possession,
        List<Research> availableRooms, List<Research> availableSpells, List<Short> availableCreatures,
        List<Research> availableDoors, List<Research> availableTraps,
        Research currentResearch, boolean currentResearchShared,
        boolean destroyed, short killedByPlayerId, long creaturesLost, long enemyCreaturesKilled,
        long heroesKilled, List<Long> creatureKillsByPlayer, Set<Short> allies) {
    public static final int MAX_TYPES = 256;

    public record Research(short id, ResearchableType type, boolean spell,
            boolean discovered, int research, boolean upgraded) {
        public Research {
            typeId(id);Objects.requireNonNull(type);
            if (research < 0 || (spell && type != ResearchableType.SPELL)) throw new IllegalArgumentException("Invalid research state");
        }
        public static Research capture(ResearchableEntity entity) {
            if (entity.getClass() != ResearchableEntity.class && entity.getClass() != PlayerSpell.class) {
                throw new IllegalArgumentException("Unsupported research class: " + entity.getClass().getName());
            }
            return new Research(entity.getId(), entity.getResearchableType(), entity instanceof PlayerSpell,
                    entity.isDiscovered(), entity.getResearch(), entity.isUpgraded());
        }
        public ResearchableEntity restore() {
            ResearchableEntity result = spell ? new PlayerSpell(id, discovered) : new ResearchableEntity(id, discovered, type);
            result.setResearch(research);result.setUpgraded(upgraded);return result;
        }
    }

    public KeeperState {
        typeId(id);typeId(killedByPlayerId);
        if (gold < 0 || goldReserve < 0 || goldReserve > gold || goldMined < 0
                || mana < 0 || maxMana < 0 || mana > maxMana) throw new IllegalArgumentException("Invalid keeper resources");
        if (!destroyed && killedByPlayerId != 0) throw new IllegalArgumentException("Living keeper has defeat attribution");
        if (creaturesLost < 0 || enemyCreaturesKilled < 0 || heroesKilled < 0) throw new IllegalArgumentException("Negative combat count");
        availableRooms = catalog(availableRooms, ResearchableType.ROOM);
        availableSpells = catalog(availableSpells, ResearchableType.SPELL);
        availableDoors = catalog(availableDoors, ResearchableType.DOOR);
        availableTraps = catalog(availableTraps, ResearchableType.TRAP);
        availableCreatures = List.copyOf(availableCreatures);
        validateIds(availableCreatures);
        allies = Set.copyOf(allies);validateIds(allies);
        creatureKillsByPlayer = List.copyOf(creatureKillsByPlayer);
        if (creatureKillsByPlayer.size() != 256 || creatureKillsByPlayer.stream().anyMatch(n -> n < 0)) {
            throw new IllegalArgumentException("Invalid per-player kill counts");
        }
        if (currentResearchShared) {
            if (currentResearch == null || !switch (currentResearch.type()) {
                case ROOM -> availableRooms.contains(currentResearch);
                case SPELL -> availableSpells.contains(currentResearch);
                case DOOR -> availableDoors.contains(currentResearch);
                case TRAP -> availableTraps.contains(currentResearch);
            }) throw new IllegalArgumentException("Current research is not the saved catalog entry");
        }
    }

    private static List<Research> catalog(List<Research> values, ResearchableType type) {
        values = List.copyOf(values);
        if (values.size() > MAX_TYPES) throw new IllegalArgumentException("Too many research entries");
        Set<Short> ids = new HashSet<>();
        for (Research value : values) if (value.type() != type || !ids.add(value.id())) throw new IllegalArgumentException("Invalid research catalog");
        return values;
    }

    private static void validateIds(Collection<Short> values) {
        if (values.size() > MAX_TYPES || new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException("Invalid ID list");
        for (short id : values) typeId(id);
    }

    private static void typeId(short id) {
        if (id < 0 || id >= MAX_TYPES) throw new IllegalArgumentException("ID outside byte range: " + id);
    }
}
