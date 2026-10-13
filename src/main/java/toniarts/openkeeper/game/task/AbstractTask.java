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
package toniarts.openkeeper.game.task;

import com.jme3.math.Vector2f;
import com.simsilica.es.EntityId;
import toniarts.openkeeper.utils.Point;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.navigation.INavigationService;
import toniarts.openkeeper.utils.WorldUtils;

/**
 * Base class for all tasks
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public abstract class AbstractTask implements Task {

    private static final Logger logger = System.getLogger(AbstractTask.class.getName());
    
    protected final TaskIdentityAllocator taskIds;
    private Map<com.simsilica.es.EntityId, Float> pendingAssignees;

    private final long id;
    private final Instant taskCreated;
    protected final INavigationService navigationService;
    protected final IMapController mapController;
    private final Map<ICreatureController, Float> assignees = new HashMap<>();

    public AbstractTask(TaskIdentityAllocator taskIds, final INavigationService navigationService, final IMapController mapController) {
        this(taskIds, taskIds.allocate(), navigationService, mapController);
    }

    private AbstractTask(TaskIdentityAllocator taskIds, TaskIdentityAllocator.Identity identity,
            INavigationService navigationService, IMapController mapController) {
        this.taskIds = taskIds;this.id = identity.id();this.taskCreated = identity.created();
        this.navigationService = navigationService;this.mapController = mapController;
    }

    protected AbstractTask(TaskIdentityAllocator taskIds, toniarts.openkeeper.game.data.TaskBaseState saved,
            INavigationService navigationService, IMapController mapController) {
        this(taskIds, saved.identity(), navigationService, mapController);
        taskIds.validateRestored(saved.identity());pendingAssignees = saved.assignees();
    }

    public toniarts.openkeeper.game.data.TaskBaseState captureBaseState() {
        requireBound();
        var values = new java.util.TreeMap<com.simsilica.es.EntityId, Float>();
        assignees.forEach((creature, duration) -> {
            if (values.put(creature.getEntityId(), duration) != null) throw new IllegalStateException("Aliased task assignee");
        });
        return new toniarts.openkeeper.game.data.TaskBaseState(new TaskIdentityAllocator.Identity(id, taskCreated), values);
    }

    /** Link task shells after creature controllers exist, without assign() callbacks or duration reset. */
    public void bindRestoredAssignees(java.util.function.Function<com.simsilica.es.EntityId, ICreatureController> creatures) {
        if (pendingAssignees == null) throw new IllegalStateException("Task is not awaiting assignee binding");
        var bound = new HashMap<ICreatureController, Float>();
        pendingAssignees.forEach((id, duration) -> {
            var creature = creatures.apply(id);
            if (creature == null || !id.equals(creature.getEntityId()) || bound.put(creature, duration) != null)
                throw new IllegalArgumentException("Missing/mismatched task assignee");
        });
        assignees.putAll(bound);pendingAssignees = null;
    }

    private void requireBound() {
        if (pendingAssignees != null) throw new IllegalStateException("Task assignees are not bound");
    }

    @Override
    public long getId() {
        return id;
    }

    /**
     * Get the non-stop execution duration of a creature
     *
     * @param creature the executing creature
     * @return the execution duration
     */
    protected float getExecutionDuration(ICreatureController creature) {
        requireBound();
        return assignees.get(creature);
    }

    /**
     * Get the non-stop execution duration of a creature
     *
     * @param creature the executing creature
     * @param duration the execution duration to store
     */
    protected void setExecutionDuration(ICreatureController creature, float duration) {
        requireBound();
        assignees.put(creature, duration);
    }

    @Override
    public Instant getTaskCreated() {
        return taskCreated;
    }

    @Override
    public int getMaxAllowedNumberOfAsignees() {
        return 1;
    }

    @Override
    public void assign(ICreatureController creature, boolean setToCreature) {
        requireBound();
        if (assignees.size() == getMaxAllowedNumberOfAsignees()) {
            logger.log(Level.WARNING, "Task already has the maximum number of assignees!");
        }
        assignees.put(creature, 0.0f);
        if (setToCreature) {
            creature.setAssignedTask(this);
        }
    }

    @Override
    public void unassign(ICreatureController creature) {
        requireBound();
        assignees.remove(creature);
    }

    @Override
    public int getAssigneeCount() {
        requireBound();
        return assignees.size();
    }

    @Override
    public boolean isFull() {
        return getAssigneeCount() >= getMaxAllowedNumberOfAsignees();
    }

    @Override
    public boolean canAssign(ICreatureController creature) {
        return (!isFull() && isValid(creature) && isReachable(creature));
    }

    @Override
    public int getPriority() {
        return 100;
    }

    public int compareTo(AbstractTask t) {
        int order = getTaskCreated().compareTo(t.getTaskCreated());
        return order != 0 ? order : Long.compare(getId(), t.getId());
    }

    @Override
    public boolean isReachable(ICreatureController creature) {
        Vector2f target = getTarget(creature);
        if (target != null) {
            return isReachable(creature, target);
        }
        return false;
    }

    /**
     * Is the task reachable by the given creature. Ask this last if determining
     * validity etc. As the method might be heavy
     *
     * @param creature the creature trying to reach this
     * @param target the target location
     * @return is the task reachable
     */
    protected boolean isReachable(ICreatureController creature, Vector2f target) {
        Point targetTile = WorldUtils.vectorToPoint(target);
        boolean hasAccessibleNeighbour = false;
        for (Point p : WorldUtils.getSurroundingTiles(mapController.getMapData(), targetTile, false)) {
            if (navigationService.isAccessible(mapController.getMapData().getTile(p), mapController.getMapData().getTile(targetTile), creature)) {
                hasAccessibleNeighbour = true;
                break; // At least one accessible point
            }
        }
        if (!hasAccessibleNeighbour) {
            return false;
        }

        // Path find
        return (navigationService.findPath(WorldUtils.vectorToPoint(creature.getPosition()), targetTile, creature) != null);
    }

    /**
     * Tries to find a accessible target, a tile next to a tile where the task
     * is supposed to happen
     *
     * @param creature the creature trying to reach this
     * @return task location next to the wanted tile
     */
    protected Vector2f getAccessibleTargetNextToLocation(ICreatureController creature) {
        List<Point> accessibleTiles = new ArrayList<>();
        for (Point taskPerformLocation : WorldUtils.getSurroundingTiles(mapController.getMapData(), getTaskLocation(), false)) {
            for (Point p : WorldUtils.getSurroundingTiles(mapController.getMapData(), getTaskLocation(), false)) {
                if (p.equals(getTaskLocation())) {
                    continue;
                }

                if (!navigationService.isAccessible(mapController.getMapData().getTile(p), mapController.getMapData().getTile(taskPerformLocation), creature)) {
                    continue;
                }

                accessibleTiles.add(p);
            }
        }

        if (accessibleTiles.isEmpty()) {
            return null;
        }

        // Sort by manhattan distance to the creature and get the first reachable one
        Point startingPoint = WorldUtils.vectorToPoint(creature.getPosition());
        accessibleTiles.sort((o1, o2) -> {
            return Integer.compare(WorldUtils.calculateDistance(startingPoint, o1), WorldUtils.calculateDistance(startingPoint, o2));
        });
        for (Point p : accessibleTiles) {
            Vector2f target = new Vector2f(p.x, p.y);
            if (isReachable(creature, target)) {
                return target;
            }
        }

        return null;
    }

    @Override
    public boolean isFaceTarget() {
        return false;
    }

    @Override
    public boolean isRemovable() {
        return !isValid(null);
    }

    @Override
    public EntityId getTaskTarget() {
        return null;
    }

    @Override
    public boolean canExecute(ICreatureController creature) {
        return true;
    }

}
