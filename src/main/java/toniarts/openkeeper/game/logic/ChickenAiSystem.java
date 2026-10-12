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

import com.google.common.base.Objects;
import com.jme3.util.SafeArrayList;
import com.simsilica.es.Entity;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import com.simsilica.es.EntitySet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Collection;
import java.util.Comparator;
import toniarts.openkeeper.game.controller.ObjectsController;
import toniarts.openkeeper.game.controller.chicken.ChickenController;
import toniarts.openkeeper.game.save.GameEntityData;
import toniarts.openkeeper.game.save.SnapshotEntitySet;
import toniarts.openkeeper.game.component.ChickenAi;
import toniarts.openkeeper.game.component.ObjectViewState;
import toniarts.openkeeper.game.component.Position;
import toniarts.openkeeper.game.controller.IObjectsController;
import toniarts.openkeeper.game.controller.chicken.ChickenState;
import toniarts.openkeeper.game.controller.chicken.IChickenController;

/**
 * Handles chicken logic updates, the chicken AI updates that is. The AI is
 * implemented elsewhere for clarity. This class just attaches the AI to the
 * entity having this component and updates it periodically.
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class ChickenAiSystem implements IGameLogicUpdatable {

    private final EntitySet chickenEntities;
    private final SnapshotEntitySet chickenViewEntities;
    private Set<EntityId> restoredViewIds;
    private Set<EntityId> restoredDirtyViews;
    private boolean reconcileOnNextTick;

    private final SafeArrayList<IChickenController> chickenControllers;
    private final Map<EntityId, IChickenController> chickenControllersByEntityId;
    private final IObjectsController objectsController;

    public record SavedState(List<ChickenController.SavedState> controllers, List<EntityId> viewIds, List<EntityId> dirtyViews) {
        public SavedState {
            controllers = List.copyOf(controllers);viewIds = List.copyOf(viewIds);dirtyViews = List.copyOf(dirtyViews);
            if (controllers.size() > 65_536 || viewIds.size() > 65_536 || dirtyViews.size() > 65_536)
                throw new IllegalArgumentException("Too many chicken observations");
            long previous = -1;
            for (var controller : controllers) {
                if (controller.entityId().getId() <= previous) throw new IllegalArgumentException("Unordered/duplicate chicken controllers");
                previous = controller.entityId().getId();
            }
            if (viewIds.stream().anyMatch(id -> id.getId() < 0) || viewIds.stream().distinct().count() != viewIds.size()
                    || dirtyViews.stream().distinct().count() != dirtyViews.size() || !Set.copyOf(viewIds).containsAll(dirtyViews))
                throw new IllegalArgumentException("Invalid chicken view observations");
        }
    }

    public SavedState captureState() {
        Set<EntityId> views = reconcileOnNextTick ? restoredViewIds : chickenViewEntities.getEntityIds();
        var dirty = new java.util.TreeSet<>(chickenViewEntities.pendingChanges(views));
        if (reconcileOnNextTick) dirty.addAll(restoredDirtyViews);
        return new SavedState(chickenControllers.stream().map(c -> ((ChickenController) c).captureState()).toList(),
                views.stream().sorted().toList(), List.copyOf(dirty));
    }

    public static ChickenAiSystem restoreState(GameEntityData data, ObjectsController objects, SavedState state) {
        for (var id : state.viewIds()) if (!data.wasAllocated(id)) throw new IllegalArgumentException("Unallocated chicken view identity");
        for (var controller : state.controllers()) if (!data.wasAllocated(controller.entityId()))
            throw new IllegalArgumentException("Unallocated chicken controller identity");
        return new ChickenAiSystem(data, objects, state);
    }

    public ChickenAiSystem(EntityData entityData, IObjectsController objectsController) {
        this(entityData, objectsController, null);
    }

    private ChickenAiSystem(EntityData entityData, IObjectsController objectsController, SavedState saved) {
        this.objectsController = objectsController;

        chickenEntities = entityData.getEntities(ChickenAi.class, Position.class);
        chickenViewEntities = new SnapshotEntitySet(entityData, ChickenAi.class, ObjectViewState.class);
        chickenControllers = new SafeArrayList<>(IChickenController.class);
        chickenControllersByEntityId = new HashMap<>();
        if (saved == null) processAddedEntities(chickenEntities);
        else {
            try {
                for (var controller : ((ObjectsController) objectsController).restoreChickenControllers(saved.controllers())) {
                    chickenControllers.add(controller);chickenControllersByEntityId.put(controller.getEntityId(), controller);
                }
                restoredViewIds = Set.copyOf(saved.viewIds());restoredDirtyViews = Set.copyOf(saved.dirtyViews());
                reconcileOnNextTick = true;
            } catch (RuntimeException | Error failure) {chickenEntities.release();chickenViewEntities.release();throw failure;}
        }
    }

    @Override
    public void processTick(float tpf) {
        // Add new & remove old
        boolean changed = chickenEntities.applyChanges();
        if (reconcileOnNextTick) {
            var current = chickenEntities.getEntityIds();
            var removed = chickenControllersByEntityId.keySet().stream().filter(id -> !current.contains(id)).sorted().toList();
            var added = chickenEntities.stream().filter(e -> !chickenControllersByEntityId.containsKey(e.getId())).toList();
            removed.forEach(this::removeController);processAddedEntities(added);
        } else if (changed) {
            chickenEntities.getRemovedEntities().stream().map(Entity::getId).sorted().forEach(this::removeController);
            processAddedEntities(chickenEntities.getAddedEntities());
        }

        // Process ticks
        for (IChickenController creatureController : chickenControllers.getArray()) {
            creatureController.processTick(tpf);
        }

        // This is shorthand for managing also the view state.... Not sure if smart or not
        boolean viewChanged = chickenViewEntities.applyChanges();
        if (reconcileOnNextTick) {
            var dirty = new java.util.HashSet<>(restoredDirtyViews);
            chickenViewEntities.getAddedEntities().forEach(e -> dirty.add(e.getId()));
            chickenViewEntities.getChangedEntities().forEach(e -> dirty.add(e.getId()));
            processViewEntities(chickenViewEntities.stream().filter(e -> !restoredViewIds.contains(e.getId())).toList());
            processViewEntities(chickenViewEntities.stream().filter(e -> restoredViewIds.contains(e.getId()) && dirty.contains(e.getId())).toList());
            reconcileOnNextTick = false;restoredViewIds = null;restoredDirtyViews = null;
        } else if (viewChanged) {
            processViewEntities(chickenViewEntities.getAddedEntities());
            processViewEntities(chickenViewEntities.getChangedEntities());
        }
    }

    private void processViewEntities(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            ChickenAi chickenAi = entity.get(ChickenAi.class);
            ObjectViewState objectViewState = entity.get(ObjectViewState.class);
            ObjectViewState.GameObjectAnimState animState = getAnimStateFromAiState(chickenAi.getChickenState());
            if (!Objects.equal(animState, objectViewState.animState)) {
                entity.set(new ObjectViewState(objectViewState.objectId, objectViewState.state, animState, objectViewState.visible));
            }
        }
    }

    private ObjectViewState.GameObjectAnimState getAnimStateFromAiState(ChickenState chickenState) {
        switch (chickenState) {
            case HATCHING_START: {
                return ObjectViewState.GameObjectAnimState.MESH_RESOURCE;
            }
            case HATCHING_END: {
                return ObjectViewState.GameObjectAnimState.ADDITIONAL_RESOURCE_1;
            }
            case PECKING: {
                return ObjectViewState.GameObjectAnimState.ADDITIONAL_RESOURCE_1;
            }
            case WANDERING: {
                return ObjectViewState.GameObjectAnimState.MESH_RESOURCE;
            }
        }

        return null;
    }

    private void processAddedEntities(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            IChickenController chickenController = objectsController.createChickenController(entity.getId());
            int index = Collections.binarySearch(chickenControllers, chickenController);
            chickenControllers.add(~index, chickenController);
            chickenControllersByEntityId.put(entity.getId(), chickenController);
        }
    }

    private void removeController(EntityId id) {
        IChickenController controller = chickenControllersByEntityId.remove(id);
        if (controller != null) {
            chickenControllers.remove(Collections.binarySearch(chickenControllers, controller));
            controller.getStateMachine().changeState(null);
        }
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {
        chickenEntities.release();
        chickenControllers.clear();
        chickenControllersByEntityId.clear();
        chickenViewEntities.release();
    }

}
