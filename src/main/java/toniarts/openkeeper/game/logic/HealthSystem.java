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

import toniarts.openkeeper.game.controller.room.FiveByFiveRotatedController;
import toniarts.openkeeper.game.component.ObjectComponent;
import toniarts.openkeeper.game.controller.IGameWorldController;
import com.jme3.util.SafeArrayList;
import com.simsilica.es.Entity;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import com.simsilica.es.EntitySet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.Comparator;
import toniarts.openkeeper.game.save.GameEntityData;
import toniarts.openkeeper.game.save.SnapshotEntitySet;
import toniarts.openkeeper.game.save.HealthSnapshots;
import toniarts.openkeeper.game.component.AttackTarget;
import toniarts.openkeeper.game.component.ChickenAi;
import toniarts.openkeeper.game.component.CreatureAi;
import toniarts.openkeeper.game.component.CreatureComponent;
import toniarts.openkeeper.game.component.CreatureImprisoned;
import toniarts.openkeeper.game.component.CreatureMood;
import toniarts.openkeeper.game.component.CreatureRecuperating;
import toniarts.openkeeper.game.component.CreatureTortured;
import toniarts.openkeeper.game.component.Damage;
import toniarts.openkeeper.game.component.LastDamageSource;
import toniarts.openkeeper.game.component.Death;
import toniarts.openkeeper.game.component.Health;
import toniarts.openkeeper.game.component.Interaction;
import toniarts.openkeeper.game.component.Mana;
import toniarts.openkeeper.game.component.Navigation;
import toniarts.openkeeper.game.component.ObjectViewState;
import toniarts.openkeeper.game.component.Owner;
import toniarts.openkeeper.game.component.Position;
import toniarts.openkeeper.game.component.Regeneration;
import toniarts.openkeeper.game.component.Unconscious;
import toniarts.openkeeper.game.controller.ICreaturesController;
import toniarts.openkeeper.game.controller.ILevelInfo;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.controller.IPlayerController;
import toniarts.openkeeper.game.controller.creature.CreatureState;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.map.IMapTileInformation;
import toniarts.openkeeper.tools.convert.map.Creature;
import toniarts.openkeeper.tools.convert.map.Variable;
import toniarts.openkeeper.utils.GameTimeCounter;

