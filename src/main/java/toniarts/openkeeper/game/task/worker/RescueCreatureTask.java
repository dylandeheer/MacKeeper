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
package toniarts.openkeeper.game.task.worker;

import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import toniarts.openkeeper.utils.Point;
import java.util.Objects;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.task.AbstractTileTask;
import toniarts.openkeeper.game.task.TaskManager;
import toniarts.openkeeper.game.task.TaskType;
import toniarts.openkeeper.utils.WorldUtils;

/**
 * A task for creatures to rescue a fellow comrade from the harsh world
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class RescueCreatureTask extends AbstractTileTask implements toniarts.openkeeper.game.task.CreatureTargetTask {

    private final toniarts.openkeeper.game.task.TaskTarget<ICreatureController> creature;
    private final TaskManager taskManager;

    public RescueCreatureTask(toniarts.openkeeper.game.task.TaskIdentityAllocator taskIds, final TaskManager taskManager, final INavigationService navigationService, final IMapController mapController, ICreatureController creature, short playerId) {
        super(taskIds, navigationService, mapController, WorldUtils.vectorToPoint(creature.getPosition()), playerId);
        this.creature = toniarts.openkeeper.game.task.TaskTarget.bound(creature);
        this.taskManager = taskManager;
    }

    public RescueCreatureTask(toniarts.openkeeper.game.task.TaskIdentityAllocator ids, toniarts.openkeeper.game.data.TaskBaseState saved,
            INavigationService navigation, IMapController map, toniarts.openkeeper.utils.Point point, short player,
            com.simsilica.es.EntityId targetCreature, TaskManager manager) {
        super(ids, saved, navigation, map, point, player);
        creature = toniarts.openkeeper.game.task.TaskTarget.pending(targetCreature);
        taskManager = manager;
    }

    @Override public toniarts.openkeeper.game.task.TaskTarget<ICreatureController> creatureTarget() {return creature;}

    @Override
    public Vector2f getTarget(ICreatureController creature) {
        return WorldUtils.pointToVector2f(getTaskLocation()); // FIXME 0.5f not needed?
    }

    @Override
    public Point getTaskLocation() {
        Vector3f pos = creature.get().getPosition();
        if (pos != null) {
            return WorldUtils.vectorToPoint(creature.get().getPosition());
        }
        return null;
    }

    @Override
    public boolean isValid(ICreatureController creature) {
        return this.creature.get().isUnconscious() && this.creature.get().hasLair() && !this.creature.get().isDragged() && !this.creature.get().isRecuperating();
    }

    @Override
    public boolean isRemovable() {
        return !this.creature.get().isUnconscious();
    }

    @Override
    public String toString() {
        return "Rescuing a creature at " + getTaskLocation();
    }

    @Override
    public void executeTask(ICreatureController creature, float executionDuration) {
        this.creature.get();
        this.creature.get().setHaulable(creature);

        // Assign carry to lair
        CarryCreatureToLairTask task = new CarryCreatureToLairTask(taskIds, navigationService, mapController, this.creature.get(), playerId);
        taskManager.addTask(playerId, task);
        task.assign(creature, true);
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.RESCUE_CREATURE;
    }

    @Override
    public EntityId getTaskTarget() {
        return creature.id();
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 83 * hash + Objects.hashCode(this.creature.get());
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final RescueCreatureTask other = (RescueCreatureTask) obj;
        if (!Objects.equals(this.creature.get(), other.creature.get())) {
            return false;
        }
        return true;
    }

}
