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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import toniarts.openkeeper.game.component.CreatureAi;
import toniarts.openkeeper.game.controller.ICreaturesController;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.task.ITaskManager;

/**
 * Handles creature logic updates, the creature AI updates that is. The AI is implemented elsewhere for
 * clarity. This class just attaches the AI to the entity having this component and updates it periodically.
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class CreatureAiSystem implements IGameLogicUpdatable {

    private final EntitySet creatureEntities;
    private boolean reconcileOnNextTick;

    private final SafeArrayList<ICreatureController> creatureControllers;
    private final Map<EntityId, ICreatureController> creatureControllersByEntityId;
    private final ICreaturesController creaturesController;
    private final ITaskManager taskManager;

    public record SavedState(java.util.List<EntityId> activeControllers) {
        public SavedState {
            activeControllers = java.util.List.copyOf(activeControllers);
            if (activeControllers.size() > 65_536) throw new IllegalArgumentException("Too many active creatures");
            long previous = -1;
            for (var id : activeControllers) {
                if (id.getId() <= previous) throw new IllegalArgumentException("Invalid active creature ordering");
                previous = id.getId();
            }
        }
    }

    public SavedState captureState() {
        return new SavedState(creatureControllers.stream().map(ICreatureController::getEntityId).toList());
    }

    /** The factory must already contain every saved controller; never create or enter states here. */
    public static CreatureAiSystem restoreState(toniarts.openkeeper.game.save.GameEntityData data,
            toniarts.openkeeper.game.controller.CreaturesController creatures, ITaskManager tasks, SavedState saved) {
        var controllers = new java.util.ArrayList<ICreatureController>();
        for (var id : saved.activeControllers()) {
            if (!data.wasAllocated(id)) throw new IllegalArgumentException("Unallocated active creature");
            controllers.add(creatures.getBoundController(id));
        }
        return new CreatureAiSystem(data, creatures, tasks, controllers);
    }

    public CreatureAiSystem(EntityData entityData, ICreaturesController creaturesController, ITaskManager taskManager) {
        this(entityData, creaturesController, taskManager, null);
    }

    private CreatureAiSystem(EntityData entityData, ICreaturesController creaturesController, ITaskManager taskManager,
            java.util.List<ICreatureController> restored) {
        this.creaturesController = creaturesController;
        this.taskManager = taskManager;
        creatureEntities = entityData.getEntities(CreatureAi.class);
        creatureControllers = new SafeArrayList<>(ICreatureController.class);
        creatureControllersByEntityId = new HashMap<>();
        if (restored == null) processAddedEntities(creatureEntities);
        else {
            restored.forEach(c -> {creatureControllers.add(c);creatureControllersByEntityId.put(c.getEntityId(), c);});
            reconcileOnNextTick = true;
        }
    }

    @Override
    public void processTick(float tpf) {
        // Add new & remove old
        boolean changed = creatureEntities.applyChanges();
        if (reconcileOnNextTick) {
            var current = creatureEntities.getEntityIds();
            creatureControllersByEntityId.keySet().stream().filter(id -> !current.contains(id)).sorted().toList().forEach(this::removeController);
            processAddedEntities(creatureEntities.stream().filter(e -> !creatureControllersByEntityId.containsKey(e.getId())).toList());
            reconcileOnNextTick = false;
        } else if (changed) {
            creatureEntities.getRemovedEntities().stream().map(Entity::getId).sorted().forEach(this::removeController);
            processAddedEntities(creatureEntities.getAddedEntities());
        }

        // Process ticks
        for (ICreatureController creatureController : creatureControllers.getArray()) {
            creatureController.processTick(tpf);
        }

        // We have a specialty here, process creature worker queue
        taskManager.processUnemployedWorkerQueue();
    }

    private void processAddedEntities(java.util.Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(java.util.Comparator.comparing(Entity::getId)).toList()) {
            ICreatureController creatureController = creaturesController.createController(entity.getId());
            int index = Collections.binarySearch(creatureControllers, creatureController);
            creatureControllers.add(~index, creatureController);
            creatureControllersByEntityId.put(entity.getId(), creatureController);
        }
    }

    private void removeController(EntityId id) {
        ICreatureController controller = creatureControllersByEntityId.remove(id);
        if (controller != null) {
            creatureControllers.remove(Collections.binarySearch(creatureControllers, controller));
            controller.getStateMachine().changeState(null);
        }
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {
        creatureEntities.release();
        creatureControllers.clear();
        creatureControllersByEntityId.clear();
    }

}
