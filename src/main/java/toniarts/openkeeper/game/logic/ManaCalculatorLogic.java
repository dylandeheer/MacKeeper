/*
 * Copyright (C) 2014-2017 OpenKeeper
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
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.component.Mana;
import toniarts.openkeeper.game.component.Owner;
import toniarts.openkeeper.game.controller.IPlayerController;
import toniarts.openkeeper.game.controller.player.PlayerManaControl;

/**
 * Calculates mana for all players
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class ManaCalculatorLogic implements IGameLogicUpdatable {

    private float tick = 0;
    private final SnapshotEntitySet manaEntities;
    private java.util.List<EntityId> restoredDirty;
    private final Map<EntityId, Short> ownerIdsByEntityId = new HashMap<>();
    private final Map<EntityId, Integer> manaGenerationByEntityId = new HashMap<>();
    private final Map<Short, PlayerManaControl> manaControls;
    private final Map<Short, Integer> manaGains;
    private final Map<Short, Integer> manaLosses;

    public ManaSnapshots.State captureState() {
        var sources = new java.util.TreeMap<EntityId, ManaSnapshots.Source>();
        ownerIdsByEntityId.forEach((id, owner) -> sources.put(id, new ManaSnapshots.Source(owner, manaGenerationByEntityId.get(id))));
        var dirty = new java.util.TreeSet<>(manaEntities.pendingChanges(sources.keySet()));
        if (restoredDirty != null) dirty.addAll(restoredDirty);
        return new ManaSnapshots.State(tick, manaControls.keySet().stream().sorted().toList(), sources, manaGains, manaLosses, java.util.List.copyOf(dirty));
    }

    public static ManaCalculatorLogic restoreState(Collection<IPlayerController> players, GameEntityData data, ManaSnapshots.State saved) {
        for (var id : saved.sources().keySet()) if (!data.wasAllocated(id)) throw new IllegalArgumentException("Unallocated mana source");
        return new ManaCalculatorLogic(players, data, saved);
    }

    public ManaCalculatorLogic(Collection<IPlayerController> playerControllers, EntityData entityData) {
        this(playerControllers, entityData, null);
    }

    private ManaCalculatorLogic(Collection<IPlayerController> playerControllers, EntityData entityData, ManaSnapshots.State saved) {

        manaControls = HashMap.newHashMap(playerControllers.size());
        for (IPlayerController playerController : playerControllers) {
            PlayerManaControl manaControl = playerController.getManaControl();
            if (manaControl != null) {
                manaControls.put(playerController.getKeeper().getId(), manaControl);
            }
        }
        manaGains = HashMap.newHashMap(manaControls.size());
        manaLosses = HashMap.newHashMap(manaControls.size());

        // Listen for mana entities
        if (saved != null && !manaControls.keySet().equals(new java.util.HashSet<>(saved.players())))
            throw new IllegalArgumentException("Mana control owners differ");
        manaEntities = new SnapshotEntitySet(entityData, Mana.class, Owner.class);
        if (saved == null) processAddedEntities(manaEntities);
        else {
            tick = saved.tick();restoredDirty = saved.dirty();
            saved.sources().forEach((id, source) -> {ownerIdsByEntityId.put(id, source.player());manaGenerationByEntityId.put(id, source.generation());});
            manaGains.putAll(saved.gains());manaLosses.putAll(saved.losses());
        }
    }

    @Override
    public void processTick(float tpf) {
        tick += tpf;
        if (tick >= 1) {
            updateManaSources();
            updateManaControls();
            tick -= 1;
        }
    }

    private void updateManaControls() {
        for (Map.Entry<Short, PlayerManaControl> entry : manaControls.entrySet()) {
            entry.getValue().updateMana(manaGains.getOrDefault(entry.getKey(), 0), manaLosses.getOrDefault(entry.getKey(), 0));
        }
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {
        manaEntities.release();
    }

    private void updateManaSources() {
        if (restoredDirty != null) {
            var observed = new java.util.HashSet<>(ownerIdsByEntityId.keySet());
            var dirty = new java.util.HashSet<>(manaEntities.pendingChanges(observed));dirty.addAll(restoredDirty);
            manaEntities.applyChanges();
            processAddedEntities(manaEntities.stream().filter(e -> !observed.contains(e.getId())).toList());
            processDeletedEntities(observed.stream().filter(id -> !manaEntities.containsId(id)).toList());
            processChangedEntities(manaEntities.stream().filter(e -> observed.contains(e.getId()) && dirty.contains(e.getId())).toList());
            restoredDirty = null;
        } else if (manaEntities.applyChanges()) {

            processAddedEntities(manaEntities.getAddedEntities());

            processDeletedEntities(manaEntities.getRemovedEntities().stream().map(Entity::getId).toList());

            processChangedEntities(manaEntities.getChangedEntities());
        }
    }

    private void processAddedEntities(Collection<Entity> entities) {
        for (Entity entity : entities) {
            short ownerId = entity.get(Owner.class).ownerId;
            int manaGeneration = entity.get(Mana.class).manaGeneration;
            ownerIdsByEntityId.put(entity.getId(), ownerId);
            manaGenerationByEntityId.put(entity.getId(), manaGeneration);

            addManaGenerationToPlayer(ownerId, manaGeneration);
        }
    }

    private void processDeletedEntities(Collection<EntityId> entities) {
        for (EntityId id : entities) {
            short ownerId = ownerIdsByEntityId.remove(id);
            int manaGeneration = manaGenerationByEntityId.remove(id);

            removeManaGenerationFromPlayer(ownerId, manaGeneration);
        }
    }

    private void processChangedEntities(Collection<Entity> entities) {
        for (Entity entity : entities) {
            short newOwnerId = entity.get(Owner.class).ownerId;
            int newManaGeneration = entity.get(Mana.class).manaGeneration;
            short oldOwnerId = ownerIdsByEntityId.put(entity.getId(), newOwnerId);
            int oldManaGeneration = manaGenerationByEntityId.put(entity.getId(), newManaGeneration);

            if (newOwnerId != oldOwnerId || newManaGeneration != oldManaGeneration) {
                addManaGenerationToPlayer(newOwnerId, newManaGeneration);
                removeManaGenerationFromPlayer(oldOwnerId, oldManaGeneration);
            }
        }
    }

    private void removeManaGenerationFromPlayer(short playerId, int manaGeneration) {
        if (manaGeneration == 0) {
            return;
        }

        // Substract the mana generation from appropriate slot
        if (manaControls.containsKey(playerId)) {
            if (manaGeneration < 0) {
                substractMana(manaLosses, playerId, -manaGeneration);
            } else {
                substractMana(manaGains, playerId, manaGeneration);
            }
        }
    }

    private void addManaGenerationToPlayer(short playerId, int manaGeneration) {
        if (manaGeneration == 0) {
            return;
        }

        // Add the mana generation from appropriate slot
        if (manaControls.containsKey(playerId)) {
            if (manaGeneration < 0) {
                addMana(manaLosses, playerId, -manaGeneration);
            } else {
                addMana(manaGains, playerId, manaGeneration);
            }
        }
    }

    private static void substractMana(Map<Short, Integer> manaValues, short playerId, int manaGeneration) {
        manaValues.merge(playerId, manaGeneration, (t, u) -> {
            return t - u;
        });
    }

    private static void addMana(Map<Short, Integer> manaValues, short playerId, int manaGeneration) {
        manaValues.merge(playerId, manaGeneration, (t, u) -> {
            return t + u;
        });
    }
}
