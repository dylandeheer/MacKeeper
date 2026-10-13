/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.data;

import com.simsilica.es.EntityId;
import java.util.*;
import toniarts.openkeeper.game.controller.player.PlayerHandControl;
import toniarts.openkeeper.game.controller.player.PlayerStatsControl;

/** Detached state outside Keeper: resource limits and ordered controller indexes. */
public record PlayerControlState(short playerId, int goldMax, Short impType,
        List<Short> creaturesAvailable, List<Group> creatures, List<Group> rooms,
        boolean portalsOpen, EntityId dungeonHeart, ResearchIndex roomResearch,
        ResearchIndex spells, ResearchIndex doors, ResearchIndex traps,
        PlayerHandControl.State hand, PlayerStatsControl.State statistics) {
    public static final int MAX_ENTITIES = 65_536;
    public enum Binding { CATALOG, CURRENT, DETACHED }
    public record ResearchEntry(KeeperState.Research value, Binding binding) {
        public ResearchEntry {Objects.requireNonNull(value);Objects.requireNonNull(binding);}
    }
    public record ResearchIndex(List<Short> available, List<ResearchEntry> entries) {
        public ResearchIndex {
            available = typeIds(available);entries = List.copyOf(entries);
            if (entries.size() > 256) throw new IllegalArgumentException("Too many research index entries");
            Set<Short> ids = new HashSet<>();
            for (var entry : entries) if (!ids.add(entry.value().id())) throw new IllegalArgumentException("Duplicate research index entry");
        }
    }
    public record Group(short type, List<EntityId> entities) {
        public Group {
            typeId(type);entities = List.copyOf(entities);
            if (entities.size() > MAX_ENTITIES || new HashSet<>(entities).size() != entities.size()) throw new IllegalArgumentException("Invalid controller entity group");
            entities.forEach(PlayerControlState::entityId);
        }
    }
    public PlayerControlState {
        typeId(playerId);if (impType != null) typeId(impType);
        if (goldMax < 0) throw new IllegalArgumentException("Negative gold capacity");
        creaturesAvailable = typeIds(creaturesAvailable);creatures = groups(creatures);rooms = groups(rooms);
        if (dungeonHeart != null) {
            entityId(dungeonHeart);
            if (rooms.stream().noneMatch(g -> g.entities().contains(dungeonHeart))) throw new IllegalArgumentException("Primary heart absent from room index");
        }
        Objects.requireNonNull(roomResearch);Objects.requireNonNull(spells);Objects.requireNonNull(doors);Objects.requireNonNull(traps);Objects.requireNonNull(statistics);
        if (hand != null && hand.playerId() != playerId) throw new IllegalArgumentException("Hand owner differs");
    }
    public static List<Short> typeIds(List<Short> ids) {
        ids = List.copyOf(ids);
        if (ids.size() > 256 || new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("Invalid type list");
        ids.forEach(PlayerControlState::typeId);return ids;
    }
    private static List<Group> groups(List<Group> groups) {
        groups = List.copyOf(groups);Set<Short> types = new HashSet<>();Set<EntityId> ids = new HashSet<>();
        if (groups.size() > 256) throw new IllegalArgumentException("Too many controller groups");
        for (var group : groups) {
            if (!types.add(group.type())) throw new IllegalArgumentException("Duplicate controller type");
            for (var id : group.entities()) if (!ids.add(id)) throw new IllegalArgumentException("Duplicate controller entity");
        }
        if (ids.size() > MAX_ENTITIES) throw new IllegalArgumentException("Too many controller entities");
        return groups;
    }
    private static void typeId(short id) {if (id < 0 || id > 255) throw new IllegalArgumentException("Invalid type/player ID");}
    private static void entityId(EntityId id) {if (id == null || id.getId() < 0) throw new IllegalArgumentException("Invalid entity ID");}
}
