/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.data;

import com.simsilica.es.EntityId;
import java.util.List;
import toniarts.openkeeper.game.controller.creature.CreatureState;
import toniarts.openkeeper.utils.Point;

/** Controller values outside ECS; task node references bind to the separately restored task graph. */
public record CreatureControlState(EntityId entityId, short creatureType,
        CreatureState current, CreatureState previous, CreatureState global,
        float motionless, double nextPayCheck, toniarts.openkeeper.game.save.TaskNodeReferences.Reference failedTask, Point failedStart,
        Point failedTarget, double failedTime, List<HeartPath> heartPaths) {
    public record HeartPath(EntityId roomId, Point start, double time) {
        public HeartPath {
            validId(roomId);
            if (start == null) throw new IllegalArgumentException("Missing failed heart path start");
            validTime(time);
        }
    }
    public CreatureControlState {
        validId(entityId);validTime(nextPayCheck);validTime(failedTime);
        if (creatureType < 0 || creatureType > 255 || !Float.isFinite(motionless) || motionless < 0
                || failedTask != null && (failedStart == null || failedTarget == null))
            throw new IllegalArgumentException("Invalid creature controller state");
        heartPaths = List.copyOf(heartPaths);
        if (heartPaths.size() > 65_536 || heartPaths.stream().map(HeartPath::roomId).distinct().count() != heartPaths.size())
            throw new IllegalArgumentException("Invalid failed heart path index");
    }
    private static void validId(EntityId id) {
        if (id == null || id.getId() < 0) throw new IllegalArgumentException("Invalid entity reference");
    }
    private static void validTime(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid retry time");
    }
}
