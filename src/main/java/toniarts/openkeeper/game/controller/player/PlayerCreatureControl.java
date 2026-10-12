/*
 * Copyright (C) 2014-2016 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenKeeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenKeeper.  If not, see <http://www.gnu.org/licenses/>.
 */
package toniarts.openkeeper.game.controller.player;

import com.simsilica.es.EntityId;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.tools.convert.map.Creature;

import java.util.*;

/**
 * Holds a list of player creatures and functionality related to them
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class PlayerCreatureControl extends AbstractPlayerControl<Creature, Set<EntityId>, Short> {

    private final Creature imp;
    private int creatureCount = 0;

    public PlayerCreatureControl(Keeper keeper, Creature imp, Collection<Creature> creatures) {
        super(keeper, keeper.getAvailableCreatures(), creatures);
        this.imp = imp;
    }

    public List<toniarts.openkeeper.game.data.PlayerControlState.Group> captureGroups() {
        return types.entrySet().stream().map(e -> new toniarts.openkeeper.game.data.PlayerControlState.Group(e.getKey().getId(), List.copyOf(e.getValue()))).toList();
    }

    public void restoreGroups(List<Short> available, List<toniarts.openkeeper.game.data.PlayerControlState.Group> groups,
            java.util.function.IntFunction<Creature> definition, toniarts.openkeeper.game.save.GameEntityData data) {
        if (!types.isEmpty()) throw new IllegalStateException("Creature index already populated");
        if (!new HashSet<>(available).equals(new HashSet<>(keeper.getAvailableCreatures()))) throw new IllegalArgumentException("Creature availability differs");
        List<Creature> availableTypes = new ArrayList<>();Map<Creature, Set<EntityId>> restored = new LinkedHashMap<>();
        for (short id : available) {
            var type = definition.apply(id);if (type == null) throw new IllegalArgumentException("Unknown creature type");availableTypes.add(type);
        }
        int count = 0;
        for (var group : groups) {
            var type = definition.apply(group.type());if (type == null) throw new IllegalArgumentException("Unknown creature group");
            for (var id : group.entities()) if (!data.wasAllocated(id)) throw new IllegalArgumentException("Creature index exceeds allocator");
            // The ECS system can have pending death/ownership changes. Preserve its last observed index.
            restored.put(type, new LinkedHashSet<>(group.entities()));
            if (!isImp(type)) count += group.entities().size();
        }
        typesAvailable.clear();typesAvailable.addAll(availableTypes);types.putAll(restored);creatureCount = count;
    }

    @Override
    protected short getDataTypeId(Short type) {
        return type;
    }

    @Override
    protected Short getDataType(Creature type) {
        return type.getCreatureId();
    }

    public void onCreatureAdded(EntityId entityId, Creature creature) {

        // Add to the list
        Set<EntityId> creatureSet = get(creature);
        if (creatureSet == null) {
            creatureSet = new LinkedHashSet<>();
            put(creature, creatureSet);
        }
        if (!creatureSet.add(entityId)) return;

        // Listeners
        if (!isImp(creature)) {
            creatureCount++;
        }
    }

    public void onCreatureRemoved(EntityId entityId, Creature creature) {

        // Delete
        Set<EntityId> creatureSet = get(creature);
        if (creatureSet == null || !creatureSet.remove(entityId)) return;

        // Listeners
        if (!isImp(creature)) {
            creatureCount--;
        }
    }

    /**
     * Get all creatures
     *
     * @return the creatures
     */
    public Map<Creature, Set<EntityId>> getAllCreatures() {
        return new LinkedHashMap<>(types);
    }

    /**
     * Get creatures, minus the imps
     *
     * @return the creatures
     */
    public Map<Creature, Set<EntityId>> getCreatures() {
        Map<Creature, Set<EntityId>> map = getAllCreatures();
        map.remove(imp);
        return map;
    }

    private boolean isImp(Creature creature) {
        return creature.equals(imp);
    }

    public Creature getImp() {
        return imp;
    }

    /**
     * Get player creature count. Excluding imps.
     *
     * @return the creature count
     */
    @Override
    public int getTypeCount() {
        return creatureCount;
    }

    /**
     * Get the imp count
     *
     * @return the number of imps
     */
    public int getImpCount() {
        if (imp == null) {
            return 0;
        }
        Set<EntityId> imps = get(imp);
        return (imps != null ? imps.size() : 0);
    }

}
