/*
 * Copyright (C) 2014-2019 OpenKeeper
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
import toniarts.openkeeper.game.component.Decay;
import toniarts.openkeeper.game.component.Health;
import toniarts.openkeeper.game.component.Position;
import toniarts.openkeeper.game.controller.entity.EntityController;
import toniarts.openkeeper.utils.GameTimeCounter;
import toniarts.openkeeper.game.save.LifetimeSnapshots;

/**
 * Handles entity decaying
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class DecaySystem extends GameTimeCounter {

    private final EntitySet decayEntities;
    private final EntityData entityData;

    public DecaySystem(EntityData entityData) {
        this.entityData = entityData;

        decayEntities = entityData.getEntities(Decay.class, Position.class);
    }

    @Override
    public void processTick(float tpf) {
        super.processTick(tpf);

        decayEntities.applyChanges();

        // Decay stuff
        for (Entity entity : decayEntities.stream().sorted(java.util.Comparator.comparing(Entity::getId)).toList()) {
            Decay decay = entity.get(Decay.class);
            if (timeElapsed - decay.startTime >= decay.duration) {
                // Decay
                entityData.removeComponent(entity.getId(), Decay.class);
                decay(entity.getId());
            }
        }
    }

    private void decay(EntityId entityId) {
        Health health = entityData.getComponent(entityId, Health.class);
        if (health != null) {
            EntityController.setDamage(entityData, entityId, health.maxHealth * 2);
        } else {

            // Hmm, just outright remove it
            entityData.removeEntity(entityId);
        }
    }

    public LifetimeSnapshots.Clock captureTime() {return new LifetimeSnapshots.Clock(timeElapsed);}

    public static DecaySystem restoreState(EntityData data,LifetimeSnapshots.Clock saved) {
        var system=new DecaySystem(data);system.timeElapsed=saved.elapsed();return system;
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {
        decayEntities.release();
    }

}
