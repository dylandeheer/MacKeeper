/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.task;

import com.simsilica.es.EntityId;
import java.util.Objects;
import java.util.function.Function;
import toniarts.openkeeper.game.controller.entity.IEntityController;

/** A task-to-controller edge that can be linked after cyclic task/creature shells exist. */
public final class TaskTarget<T extends IEntityController> {
    private final EntityId id;
    private T controller;
    private TaskTarget(EntityId id, T controller) {
        if (id == null || id.getId() < 0) throw new IllegalArgumentException("Invalid task target");
        this.id = id;this.controller = controller;
    }
    public static <T extends IEntityController> TaskTarget<T> bound(T controller) {
        Objects.requireNonNull(controller);return new TaskTarget<>(controller.getEntityId(), controller);
    }
    public static <T extends IEntityController> TaskTarget<T> pending(EntityId id) {return new TaskTarget<>(id, null);}
    public EntityId id() {return id;}
    public T get() {
        if (controller == null) throw new IllegalStateException("Task target is not bound");
        return controller;
    }
    public void bind(Function<EntityId, T> resolver) {
        if (controller != null) throw new IllegalStateException("Task target is already bound");
        T resolved = resolver.apply(id);
        if (resolved == null || !id.equals(resolved.getEntityId())) throw new IllegalArgumentException("Missing/mismatched task target");
        controller = resolved;
    }
}
