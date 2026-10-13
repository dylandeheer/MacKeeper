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
package toniarts.openkeeper.game.trigger;

import com.simsilica.es.Entity;
import com.simsilica.es.EntitySet;
import java.util.Map;
import toniarts.openkeeper.game.component.Trigger;
import toniarts.openkeeper.game.controller.IEntityWrapper;
import toniarts.openkeeper.game.controller.entity.IEntityController;
import toniarts.openkeeper.game.logic.IGameLogicUpdatable;

/**
 * A state for handling thing triggers
 *
 * @param <T> the entity controller wrapper
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public abstract class AbstractThingTriggerLogicController<T extends IEntityController> implements IGameLogicUpdatable, TriggerStateSource {
    private final Map<Integer, AbstractThingTriggerControl<T>> thingTriggers;
    private final java.util.List<AbstractThingTriggerControl<T>> triggerControls;
    private final EntitySet entities;
    private final IEntityWrapper<T> entityWrapper;
    private final toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Kind kind;
    private java.util.List<com.simsilica.es.EntityId> pendingObserved;

    protected AbstractThingTriggerLogicController(Map<Integer, AbstractThingTriggerControl<T>> triggers,
            com.simsilica.es.EntityData data, Class<? extends com.simsilica.es.EntityComponent> type, IEntityWrapper<T> wrapper,
            toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Kind kind,
            toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State saved,
            java.util.function.Function<toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Entry,T> bind) {
        this.kind = kind;thingTriggers = triggers;entityWrapper = wrapper;
        if (saved == null) triggerControls = java.util.List.copyOf(new java.util.TreeMap<>(triggers).values());
        else {
            if (saved.kind() != kind || !(data instanceof toniarts.openkeeper.game.save.GameEntityData tracked))
                throw new IllegalArgumentException("Wrong thing-trigger registry");
            for (var id : saved.observed()) if (!tracked.wasAllocated(id)) throw new IllegalArgumentException("Unallocated trigger observation");
            var keys = saved.entries().stream().map(e -> e.tree().triggerId()).collect(java.util.stream.Collectors.toSet());
            if (!keys.equals(triggers.keySet())) throw new IllegalArgumentException("Thing trigger definitions differ");
            for (var entry : saved.entries()) {
                var control = triggers.get(entry.tree().triggerId());
                if (control.getPlayerId() != entry.playerId() || entry.entity() != null && !tracked.wasAllocated(entry.entity()))
                    throw new IllegalArgumentException("Thing trigger owner differs");
                control.validateState(entry.tree());
            }
            var ordered = new java.util.ArrayList<AbstractThingTriggerControl<T>>();
            var instances = new java.util.ArrayList<T>();
            for (var entry : saved.entries()) instances.add(entry.entity() == null ? null : bind.apply(entry));
            for (int i=0;i<saved.entries().size();i++) {
                var entry = saved.entries().get(i);var instance = instances.get(i);
                if (entry.entity() != null && (instance == null || !entry.entity().equals(instance.getEntityId())))
                    throw new IllegalArgumentException("Wrong trigger entity binding");
            }
            for (int i=0;i<saved.entries().size();i++) {
                var entry = saved.entries().get(i);var control = triggers.get(entry.tree().triggerId());
                control.restoreState(entry.tree());control.setThing(instances.get(i));ordered.add(control);
            }
            triggerControls = java.util.List.copyOf(ordered);pendingObserved = saved.observed();
        }
        entities = data.getEntities(type, Trigger.class);
        if (saved == null) processAddedEntities(entities);
    }

    @Override public java.util.List<toniarts.openkeeper.game.save.TriggerSnapshots.State> captureTrees() {
        return triggerControls.stream().map(control -> control.captureState()).toList();
    }
    @Override public toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State captureRegistry() {
        var entries = triggerControls.stream().map(control -> {
            var instance = control.instanceControl;short definition = -1;
            if (instance instanceof toniarts.openkeeper.game.controller.creature.ICreatureController creature) definition = creature.getCreature().getCreatureId();
            else if (instance instanceof toniarts.openkeeper.game.controller.object.ObjectController object) definition = object.captureState().definition();
            else if (instance instanceof toniarts.openkeeper.game.controller.door.DoorController door) definition = door.getDefinitionId();
            else if (instance != null) throw new IllegalStateException("Unsupported trigger controller");
            return toniarts.openkeeper.game.save.TriggerRegistrySnapshots.Entry.thing(control.captureState(), control.getPlayerId(),
                    instance == null ? null : instance.getEntityId(), definition);
        }).toList();
        var observed = pendingObserved == null ? entities.stream().map(Entity::getId).sorted().toList() : pendingObserved;
        return new toniarts.openkeeper.game.save.TriggerRegistrySnapshots.State(kind, entries, observed);
    }
    @Override public void processTick(float tpf) {
        boolean changed = entities.applyChanges();
        if (pendingObserved != null) {
            var old = new java.util.HashSet<>(pendingObserved);
            processAddedEntities(entities.stream().filter(e -> !old.contains(e.getId())).toList());pendingObserved = null;
        } else if (changed) processAddedEntities(entities.getAddedEntities());
        // Removed/changed members intentionally retain their previous wrapper binding.
        for (var control : triggerControls) control.update(tpf);
    }
    private void processAddedEntities(java.util.Collection<Entity> added) {
        for (Entity entity : added.stream().sorted(java.util.Comparator.comparing(Entity::getId)).toList())
            thingTriggers.get(entity.get(Trigger.class).triggerId).setThing(entityWrapper.createController(entity.getId()));
    }
    @Override public void start() { }
    @Override public void stop() { entities.release(); }
}
