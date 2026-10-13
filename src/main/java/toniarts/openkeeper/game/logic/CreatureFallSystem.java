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
import com.simsilica.es.EntitySet;
import toniarts.openkeeper.game.component.CreatureAi;
import toniarts.openkeeper.game.component.CreatureComponent;
import toniarts.openkeeper.game.component.CreatureFall;
import toniarts.openkeeper.game.component.Position;
import toniarts.openkeeper.game.controller.creature.CreatureState;
import toniarts.openkeeper.utils.GameTimeCounter;
import toniarts.openkeeper.utils.WorldUtils;
import toniarts.openkeeper.game.save.LifetimeSnapshots;

/**
 * Handles creature falling (dropped from hand). In the future maybe all these would be handled by a physics
 * thingie?
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class CreatureFallSystem extends GameTimeCounter {

    private static final float GRAVITY = 2.0f;

    private final EntityData entityData;
    private final EntitySet fallEntities;

    public CreatureFallSystem(EntityData entityData) {
        this.entityData = entityData;

        fallEntities = entityData.getEntities(CreatureFall.class, Position.class, CreatureComponent.class);
    }

    public LifetimeSnapshots.Clock captureTime() {return new LifetimeSnapshots.Clock(timeElapsed);}

    public static CreatureFallSystem restoreState(EntityData data, LifetimeSnapshots.Clock saved) {
        var system = new CreatureFallSystem(data);system.timeElapsed = saved.elapsed();return system;
    }

    @Override
    public void processTick(float tpf) {
        super.processTick(tpf);
        // Add new & remove old
        fallEntities.applyChanges();

        // Process ticks
        for (Entity entity : fallEntities.stream().sorted(java.util.Comparator.comparing(Entity::getId)).toList()) {
            Position position = entity.get(Position.class);
            Position newPosition = new Position(position.rotation, position.position.clone());
            newPosition.position.y = Math.max(newPosition.position.y - tpf * GRAVITY, WorldUtils.FLOOR_HEIGHT);
            entity.set(newPosition);
            if (newPosition.position.y == WorldUtils.FLOOR_HEIGHT) {

                // We'll just remove this and add the AI
                entityData.removeComponent(entity.getId(), CreatureFall.class);

                // The state, depending on the landing
                CreatureComponent creatureComponent = entity.get(CreatureComponent.class);
                entityData.setComponent(entity.getId(), new CreatureAi(timeElapsed, creatureComponent.stunDuration > 0f ? CreatureState.FALLEN : CreatureState.IDLE, creatureComponent.creatureId));
            }
        }
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {
        fallEntities.release();
    }

}
