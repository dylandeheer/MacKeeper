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
package toniarts.openkeeper.game.navigation.steering;

import com.badlogic.gdx.ai.steer.behaviors.FollowPath;
import com.badlogic.gdx.ai.steer.behaviors.ReachOrientation;
import com.badlogic.gdx.ai.steer.utils.paths.LinePath;
import com.badlogic.gdx.math.Vector2;
import toniarts.openkeeper.utils.Point;
import java.util.List;
import toniarts.openkeeper.utils.WorldUtils;

/**
 * A steering factory, static methods for constructing steerings for entities
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class EntitySteeringFactory {

    private EntitySteeringFactory() {
    }

    /** Immutable geometry used by the active behavior, independently of later Navigation writes. */
    public record Waypoint(float x, float y) {
        public Waypoint {
            if (!Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalArgumentException("Invalid waypoint");
        }
        public Vector2 vector() { return new Vector2(x, y); }
    }

    public record Plan(List<Waypoint> path, Float facing) {
        public Plan {
            path = List.copyOf(path);
            if (path.size() > 65536 || (facing != null && !Float.isFinite(facing))) throw new IllegalArgumentException("Invalid steering plan");
        }
        public boolean active() { return path.size() > 1 || facing != null; }
    }

    public static Plan plan(List<Vector2> path, Point faceTarget, Point target) {
        Float facing = faceTarget == null ? null : new TargetLocation(WorldUtils.pointToVector2(faceTarget),
                WorldUtils.pointToVector2(target)).getOrientation();
        return new Plan(path.stream().map(p -> new Waypoint(p.x, p.y)).toList(), facing);
    }

    public static EntitySteeringBehavior navigateToPoint(List<Vector2> path, Point faceTarget, ISteerableEntity steerable, Point target) {
        return navigate(plan(path, faceTarget, target), steerable);
    }

    public static EntitySteeringBehavior navigate(Plan plan, ISteerableEntity steerable) {
        if (!plan.active()) return null;
        var priority = new EntitySteeringBehavior(steerable);
        if (plan.path().size() > 1) {
            // Private vectors keep LinePath geometry and its cached segment lengths consistent.
            var path = plan.path().stream().map(Waypoint::vector).toList();
            var follow = new FollowPath<Vector2, LinePath.LinePathParam>(steerable,
                    new LinePath<>(SteeringUtils.pathToArray(path), true), 0.2f);
            follow.setDecelerationRadius(0.2f);
            follow.setArrivalTolerance(0.1f);
            priority.add(follow);
        }
        if (plan.facing() != null) {
            var orient = new ReachOrientation<Vector2>(steerable, new TargetLocation(plan.facing()));
            orient.setDecelerationRadius(0.2f);
            orient.setTimeToTarget(0.001f);
            orient.setAlignTolerance(0.1f);
            priority.add(orient);
        }
        // In gdx-ai 1.8.2 the selected child, path parameter, internal target and
        // acceleration are overwritten before use each calculation. They are scratch,
        // not continuation state. Recheck this contract when upgrading gdx-ai.
        return priority;
    }
}
