/*
 * Copyright (C) 2014-2017 OpenKeeper
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
package toniarts.openkeeper.game.task.objective;

import com.jme3.math.Vector2f;
import com.simsilica.es.EntityId;
import toniarts.openkeeper.utils.Point;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.task.Task;
import toniarts.openkeeper.game.task.TaskType;

/**
 * A decorator for some simple task to create complex task chains
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public class ObjectiveTaskDecorator implements ObjectiveTask {

    private final long taskId;
    private Task task;
    private final boolean workerPartyTask;
    private final Deque<ObjectiveTask> taskQueue = new ArrayDeque<>();

    public ObjectiveTaskDecorator(long originalTaskId, Task task) {
        this(originalTaskId, task, false);
    }

    public ObjectiveTaskDecorator(long originalTaskId, Task task, boolean workerPartyTask) {
        this(originalTaskId, workerPartyTask);this.task = java.util.Objects.requireNonNull(task);
    }

    private ObjectiveTaskDecorator(long taskId, boolean workerPartyTask) {
        if (taskId < 0) throw new IllegalArgumentException("Invalid objective task ID");
        this.taskId = taskId;this.workerPartyTask = workerPartyTask;
    }

    public static ObjectiveTaskDecorator restoreShell(long taskId, boolean workerPartyTask) {
        return new ObjectiveTaskDecorator(taskId, workerPartyTask);
    }

    public void bindRestoredTask(Task task) {
        if (this.task != null) throw new IllegalStateException("Objective wrapper is already bound");
        if (task == null || task == this) throw new IllegalArgumentException("Invalid wrapped task");
        this.task = task;
    }

    public Task getWrappedTask() {
        if (task == null) throw new IllegalStateException("Objective wrapper is not bound");
        return task;
    }

    @Override public boolean isWorkerPartyTask() {return workerPartyTask;}

    @Override
    public Deque<ObjectiveTask> getTaskQueue() {
        return taskQueue;
    }

    @Override
    public void executeTask(ICreatureController creature, float executionDuration) {
        getWrappedTask().executeTask(creature, executionDuration);
        ObjectiveTask.super.executeTask(creature, executionDuration);
    }

    @Override
    public boolean canExecute(ICreatureController creature) {
        return true;
    }

    @Override
    public void assign(ICreatureController creature, boolean setToCreature) {
        getWrappedTask().assign(creature, false);

        // Override the assign
        creature.setAssignedTask(this);
        if (isWorkerPartyTask() && creature.getParty() != null) {

            // Assign to all workers
            for (ICreatureController c : creature.getParty().getActualMembers()) {
                if (!c.equals(creature) && c.isWorker() && getWrappedTask().canAssign(c)) {
                    getWrappedTask().assign(c, true);
                }
            }
        }
    }

    @Override
    public boolean canAssign(ICreatureController creature) {
        return getWrappedTask().canAssign(creature);
    }

    @Override
    public int getAssigneeCount() {
        return getWrappedTask().getAssigneeCount();
    }

    @Override
    public int getMaxAllowedNumberOfAsignees() {
        return getWrappedTask().getMaxAllowedNumberOfAsignees();
    }

    @Override
    public int getPriority() {
        return getWrappedTask().getPriority();
    }

    @Override
    public Vector2f getTarget(ICreatureController creature) {
        return getWrappedTask().getTarget(creature);
    }

    @Override
    public Instant getTaskCreated() {
        return getWrappedTask().getTaskCreated();
    }

    @Override
    public Point getTaskLocation() {
        return getWrappedTask().getTaskLocation();
    }

    @Override
    public boolean isFaceTarget() {
        return getWrappedTask().isFaceTarget();
    }

    @Override
    public boolean isReachable(ICreatureController creature) {
        return getWrappedTask().isReachable(creature);
    }

    @Override
    public boolean isValid(ICreatureController creature) {
        return getWrappedTask().isValid(creature);
    }

    @Override
    public void unassign(ICreatureController creature) {
        getWrappedTask().unassign(creature);
    }

    @Override
    public boolean isRemovable() {
        return getWrappedTask().isRemovable();
    }

    @Override
    public TaskType getTaskType() {
        return getWrappedTask().getTaskType();
    }

    @Override
    public EntityId getTaskTarget() {
        return getWrappedTask().getTaskTarget();
    }

    @Override
    public long getId() {
        return taskId;
    }

    @Override
    public boolean isFull() {
        return getWrappedTask().isFull();
    }

}
