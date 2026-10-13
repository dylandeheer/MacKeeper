/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.task.creature;

import com.jme3.math.Vector2f;
import com.simsilica.es.EntityData;
import toniarts.openkeeper.game.component.CreatureSalary;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.logic.PaydaySystem;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.game.task.*;
import toniarts.openkeeper.utils.Point;
import toniarts.openkeeper.utils.WorldUtils;

/** A creature must reach an owned gold room before money can move to its purse. */
public final class CollectPayTask extends AbstractRoomTask {
    private final IGameWorldController world;
    private final EntityData data;
    private boolean executed;

    public CollectPayTask(toniarts.openkeeper.game.task.TaskIdentityAllocator taskIds, INavigationService navigation, IMapController map, Point target, short player,
            IRoomController room, IGameWorldController world, EntityData data) {
        super(taskIds, navigation, map, target, player, room);
        this.world = world;
        this.data = data;
    }

    @Override public boolean isValid(ICreatureController creature) {
        // AbstractTask probes with null when deciding whether an unassigned task can be removed.
        if (creature == null) return false;
        CreatureSalary salary = data.getComponent(creature.getEntityId(), CreatureSalary.class);
        return !executed && creature.getOwnerId() == playerId && PaydaySystem.eligible(data, creature.getEntityId())
                && salary != null && salary.employer == playerId && salary.owed > 0
                && !room.isRemoved() && !room.isDestroyed() && room.getOwnerId() == playerId
                && room.hasObjectControl(ObjectType.GOLD) && world.getGoldAvailableForPayday(playerId, room) > 0;
    }
    @Override public Vector2f getTarget(ICreatureController creature) { return WorldUtils.pointToVector2f(getTaskLocation()); }
    @Override protected ObjectType getRoomObjectType() { return ObjectType.GOLD; }
    @Override public TaskType getTaskType() { return TaskType.COLLECT_PAY; }
    @Override public void executeTask(ICreatureController creature, float duration) {
        if (isValid(creature)) world.collectWages(creature.getEntityId(), room);
        executed = true;
    }
}
