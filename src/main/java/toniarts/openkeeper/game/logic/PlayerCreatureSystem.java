/*
 * Copyright (C) 2014-2018 OpenKeeper
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
package toniarts.openkeeper.game.logic;

import com.simsilica.es.Entity;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import com.simsilica.es.EntitySet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import toniarts.openkeeper.game.component.CreatureComponent;
import toniarts.openkeeper.game.component.Health;
import toniarts.openkeeper.game.component.Owner;
import toniarts.openkeeper.game.controller.IPlayerController;
import toniarts.openkeeper.game.controller.player.PlayerCreatureControl;
import toniarts.openkeeper.tools.convert.map.IKwdFile;

/**
 * Basically just calculates the amount of creatures
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class PlayerCreatureSystem implements IGameLogicUpdatable {

    private final IKwdFile kwdFile;
    private final EntitySet creatureEntities;
    private final Map<Short, PlayerCreatureControl> creatureControls;
    private final Map<EntityId, Short> ownerIdsByEntityId = new HashMap<>();
    private final Map<EntityId, Short> creatureIdsByEntityId = new HashMap<>();

    private boolean reconcileOnNextTick;

    public record Entry(EntityId entityId, short ownerId, short creatureId) {
        public Entry {
            if (entityId == null || entityId.getId() < 0 || ownerId < 0 || ownerId > 255 || creatureId < 0 || creatureId > 255)
                throw new IllegalArgumentException("Invalid observed creature");
        }
    }
    public record State(java.util.List<Entry> entries) {
        public State {
            entries = java.util.List.copyOf(entries);
            if (entries.size() > 65_536 || entries.stream().map(Entry::entityId).distinct().count() != entries.size())
                throw new IllegalArgumentException("Invalid observed creature index");
        }
    }

    public State captureState() {
        return new State(ownerIdsByEntityId.keySet().stream().sorted().map(id -> new Entry(id, ownerIdsByEntityId.get(id), creatureIdsByEntityId.get(id))).toList());
    }

    public static PlayerCreatureSystem restoreState(toniarts.openkeeper.game.save.GameEntityData data, IKwdFile definition,
            Collection<IPlayerController> players, State state) {
        Map<EntityId, Entry> expected = new HashMap<>();
        for (var entry : state.entries()) {
            if (!data.wasAllocated(entry.entityId()) || definition.getCreature(entry.creatureId()) == null)
                throw new IllegalArgumentException("Observed creature reference/definition missing");
            expected.put(entry.entityId(), entry);
        }
        var playerIds = new java.util.HashSet<Short>();var indexed = new java.util.HashSet<EntityId>();
        for (var player : players) {
            if (!playerIds.add(player.getKeeper().getId())) throw new IllegalArgumentException("Duplicate player controller");
            for (var group : player.getCreatureControl().captureGroups()) for (var id : group.entities()) {
                var entry = expected.get(id);
                if (!indexed.add(id) || entry == null || entry.ownerId() != player.getKeeper().getId() || entry.creatureId() != group.type())
                    throw new IllegalArgumentException("Observed creature and player indexes differ");
            }
        }
        for (var entry : state.entries()) if (playerIds.contains(entry.ownerId()) && !indexed.contains(entry.entityId()))
            throw new IllegalArgumentException("Player index omits observed creature");
        return new PlayerCreatureSystem(data, definition, players, state);
    }

    public PlayerCreatureSystem(EntityData entityData, IKwdFile kwdFile, Collection<IPlayerController> playerControllers) {
        this(entityData, kwdFile, playerControllers, null);
    }

    private PlayerCreatureSystem(EntityData entityData, IKwdFile kwdFile, Collection<IPlayerController> playerControllers, State saved) {
        this.kwdFile = kwdFile;
        creatureControls = HashMap.newHashMap(playerControllers.size());
        for (IPlayerController playerController : playerControllers) {
            creatureControls.put(playerController.getKeeper().getId(), playerController.getCreatureControl());
        }

        creatureEntities = entityData.getEntities(CreatureComponent.class, Health.class, Owner.class);
        if (saved == null) processAddedEntities(creatureEntities);
        else {
            for (var entry : saved.entries()) {
                ownerIdsByEntityId.put(entry.entityId(), entry.ownerId());creatureIdsByEntityId.put(entry.entityId(), entry.creatureId());
            }
            reconcileOnNextTick = true;
        }
    }

    @Override
    public void processTick(float tpf) {
        boolean changed = creatureEntities.applyChanges();
        if (reconcileOnNextTick) {
            // A new EntitySet starts at current ECS state. Compare it with the saved
            // last-observed membership so pending additions/deaths/transfers still fire once.
            var current = new HashMap<EntityId, Entity>();
            for (var entity : creatureEntities) current.put(entity.getId(), entity);
            var added = current.values().stream().filter(e -> !ownerIdsByEntityId.containsKey(e.getId())).toList();
            var removed = ownerIdsByEntityId.keySet().stream().filter(id -> !current.containsKey(id)).sorted().toList();
            var retained = current.values().stream().filter(e -> ownerIdsByEntityId.containsKey(e.getId())).toList();
            processAddedEntities(added);removed.forEach(this::removeObserved);processChangedEntities(retained);
            reconcileOnNextTick = false;
        } else if (changed) {

            processAddedEntities(creatureEntities.getAddedEntities());

            processDeletedEntities(creatureEntities.getRemovedEntities());

            processChangedEntities(creatureEntities.getChangedEntities());
        }
    }

    private void processAddedEntities(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(java.util.Comparator.comparing(Entity::getId)).toList()) {
            short ownerId = entity.get(Owner.class).ownerId;
            short creatureId = entity.get(CreatureComponent.class).creatureId;
            ownerIdsByEntityId.put(entity.getId(), ownerId);
            creatureIdsByEntityId.put(entity.getId(), creatureId);
            if (creatureControls.containsKey(ownerId)) {
                creatureControls.get(ownerId).onCreatureAdded(entity.getId(), kwdFile.getCreature(creatureId));
            }
        }
    }

    private void processDeletedEntities(Set<Entity> entities) {
        for (Entity entity : entities.stream().sorted(java.util.Comparator.comparing(Entity::getId)).toList()) {
            removeObserved(entity.getId());
        }
    }

    private void removeObserved(EntityId id) {
        short ownerId = ownerIdsByEntityId.remove(id), creatureId = creatureIdsByEntityId.remove(id);
        if (creatureControls.containsKey(ownerId)) creatureControls.get(ownerId).onCreatureRemoved(id, kwdFile.getCreature(creatureId));
    }

    private void processChangedEntities(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(java.util.Comparator.comparing(Entity::getId)).toList()) {
            short newOwnerId = entity.get(Owner.class).ownerId;
            short newCreatureId = entity.get(CreatureComponent.class).creatureId;
            short oldOwnerId = ownerIdsByEntityId.put(entity.getId(), newOwnerId);
            short oldCreatureId = creatureIdsByEntityId.put(entity.getId(), newCreatureId);
            if (newOwnerId != oldOwnerId || newCreatureId != oldCreatureId) {
                if (creatureControls.containsKey(newOwnerId)) {
                    creatureControls.get(newOwnerId).onCreatureAdded(entity.getId(), kwdFile.getCreature(newCreatureId));
                }
                if (creatureControls.containsKey(oldOwnerId)) {
                    creatureControls.get(oldOwnerId).onCreatureRemoved(entity.getId(), kwdFile.getCreature(oldCreatureId));
                }
            }
        }
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {
        creatureEntities.release();
    }

}
