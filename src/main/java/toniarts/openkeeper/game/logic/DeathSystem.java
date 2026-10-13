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

import com.jme3.util.SafeArrayList;
import com.simsilica.es.Entity;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import com.simsilica.es.EntitySet;
import java.util.Collections;
import java.util.Map;
import toniarts.openkeeper.game.component.Death;
import toniarts.openkeeper.tools.convert.map.Variable;
import toniarts.openkeeper.utils.GameTimeCounter;
import toniarts.openkeeper.game.save.LifetimeSnapshots;
import toniarts.openkeeper.game.save.GameEntityData;
import java.util.Collection;
import java.util.List;
import java.util.HashSet;

/**
 * The waste disposal class, removes entities after reasonable amount of time has passed (i.e. death
 * animations or corpse decays have passed). Also handles passing other posessions related to the entity,
 * maybe some other universal remove listener would be better in the long run...
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class DeathSystem extends GameTimeCounter {

    private final IEntityPositionLookup entityPositionLookup;
    private final EntitySet deathEntities;
    private final EntityData entityData;
    private final SafeArrayList<EntityId> entityIds;
    private final int timeToDecay;

    private boolean reconcileOnNextTick;

    public DeathSystem(EntityData entityData, Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings,
            IEntityPositionLookup entityPositionLookup) {
        this(entityData,entityPositionLookup,(int)gameSettings.get(Variable.MiscVariable.MiscType.DEAD_BODY_DIES_AFTER_SECONDS).getValue(),null);
    }

    private DeathSystem(EntityData data,IEntityPositionLookup lookup,int delay,LifetimeSnapshots.DeathState saved) {
        entityPositionLookup=lookup;entityData=data;timeToDecay=delay;entityIds=new SafeArrayList<>(EntityId.class);
        deathEntities=data.getEntities(Death.class);
        if(saved==null)processAddedEntities(deathEntities);
        else {timeElapsed=saved.clock().elapsed();entityIds.addAll(saved.observed());reconcileOnNextTick=true;}
    }

    public LifetimeSnapshots.DeathState captureState() {
        return new LifetimeSnapshots.DeathState(new LifetimeSnapshots.Clock(timeElapsed),timeToDecay,List.copyOf(entityIds));
    }

    public static DeathSystem restoreState(GameEntityData data,IEntityPositionLookup lookup,LifetimeSnapshots.DeathState saved) {
        for(var id:saved.observed())if(!data.wasAllocated(id))throw new IllegalArgumentException("Unallocated death observation");
        return new DeathSystem(data,lookup,saved.decayDelay(),saved);
    }

    @Override
    public void processTick(float tpf) {
        super.processTick(tpf);

        if(reconcileOnNextTick) {
            var observed=new HashSet<>(entityIds);deathEntities.applyChanges();
            processDeletedEntities(observed.stream().filter(id->!deathEntities.containsId(id)).toList());
            processAddedEntities(deathEntities.stream().filter(entity->!observed.contains(entity.getId())).toList());
            reconcileOnNextTick=false;
        } else if(deathEntities.applyChanges()) {
            processDeletedEntities(deathEntities.getRemovedEntities().stream().map(Entity::getId).toList());
            processAddedEntities(deathEntities.getAddedEntities());
        }

        // Decay stuff
        for (EntityId entityId : entityIds.getArray()) {
            Death death = deathEntities.getEntity(entityId).get(Death.class);
            if (timeElapsed - death.startTime >= timeToDecay) {
                entityData.removeEntity(entityId);
            }
        }
    }

    private void processAddedEntities(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(java.util.Comparator.comparing(Entity::getId)).toList()) {
            int index = Collections.binarySearch(entityIds, entity.getId());
            entityIds.add(~index, entity.getId());
            entityPositionLookup.getEntityController(entity.getId()).removePossession();
        }
    }

    private void processDeletedEntities(Collection<EntityId> entities) {
        for (EntityId id : entities) {
            int index = Collections.binarySearch(entityIds, id);
            entityIds.remove(index);
        }
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {
        deathEntities.release();
        entityIds.clear();
    }

}