/**
 * Manages and monitors thing healthiness. Beeb... beeb... beeeeeeeeeeeeeeeeeeeb :)
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class HealthSystem extends GameTimeCounter {

    private final EntityData entityData;
    private final SafeArrayList<EntityId> entityIds;
    private final IEntityPositionLookup entityPositionLookup;
    private final ICreaturesController creaturesController;
    private final int timeToDeath;
    private final int healthRegeneratePerSecond;
    private final int healthRegeneratePerSecondImprisoned;
    private final ILevelInfo levelInfo;
    private final Map<Short, IPlayerController> playerControllersById;
    private final IMapController mapController;
    private final IGameWorldController world;

    private final SnapshotEntitySet healthEntities;
    private HealthSnapshots.State pendingRestore;
    private final EntitySet damageEntities;
    private final EntitySet imprisonedEntities;
    private final EntitySet torturedEntities;
    private final EntitySet regeneratedEntities;
    private final EntitySet recuperatingEntities;

    public HealthSystem(EntityData entityData, IEntityPositionLookup entityPositionLookup,
            Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings,
            ICreaturesController creaturesController, ILevelInfo levelInfo,
            Collection<IPlayerController> playerControllers, IMapController mapController,
            IGameWorldController world) {
        this(entityData, entityPositionLookup, creaturesController, levelInfo, playerControllers, mapController, world,
                new HealthSnapshots.Settings(
                        (int) gameSettings.get(Variable.MiscVariable.MiscType.CREATURE_DYING_STATE_DURATION_SECONDS).getValue(),
                        (int) gameSettings.get(Variable.MiscVariable.MiscType.MODIFY_HEALTH_OF_CREATURE_IN_LAIR_PER_SECOND).getValue(),
                        (int) gameSettings.get(Variable.MiscVariable.MiscType.PRISON_MODIFY_CREATURE_HEALTH_PER_SECOND).getValue()), null);
    }

    private HealthSystem(EntityData entityData, IEntityPositionLookup entityPositionLookup,
            ICreaturesController creaturesController, ILevelInfo levelInfo, Collection<IPlayerController> playerControllers,
            IMapController mapController, IGameWorldController world, HealthSnapshots.Settings settings, HealthSnapshots.State saved) {
        this.world = world;

        this.entityData = entityData;
        this.entityPositionLookup = entityPositionLookup;
        this.creaturesController = creaturesController;
        this.levelInfo = levelInfo;
        this.mapController = mapController;
        entityIds = new SafeArrayList<>(EntityId.class);

        playerControllersById = HashMap.newHashMap(playerControllers.size());
        for (IPlayerController player : playerControllers) {
            playerControllersById.put(player.getKeeper().getId(), player);
        }

        timeToDeath = settings.timeToDeath();
        healthRegeneratePerSecond = settings.regeneration();
        healthRegeneratePerSecondImprisoned = settings.imprisonedRegeneration();

        healthEntities = new SnapshotEntitySet(entityData, Health.class);
        if (saved == null) processAddedEntities(healthEntities);
        else {
            timeElapsed = saved.elapsed();entityIds.addAll(saved.observedHealth());pendingRestore = saved;
        }

        damageEntities = entityData.getEntities(Health.class, Damage.class);

        // Have the position also here, since the player may move imprisoned entities between jails, kinda still imprisoned but not counting towards death at the time
        imprisonedEntities = entityData.getEntities(CreatureImprisoned.class, Health.class, CreatureComponent.class, Position.class);

        // Have the position also here, since the player may move tortured entities between torture rooms, kinda still tortured but not counting towards death at the time
        torturedEntities = entityData.getEntities(CreatureTortured.class, Health.class, CreatureComponent.class, Position.class);

        regeneratedEntities = entityData.getEntities(Health.class, Regeneration.class, Owner.class);
        recuperatingEntities = entityData.getEntities(CreatureRecuperating.class, Health.class, CreatureComponent.class);
    }

    public HealthSnapshots.State captureState() {
        return new HealthSnapshots.State(timeElapsed,
                new HealthSnapshots.Settings(timeToDeath, healthRegeneratePerSecond, healthRegeneratePerSecondImprisoned),
                playerControllersById.keySet().stream().sorted().toList(), List.copyOf(entityIds), dirtyHealth(),
                pendingRestore == null ? ids(recuperatingEntities) : pendingRestore.observedRecuperating());
    }

    public static HealthSystem restoreState(GameEntityData data, IEntityPositionLookup lookup,
            ICreaturesController creatures, ILevelInfo level, Collection<IPlayerController> players,
            IMapController map, IGameWorldController world, HealthSnapshots.State saved) {
        if (!players.stream().map(p -> p.getKeeper().getId()).sorted().toList().equals(saved.players()))
            throw new IllegalArgumentException("Health players differ");
        for (var id : saved.observedHealth()) if (!data.wasAllocated(id)) throw new IllegalArgumentException("Unallocated health observation");
        for (var id : saved.observedRecuperating()) if (!data.wasAllocated(id)) throw new IllegalArgumentException("Unallocated recovery observation");
        return new HealthSystem(data, lookup, creatures, level, players, map, world, saved.settings(), saved);
    }

    private static List<EntityId> ids(Collection<Entity> entities) {
        return entities.stream().map(Entity::getId).sorted().toList();
    }

    private List<EntityId> dirtyHealth() {
        var dirty = new TreeSet<>(healthEntities.pendingChanges(new HashSet<>(entityIds)));
        if (pendingRestore != null) dirty.addAll(pendingRestore.dirtyHealth());
        return List.copyOf(dirty);
    }

    @Override
    public void processTick(float tpf) {
        super.processTick(tpf);
        // Health changes reset regeneration even when a replacement has equal values.
        if (pendingRestore != null) {
            var observed = new HashSet<>(entityIds);var dirty = dirtyHealth();
            healthEntities.applyChanges();
            processAddedEntities(healthEntities.stream().filter(e -> !observed.contains(e.getId())).toList());
            processDeletedEntities(observed.stream().filter(id -> !healthEntities.containsId(id)).toList());
            processChangedEntities(dirty.stream().filter(healthEntities::containsId).map(healthEntities::getEntity).toList());
        } else if (healthEntities.applyChanges()) {
            processAddedEntities(healthEntities.getAddedEntities());
            processDeletedEntities(ids(healthEntities.getRemovedEntities()));
            processChangedEntities(healthEntities.getChangedEntities());
        }

        // Update other monitorable sets
        damageEntities.applyChanges();
        imprisonedEntities.applyChanges();
        torturedEntities.applyChanges();
        regeneratedEntities.applyChanges();
        recuperatingEntities.applyChanges();

        // Process special recuperating... event
        var oldRecovery = pendingRestore == null ? null : new HashSet<>(pendingRestore.observedRecuperating());
        var addedRecovery = oldRecovery == null ? recuperatingEntities.getAddedEntities()
                : recuperatingEntities.stream().filter(e -> !oldRecovery.contains(e.getId())).toList();
        for (Entity entity : addedRecovery.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            entityData.removeComponent(entity.getId(), Unconscious.class);
        }
        pendingRestore = null;

        // Bring death to those unfortunate and increase the health of the fortunate
        for (EntityId entityId : entityIds.getArray()) {
            Unconscious unconscious = entityData.getComponent(entityId, Unconscious.class);

            // From unconsciousness we start the countdown to death
            if (unconscious != null) {
                if (timeElapsed - unconscious.startTime >= timeToDeath) {
                    processDeath(entityId);
                }
                continue;
            }

            // Normal health related routines
            // Destruction can remove room contents earlier in this same tick.
            Health health = entityData.getComponent(entityId, Health.class);
            if (health == null) {
                continue;
            }

            long healthChange = calculateHealthChange(entityId, health, timeElapsed);
            if (healthChange == 0) {
                continue;
            }

            // Set new health or death
            if (health.health + healthChange <= 0) {
                processHealthDepleted(entityId, health);
            } else {
                entityData.setComponent(entityId, new Health((int) Math.min(health.health + healthChange, health.maxHealth), health.maxHealth));
            }
        }
    }

    private void processHealthDepleted(EntityId entityId, Health health) {

        var object = entityData.getComponent(entityId, ObjectComponent.class);
        if (object != null && object.objectId == FiveByFiveRotatedController.OBJECT_HEART_ID) {
            Damage damage = damageEntities.getEntity(entityId) == null ? null : damageEntities.getEntity(entityId).get(Damage.class);
            entityData.setComponent(entityId, new Health(0, health.maxHealth));
            if (world.destroyDungeonHeart(entityId, damage == null ? (short) 0 : damage.sourcePlayerId)) return;
        }

        // Death or destruction!!!!
        // No body, just vanish from the world
        if (!isLeaveDeadBody(entityId)) {
            recordCreatureDeath(entityId);
            entityPositionLookup.getEntityController(entityId).remove();
            return;
        }

        // Some creatures die instantly, skipping the unconscious/dying wait and any skeleton rising, no matter where they are
        if (isDiesInstantly(entityId)) {
            processDeath(entityId);
            return;
        }

        // Tortured entities just die outright
        if (torturedEntities.containsId(entityId)) {
            processDeath(entityId);
            return;
        }

        // If there is enough capacity for skeletons, imprisoned enties will rise as skeletons
        if (imprisonedEntities.containsId(entityId)) {
            Owner owner = entityData.getComponent(entityId, Owner.class);
            Short creatureId = getRoomCreatureId(entityId);
            if (creatureId != null && !damageEntities.containsId(entityId) && canRiseAsSkeleton(owner, creatureId)) {
                recordCreatureDeath(entityId);
                creaturesController.turnCreatureIntoAnother(entityId, owner.controlId, creatureId);
                return;
            }
            processDeath(entityId);
            return;
        }

        // Leave the entity incapacitaded and waiting for death... or rescue
        processUnconscious(entityId, health);
    }

    private boolean isDiesInstantly(EntityId entityId) {
        CreatureComponent creatureComponent = entityData.getComponent(entityId, CreatureComponent.class);
        return creatureComponent != null && creatureComponent.diesInstantly;
    }

    private Short getRoomCreatureId(EntityId entityId) {

        // The room dictates what creatures it creates (by death in this case), so it somewhat makes sense that we look it up here
        // But maybe better to rethink this to somewhere else
        IRoomController roomController = mapController.getRoomControllerByCoordinates(entityPositionLookup.getEntityLocation(entityId).getLocation());
        if (roomController == null) {
            return null;
        }

        short creatureId = roomController.getRoom().getCreatedCreatureId();
        if (creatureId == 0) {
            return null;
        }

        return creatureId;
    }

    /**
     * TODO: maybe better of somewhere else
     *
     * @param owner
     * @param creatureId
     * @return
     */
    private boolean canRiseAsSkeleton(Owner owner, short creatureId) {
        if (owner == null) {
            return false;
        }

        int capacity = mapController.getPlayerSkeletonCapacity(owner.controlId);

        return capacity > playerControllersById.get(owner.controlId).getCreatureControl()
                .getTypeCount(levelInfo.getLevelData().getCreature(creatureId));
    }

    private void processUnconscious(EntityId entityId, Health health) {
        entityData.removeComponent(entityId, AttackTarget.class);
        entityData.setComponent(entityId, new Health(0, health.maxHealth));
        entityData.setComponent(entityId, new Unconscious(timeElapsed));
        //entityData.setComponent(entityId, new CreatureAi(gameTime, CreatureState.UNCONSCIOUS, creatureComponent.creatureId)); // Hmm
        creaturesController.createController(entityId).getStateMachine().changeState(CreatureState.UNCONSCIOUS);
        entityData.removeComponent(entityId, Navigation.class);
    }

    private boolean isLeaveDeadBody(EntityId entityId) {
        CreatureComponent creatureComponent = entityData.getComponent(entityId, CreatureComponent.class);
        return creatureComponent != null && levelInfo.getLevelData()
                .getCreature(creatureComponent.creatureId)
                .getFlags().contains(Creature.CreatureFlag.GENERATE_DEAD_BODY);
    }

    private long calculateHealthChange(EntityId entityId, Health health, double gameTime) {
        long delta = 0;

        // Damage (or healing)
        Entity entity = damageEntities.getEntity(entityId);
        if (entity != null) {
            Damage damage = entity.get(Damage.class);
            delta -= damage.damage;
            if (damage.damage > 0) {
                entityData.setComponent(entityId, new LastDamageSource(damage.sourcePlayerId));
            }

            // Remove the damage
            entityData.removeComponent(entityId, Damage.class);
        }

        // Imprisonment
        entity = imprisonedEntities.getEntity(entityId);
        if (entity != null) {
            CreatureImprisoned imprisoned = entity.get(CreatureImprisoned.class);
            if (gameTime - imprisoned.healthCheckTime >= 1) {
                entityData.setComponent(entity.getId(), new CreatureImprisoned(imprisoned.startTime, imprisoned.healthCheckTime + 1));
                delta += healthRegeneratePerSecondImprisoned;

                return delta; // Assume no other states can be
            }
        }

        // Torture
        entity = torturedEntities.getEntity(entityId);
        if (entity != null) {
            CreatureTortured tortured = entity.get(CreatureTortured.class);
            if (gameTime - tortured.healthCheckTime >= 1) {
                int tortureHealthRegeneratePerSecond = levelInfo.getLevelData().getCreature(entity.get(CreatureComponent.class).creatureId).getAttributes().getTortureHpChange();
                entityData.setComponent(entity.getId(), new CreatureTortured(tortured.timeTortured, tortured.tortureCheckTime, tortured.healthCheckTime + 1));
                delta += tortureHealthRegeneratePerSecond;

                return delta; // Assume no other states can be
            }
        }

        // Regeneration (with little bit optimization if already at full health)
        if (health.health != health.maxHealth) {
            entity = regeneratedEntities.getEntity(entityId);
            if (entity != null) {
                Regeneration regeneration = entity.get(Regeneration.class);
                IMapTileInformation tile = entityPositionLookup.getEntityLocation(entityId);
                Owner owner = entity.get(Owner.class);
                if (tile != null && owner != null && tile.getOwnerId() == owner.ownerId) {

                    // In own land
                    Double lastTimeOnOwnLand = regeneration.timeOnOwnLand;
                    if (lastTimeOnOwnLand == null) {
                        setTimeOwnOwnLand(regeneration, entityId, gameTime);
                    } else if (gameTime - lastTimeOnOwnLand >= 1) {
                        delta += regeneration.ownLandHealthIncrease;
                    }
                } else {

                    // At someones elses land, reset counter
                    setTimeOwnOwnLand(regeneration, entityId, null);
                }
            }
        }

        // Recuperating
        entity = recuperatingEntities.getEntity(entityId);
        if (entity != null) {
            CreatureRecuperating creatureRecuperating = entity.get(CreatureRecuperating.class);
            if (gameTime - creatureRecuperating.healthCheckTime >= 1) {
                entityData.setComponent(entity.getId(), new CreatureRecuperating(creatureRecuperating.startTime, creatureRecuperating.healthCheckTime + 1));
                delta += healthRegeneratePerSecond;
            }
        }

        return delta;
    }

    private void recordCreatureDeath(EntityId entityId) {
        CreatureComponent creature = entityData.getComponent(entityId, CreatureComponent.class);
        Owner owner = entityData.getComponent(entityId, Owner.class);
        if (creature == null || owner == null || entityData.getComponent(entityId, Health.class) == null) return;
        IPlayerController victim = playerControllersById.get(owner.ownerId);
        if (victim != null) victim.getKeeper().recordCreatureLoss();
        LastDamageSource source = entityData.getComponent(entityId, LastDamageSource.class);
        IPlayerController killer = source == null || source.playerId == 0 ? null : playerControllersById.get(source.playerId);
        if (killer != null) {
            boolean hero = levelInfo.getLevelData().getCreature(creature.creatureId).getFlags().contains(Creature.CreatureFlag.IS_GOOD);
            killer.getKeeper().recordCreatureKill(owner.ownerId, hero);
        }
    }

    private void processDeath(EntityId entityId) {
        recordCreatureDeath(entityId);
        entityData.removeComponent(entityId, Health.class);
        entityData.removeComponent(entityId, CreatureAi.class);
        entityData.removeComponent(entityId, ChickenAi.class);
        entityData.removeComponent(entityId, ObjectViewState.class);
        entityData.removeComponent(entityId, Navigation.class);
        entityData.removeComponent(entityId, Interaction.class);
        entityData.removeComponent(entityId, Unconscious.class);
        entityData.removeComponent(entityId, Mana.class);
        entityData.removeComponent(entityId, CreatureImprisoned.class);
        entityData.removeComponent(entityId, CreatureTortured.class);
        entityData.removeComponent(entityId, CreatureMood.class);
        entityData.setComponent(entityId, new Death(timeElapsed));
    }

    private void processAddedEntities(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            int index = Collections.binarySearch(entityIds, entity.getId());
            entityIds.add(~index, entity.getId());
        }
    }

    private void processDeletedEntities(Collection<EntityId> entities) {
        for (EntityId id : entities) {
            int index = Collections.binarySearch(entityIds, id);
            entityIds.remove(index);
        }
    }

    private void processChangedEntities(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            // If the health is changed (either by us or damage)...
            // Reset the health regen counter
            Regeneration regeneration = entityData.getComponent(entity.getId(), Regeneration.class);
            if (regeneration != null && regeneration.timeOnOwnLand != null) {
                setTimeOwnOwnLand(regeneration, entity.getId(), null);
            }
        }
    }

    private void setTimeOwnOwnLand(Regeneration regeneration, EntityId entityId, Double time) {
        regeneration = new Regeneration(regeneration);
        regeneration.timeOnOwnLand = time;
        entityData.setComponent(entityId, regeneration);
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {
        healthEntities.release();
        damageEntities.release();
        imprisonedEntities.release();
        torturedEntities.release();
        regeneratedEntities.release();
        recuperatingEntities.release();
        entityIds.clear();
    }

}
