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

import com.badlogic.gdx.ai.steer.SteeringAcceleration;
import com.badlogic.gdx.math.Vector2;
import com.jme3.util.SafeArrayList;
import com.simsilica.es.Entity;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import toniarts.openkeeper.game.save.GameEntityData;
import toniarts.openkeeper.game.save.MovementSnapshots;
import toniarts.openkeeper.game.save.SnapshotEntitySet;
import toniarts.openkeeper.utils.Point;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.TreeSet;
import toniarts.openkeeper.game.component.Mobile;
import toniarts.openkeeper.game.component.Navigation;
import toniarts.openkeeper.game.component.Position;
import toniarts.openkeeper.game.navigation.steering.EntitySteeringBehavior;
import toniarts.openkeeper.game.navigation.steering.EntitySteeringFactory;
import toniarts.openkeeper.game.navigation.steering.ISteerableEntity;
import toniarts.openkeeper.game.navigation.steering.SteerableEntity;
import toniarts.openkeeper.game.navigation.steering.SteeringUtils;

/**
 * Handles moving of the entities
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class MovementSystem implements IGameLogicUpdatable {
    
    private final static boolean INDEPENDENT_FACING = false;
    
    private final SafeArrayList<EntitySteeringBehavior> steeringBehaviors = new SafeArrayList<>(EntitySteeringBehavior.class);
    private final Map<EntityId, ISteerableEntity> steerableEntitiesByEntityId = new HashMap<>();
    private final Map<ISteerableEntity, EntitySteeringBehavior> steeringBehaviorsBySteerableEntity = new HashMap<>();
    private final Map<EntitySteeringBehavior, ISteerableEntity> steerableEntitiesBySteeringBehavior = new HashMap<>();
    private final Map<EntitySteeringBehavior, EntityId> entityIdsBySteeringBehavior = new HashMap<>();

    // For tracking the changes in navigation itself, for now it might be enough to just see that if the target has been changed
    private final Map<EntityId, Point> targetPointsByEntityId = new HashMap<>();

    /**
     * This is for saving in object creation
     */
    private final Map<EntitySteeringBehavior, SteeringAcceleration<Vector2>> steeringOutputsBySteeringBehaviors = new HashMap<>();
    private final SnapshotEntitySet movableEntities;
    private final Map<EntityId, EntitySteeringFactory.Plan> plans = new HashMap<>();
    private MovementSnapshots.State pendingRestore;
    private final EntityData entityData;

    public MovementSystem(EntityData entityData) {
        this(entityData, null);
    }

    private MovementSystem(EntityData entityData, MovementSnapshots.State saved) {
        this.entityData = entityData;
        movableEntities = new SnapshotEntitySet(entityData, Position.class, Mobile.class, Navigation.class);
        if (saved == null) processAddedEntities(movableEntities);
        else {
            pendingRestore = saved;
            for (var body : saved.bodies()) {
                var steerable = body.restore();
                register(steerable, EntitySteeringFactory.navigate(body.plan(), steerable),
                        body.plan(), new Point(body.targetX(), body.targetY()));
            }
        }
    }

    public static MovementSystem restoreState(GameEntityData data, MovementSnapshots.State saved) {
        for (var id : saved.observed()) if (!data.wasAllocated(id)) throw new IllegalArgumentException("Unallocated movement observation");
        return new MovementSystem(data, saved);
    }

    public MovementSnapshots.State captureState() {
        var observed = pendingRestore == null ? movableEntities.stream().map(Entity::getId).sorted().toList() : pendingRestore.observed();
        var bodies = steerableEntitiesByEntityId.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
            var id = entry.getKey();var target = targetPointsByEntityId.get(id);
            return MovementSnapshots.Body.capture(entry.getValue(), target.x, target.y, plans.get(id));
        }).toList();
        return new MovementSnapshots.State(observed, dirty(observed), bodies);
    }

    private List<EntityId> dirty(List<EntityId> observed) {
        var result = new TreeSet<EntityId>();
        if (pendingRestore != null) result.addAll(pendingRestore.dirty());
        result.addAll(movableEntities.pendingChanges(new HashSet<>(observed)));
        return List.copyOf(result);
    }

    @Override
    public void processTick(float tpf) {
        var changed = pendingRestore == null ? List.<EntityId>of() : dirty(pendingRestore.observed());
        boolean updated = movableEntities.applyChanges();
        if (pendingRestore != null) {
            var old = new HashSet<>(pendingRestore.observed());
            pendingRestore.observed().stream().filter(id -> !movableEntities.containsId(id)).forEach(this::deleteEntity);
            processAddedEntities(movableEntities.stream().filter(e -> !old.contains(e.getId())).toList());
            processChangedEntities(changed.stream().filter(movableEntities::containsId).map(movableEntities::getEntity).toList());
            pendingRestore = null;
        } else if (updated) {
            processDeletedEntities(movableEntities.getRemovedEntities());
            processAddedEntities(movableEntities.getAddedEntities());
            processChangedEntities(movableEntities.getChangedEntities());
        }

        // Process ticks
        for (EntitySteeringBehavior steeringBehavior : steeringBehaviors.getArray()) {

            // Calculate steering acceleration
            SteeringAcceleration<Vector2> steeringOutput = steeringOutputsBySteeringBehaviors.get(steeringBehavior);
            steeringBehavior.calculateSteering(steeringOutput);

            /*
             * Here you might want to add a motor control layer filtering steering accelerations.
             *
             * For instance, a car in a driving game has physical constraints on its movement: it cannot turn while stationary; the
             * faster it moves, the slower it can turn (without going into a skid); it can brake much more quickly than it can
             * accelerate; and it only moves in the direction it is facing (ignoring power slides).
             */
            // Apply steering acceleration
            applySteering(entityIdsBySteeringBehavior.get(steeringBehavior), steerableEntitiesBySteeringBehavior.get(steeringBehavior), steeringOutput, tpf);
        }
    }

    private void processAddedEntities(Collection<Entity> addedEntities) {
        for (Entity entity : addedEntities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            addEntity(entity);
        }
    }

    private void addEntity(Entity entity) {
        Mobile mobile = entity.get(Mobile.class);
        Navigation navigation = entity.get(Navigation.class);
        Position position = entity.get(Position.class);
        ISteerableEntity steerableEntity = new SteerableEntity(entity.getId(), mobile.maxSpeed, 0.25f, position.position.x, position.position.z, position.rotation);
        var plan = EntitySteeringFactory.plan(navigation.navigationPath, navigation.faceTarget, navigation.target);
        EntitySteeringBehavior steeringBehavior = EntitySteeringFactory.navigate(plan, steerableEntity);
        if (steeringBehavior == null) {

            // The fug, can't navigate, are we there already??
            entityData.removeComponent(entity.getId(), Navigation.class);
            return;
        }
        register(steerableEntity, steeringBehavior, plan, navigation.target);
    }

    private void register(ISteerableEntity steerableEntity, EntitySteeringBehavior steeringBehavior,
            EntitySteeringFactory.Plan plan, Point target) {
        var id = steerableEntity.getEntityId();
        plans.put(id, plan);
        steerableEntitiesByEntityId.put(id, steerableEntity);
        steeringBehaviorsBySteerableEntity.put(steerableEntity, steeringBehavior);
        steerableEntitiesBySteeringBehavior.put(steeringBehavior, steerableEntity);
        int index = Collections.binarySearch(steeringBehaviors, steeringBehavior);
        steeringBehaviors.add(~index, steeringBehavior);
        steeringOutputsBySteeringBehaviors.put(steeringBehavior, new SteeringAcceleration<>(new Vector2()));
        entityIdsBySteeringBehavior.put(steeringBehavior, id);
        targetPointsByEntityId.put(id, new Point(target.x, target.y));
    }

    private void processDeletedEntities(Collection<Entity> removedEntities) {
        for (Entity entity : removedEntities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            deleteEntity(entity.getId());
        }
    }

    private void deleteEntity(EntityId id) {
        plans.remove(id);
        ISteerableEntity steerableEntity = steerableEntitiesByEntityId.remove(id);
        EntitySteeringBehavior steeringBehavior = steeringBehaviorsBySteerableEntity.remove(steerableEntity);
        steerableEntitiesBySteeringBehavior.remove(steeringBehavior);
        if (steeringBehavior != null) {
            int index = Collections.binarySearch(steeringBehaviors, steeringBehavior);
            steeringBehaviors.remove(index);
        }
        steeringOutputsBySteeringBehaviors.remove(steeringBehavior);
        entityIdsBySteeringBehavior.remove(steeringBehavior);
        targetPointsByEntityId.remove(id);
    }

    private void processChangedEntities(Collection<Entity> changedEntities) {
        for (Entity entity : changedEntities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {

            // Dirty trick to try to see if the navigation has changed
            Navigation navigation = entity.get(Navigation.class);
            if (!navigation.target.equals(targetPointsByEntityId.get(entity.getId()))) {
                deleteEntity(entity.getId());
                addEntity(entity);
                continue;
            }

            // We are only prepared for the changes in Mobile
            // Not the position, the position is managed by us only
            Mobile mobile = entity.get(Mobile.class);

            ISteerableEntity steerableEntity = steerableEntitiesByEntityId.get(entity.getId());
            steerableEntity.setMaxLinearSpeed(mobile.maxSpeed);
        }
    }

    private void applySteering(EntityId entityId, ISteerableEntity steerableEntity, SteeringAcceleration<Vector2> steering, float tpf) {

        // We are done
        if (steering.isZero()
                && steerableEntity.getLinearVelocity().isZero(steerableEntity.getZeroLinearSpeedThreshold())
                && isZeroAngular(steerableEntity.getAngularVelocity(), steerableEntity.getZeroLinearSpeedThreshold())) {
            entityData.removeComponent(entityId, Navigation.class);
            return;
        }

        // trim speed if have no forces
        if (steering.linear.isZero() && !steerableEntity.getLinearVelocity().isZero()) {
            steerableEntity.getLinearVelocity().scl(0.8f);
        }
        // apply linear
        steerableEntity.getLinearVelocity().mulAdd(steering.linear, tpf).limit(steerableEntity.getMaxLinearSpeed());
        steerableEntity.getPosition().add(steerableEntity.getLinearVelocity().x * tpf, steerableEntity.getLinearVelocity().y * tpf);

        // Update orientation and angular velocity
        if (INDEPENDENT_FACING
                || steerableEntity.getLinearVelocity().isZero(steerableEntity.getZeroLinearSpeedThreshold())) {
            // trim speed if have no forces
            if (steering.angular == 0 && steerableEntity.getAngularVelocity() != 0) {
                steerableEntity.setAngularVelocity(steerableEntity.getAngularVelocity() * 0.7f);
            }
            // Update angular velocity. Velocity is trimmed to maximum speed
            steerableEntity.setAngularVelocity(steerableEntity.getAngularVelocity()
                    + limitAngular(steering.angular, steerableEntity.getMaxAngularAcceleration()) * tpf);
            steerableEntity.setAngularVelocity(limitAngular(steerableEntity.getAngularVelocity(), steerableEntity.getMaxAngularSpeed()));
            // apply
            if (!isZeroAngular(steerableEntity.getAngularVelocity(), steerableEntity.getZeroLinearSpeedThreshold())) {
                steerableEntity.setOrientation(steerableEntity.getOrientation() + steerableEntity.getAngularVelocity() * tpf);
            }
        } else {
            float newOrientation = SteeringUtils.calculateVectorToAngle(steerableEntity.getLinearVelocity());
            steerableEntity.setAngularVelocity(newOrientation - steerableEntity.getOrientation());
            steerableEntity.setOrientation(newOrientation);
        }

        // Also update the real components
        Position oldPosition = entityData.getComponent(entityId, Position.class);
        if (oldPosition != null) {

            var position = oldPosition.position.clone();
            position.x = steerableEntity.getPosition().x;
            position.z = steerableEntity.getPosition().y;
            entityData.setComponent(entityId, new Position(-steerableEntity.getOrientation(), position));
        }
    }

    /**
     * Limit angular speed or acceleration
     *
     * @param angular
     * @param limit
     * @return
     */
    public float limitAngular(float angular, float limit) {
        return (Math.abs(angular) > limit) ? Math.copySign(limit, angular) : angular;
    }

    public boolean isZeroAngular(float angular, float threshold) {
        return Math.abs(angular) < threshold;
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {
        movableEntities.release();
        steeringBehaviors.clear();
        steerableEntitiesByEntityId.clear();
        steerableEntitiesBySteeringBehavior.clear();
        steeringBehaviorsBySteerableEntity.clear();
        steeringOutputsBySteeringBehaviors.clear();
        entityIdsBySteeringBehavior.clear();
        targetPointsByEntityId.clear();
        plans.clear();
    }

}
