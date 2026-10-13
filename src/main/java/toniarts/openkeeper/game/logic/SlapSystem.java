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

import toniarts.openkeeper.game.controller.GameWorldController;

import com.simsilica.es.Entity;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import toniarts.openkeeper.game.save.SnapshotEntitySet;
import toniarts.openkeeper.game.save.SlapSnapshots;
import toniarts.openkeeper.game.save.GameEntityData;
import java.util.List;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import toniarts.openkeeper.game.component.CreatureComponent;
import toniarts.openkeeper.game.component.CreatureEfficiency;
import toniarts.openkeeper.game.component.CreatureMood;
import toniarts.openkeeper.game.component.Interaction;
import toniarts.openkeeper.game.component.ObjectComponent;
import toniarts.openkeeper.game.component.Owner;
import toniarts.openkeeper.game.component.Slapped;
import toniarts.openkeeper.game.controller.IPlayerController;
import toniarts.openkeeper.game.controller.entity.EntityController;
import toniarts.openkeeper.game.controller.player.PlayerStatsControl;
import toniarts.openkeeper.tools.convert.map.Creature;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.tools.convert.map.Variable;
import toniarts.openkeeper.utils.GameTimeCounter;

/**
 * Manages slapping of entities, the added effects etc
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class SlapSystem extends GameTimeCounter {

    private final static int EFFICIENCY_BONUS = 10;

    private final IKwdFile kwdFile;
    private final SnapshotEntitySet creatureEntities;
    private final SnapshotEntitySet objectEntities;
    private final EntityData entityData;
    private final int maxSlapDuration;
    private final Map<Short, PlayerStatsControl> statControls;
    private final Map<EntityId, Double> slapStartTimesByEntityId = new HashMap<>();

    private SlapSnapshots.State pendingRestore;

    public SlapSystem(EntityData entityData, IKwdFile kwdFile, Collection<IPlayerController> playerControllers,
            Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings) {
        this(entityData, kwdFile, playerControllers,
                (int) gameSettings.get(Variable.MiscVariable.MiscType.INCREASED_WORK_RATE_DURATION_FROM_SLAPPING_SECONDS).getValue(), null);
    }

    private SlapSystem(EntityData entityData, IKwdFile kwdFile, Collection<IPlayerController> playerControllers,
            int duration, SlapSnapshots.State saved) {
        this.kwdFile = kwdFile;
        this.entityData = entityData;
        statControls = HashMap.newHashMap(playerControllers.size());
        for (IPlayerController playerController : playerControllers) {
            statControls.put(playerController.getKeeper().getId(), playerController.getStatsControl());
        }
        maxSlapDuration = duration;

        creatureEntities = new SnapshotEntitySet(entityData, Slapped.class, CreatureComponent.class, Owner.class);
        objectEntities = new SnapshotEntitySet(entityData, Slapped.class, ObjectComponent.class, Interaction.class);
        if (saved == null) {
            processAddedCreatureEntities(creatureEntities);
            processAddedObjectEntities(objectEntities);
        } else {
            timeElapsed = saved.elapsed();
            slapStartTimesByEntityId.putAll(saved.startTimes());
            pendingRestore = saved;
        }
    }

    public SlapSnapshots.State captureState() {
        var creatures = pendingRestore == null ? ids(creatureEntities) : pendingRestore.creatures();
        var objects = pendingRestore == null ? ids(objectEntities) : pendingRestore.objects();
        return new SlapSnapshots.State(timeElapsed, maxSlapDuration, statControls.keySet().stream().sorted().toList(),
                slapStartTimesByEntityId, creatures, objects,
                dirty(creatureEntities, creatures, pendingRestore == null ? List.of() : pendingRestore.dirtyCreatures()),
                dirty(objectEntities, objects, pendingRestore == null ? List.of() : pendingRestore.dirtyObjects()));
    }

    public static SlapSystem restoreState(GameEntityData data, IKwdFile kwd, Collection<IPlayerController> players, SlapSnapshots.State saved) {
        var ids = players.stream().map(p -> p.getKeeper().getId()).sorted().toList();
        if (!ids.equals(saved.players())) throw new IllegalArgumentException("Slap players differ");
        for (var id : saved.creatures()) if (!data.wasAllocated(id)) throw new IllegalArgumentException("Unallocated slap creature");
        for (var id : saved.objects()) if (!data.wasAllocated(id)) throw new IllegalArgumentException("Unallocated slap object");
        return new SlapSystem(data, kwd, players, saved.duration(), saved);
    }

    private static List<EntityId> ids(Collection<Entity> entities) {
        return entities.stream().map(Entity::getId).sorted().toList();
    }

    private static List<EntityId> dirty(SnapshotEntitySet query, List<EntityId> observed, List<EntityId> saved) {
        var result = new TreeSet<>(saved);
        result.addAll(query.pendingChanges(new HashSet<>(observed)));
        return List.copyOf(result);
    }

    private void update(SnapshotEntitySet query, boolean creatures) {
        var observed = pendingRestore == null ? List.<EntityId>of() : creatures ? pendingRestore.creatures() : pendingRestore.objects();
        var changed = pendingRestore == null ? List.<EntityId>of() : dirty(query, observed,
                creatures ? pendingRestore.dirtyCreatures() : pendingRestore.dirtyObjects());
        boolean updated = query.applyChanges();
        if (pendingRestore != null) {
            var old = new HashSet<>(observed);
            processDeletedEntities(observed.stream().filter(id -> !query.containsId(id)).toList());
            var added = query.stream().filter(e -> !old.contains(e.getId())).toList();
            if (creatures) processAddedCreatureEntities(added); else processAddedObjectEntities(added);
            processChangedEntities(changed.stream().filter(query::containsId).map(query::getEntity).toList());
        } else if (updated) {
            processDeletedEntities(ids(query.getRemovedEntities()));
            if (creatures) processAddedCreatureEntities(query.getAddedEntities()); else processAddedObjectEntities(query.getAddedEntities());
            processChangedEntities(query.getChangedEntities());
        }
    }

    @Override
    public void processTick(float tpf) {
        super.processTick(tpf);

        update(creatureEntities, true);
        update(objectEntities, false);
        pendingRestore = null;

        // Remove the slap effect from creatures
        // TODO: So many variables, for work efficiency and speeding up, so, figure out
        for (Map.Entry<EntityId, Double> entry : slapStartTimesByEntityId.entrySet()) {
            if (timeElapsed - entry.getValue() >= maxSlapDuration) {
                entityData.removeComponent(entry.getKey(), Slapped.class);
            }
        }
    }

    private void processAddedCreatureEntities(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            double startTime = entity.get(Slapped.class).startTime;
            short creatureId = entity.get(CreatureComponent.class).creatureId;
            short ownerId = entity.get(Owner.class).ownerId;

            // Slap
            slapStartTimesByEntityId.put(entity.getId(), startTime);
            handleCreatureSlap(entity, creatureId, ownerId);

            // Efficiency, this doesn't stack, so only when added
            // TODO: there are several parameters, speed and work efficiency, and they look a bit weird to me, so just this now
            CreatureEfficiency efficiency = entityData.getComponent(entity.getId(), CreatureEfficiency.class);
            if (efficiency != null) {
                entityData.setComponent(entity.getId(), new CreatureEfficiency(efficiency.efficiencyPercentage + EFFICIENCY_BONUS));
            }
        }
    }

    private void processAddedObjectEntities(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            double startTime = entity.get(Slapped.class).startTime;
            Interaction interaction = entity.get(Interaction.class);

            // Slap
            slapStartTimesByEntityId.put(entity.getId(), startTime);
            handleObjectSlap(entity, interaction);
        }
    }

    private void handleObjectSlap(Entity entity, Interaction interaction) {
        if (interaction.dieWhenSlapped) {
            EntityController.setDamage(entityData, entity.getId(), Integer.MAX_VALUE);
        }
    }

    private void handleCreatureSlap(Entity entity, short creatureId, short ownerId) {

        // Stats and health
        Creature creature = kwdFile.getCreature(creatureId);

        // Stat
        statControls.get(ownerId).creatureSlapped(creature);

        // Damage
        int damage = creature.getAttributes().getSlapDamage();
        if (damage != 0) {
            EntityController.setDamage(entityData, entity.getId(), damage, ownerId);
        }

        // Mood
        short moodChange = creature.getAttributes().getAngerSlap();
        if (moodChange != 0) {
            synchronized (GameWorldController.GOLD_LOCK) {
                CreatureMood mood = entityData.getComponent(entity.getId(), CreatureMood.class);
                if (mood != null) {
                    entityData.setComponent(entity.getId(), mood.add(CreatureMood.REASON_OTHER,
                            CreatureMood.toRuntimeValue(moodChange)));
                }
            }
        }

        // TODO: Apply the force
    }

    private void processDeletedEntities(Collection<EntityId> entities) {
        for (EntityId id : entities.stream().sorted().toList()) {
            slapStartTimesByEntityId.remove(id);
            CreatureEfficiency efficiency = entityData.getComponent(id, CreatureEfficiency.class);
            if (efficiency != null) {
                entityData.setComponent(id, new CreatureEfficiency(efficiency.efficiencyPercentage - EFFICIENCY_BONUS));
            }
        }
    }

    private void processChangedEntities(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            double startTime = entity.get(Slapped.class).startTime;

            if (slapStartTimesByEntityId.get(entity.getId()) != startTime) {
                slapStartTimesByEntityId.put(entity.getId(), startTime);
                if (entity.get(CreatureComponent.class) != null) {
                    short creatureId = entity.get(CreatureComponent.class).creatureId;
                    short ownerId = entity.get(Owner.class).ownerId;

                    // Slap
                    handleCreatureSlap(entity, creatureId, ownerId);
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
        objectEntities.release();
        slapStartTimesByEntityId.clear();
    }

}
