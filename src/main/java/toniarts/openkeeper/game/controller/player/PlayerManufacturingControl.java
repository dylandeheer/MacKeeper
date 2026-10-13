/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.controller.player;

import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import java.util.List;
import java.util.Objects;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.tools.convert.map.GameObject;
import toniarts.openkeeper.utils.Point;

/**
 * One authoritative FIFO per keeper, shared by all of that keeper's workshops.
 * Call only inside the simulation boundary. The ECS owns orders and progress;
 * rebinding this controller during restoration does not enqueue or advance work.
 */
public final class PlayerManufacturingControl {
    public static final int MAX_ORDERS = 128;
    private final short playerId;
    private final IKwdFile definitions;
    private final EntityData data;

    /** Detached queue entry. Locations are tile coordinates, not entity references. */
    public record Order(EntityId id, boolean trap, short typeId, int requiredWork,
            int completedWork, Point location, short crateObjectId) {
        public Order { location = new Point(location.x, location.y); }
        @Override public Point location() { return new Point(location.x, location.y); }
        public boolean isComplete() { return completedWork == requiredWork; }
    }

    public PlayerManufacturingControl(short playerId, IKwdFile definitions, EntityData data) {
        if (playerId < 0 || playerId > 255) throw new IllegalArgumentException("Invalid manufacture owner");
        this.playerId = playerId;
        this.definitions = Objects.requireNonNull(definitions);
        this.data = Objects.requireNonNull(data);
        validateState();
    }

    /** Reject orphan orders before restored player controllers are bound. */
    public static void validateOwners(EntityData data, java.util.Set<Short> players) {
        for (EntityId id : data.findEntities(null, ManufacturingOrder.class)) {
            short owner = data.getComponent(id, ManufacturingOrder.class).playerId;
            if (owner < 0 || owner > 255 || !players.contains(owner))
                throw new IllegalArgumentException("Manufacturing order references an absent keeper");
        }
    }

    public List<Order> getOrders() {
        return ids().stream().map(this::order).toList();
    }

    public boolean hasWork() { return !ids().isEmpty(); }

    /**
     * Called once after a new blueprint has entered the world. Existing owned
     * matching crates satisfy demand without creating another production order.
     * False means admission failed; the placement transaction must roll back.
     */
    public boolean request(boolean trap, short typeId, Point location) {
        Definition definition = definition(trap, typeId);
        // Native admission rejects a full queue even when stock could satisfy demand.
        if (definition == null || location == null || location.x < 0 || location.y < 0
                || ids().size() >= MAX_ORDERS) return false;
        int demand = blueprintCount(trap, typeId);
        if (demand == 0) return false;
        if (demand <= crateCount(definition.crateObjectId())) return true;
        EntityId id = data.createEntity();
        data.setComponent(id, new ManufacturingOrder(playerId, trap, typeId,
                definition.requiredWork(), 0, location));
        return true;
    }

    /** Remove the first matching order, even if a later same-type blueprint was sold. */
    public boolean cancel(boolean trap, short typeId) {
        for (EntityId id : ids()) {
            ManufacturingOrder order = data.getComponent(id, ManufacturingOrder.class);
            if (order.trap == trap && order.typeId == typeId) {
                // Work belongs only to the head. Removing it discards that work;
                // removing a later order leaves the current work untouched.
                data.removeEntity(id);
                return true;
            }
        }
        return false;
    }

    /**
     * Add creature work to the head. A completed head stays queued until a crate
     * can be stored. Surplus work never spills into the next order.
     */
    public Order contribute(int work) {
        if (work < 0) throw new IllegalArgumentException("Negative manufacturing work");
        var ids = ids();
        if (ids.isEmpty()) return null;
        EntityId id = ids.getFirst();
        ManufacturingOrder current = data.getComponent(id, ManufacturingOrder.class);
        int completed = (int) Math.min(current.requiredWork, (long) current.completedWork + work);
        if (completed != current.completedWork) data.setComponent(id, new ManufacturingOrder(playerId,
                current.trap, current.typeId, current.requiredWork, completed, current.location));
        return order(id);
    }

    /**
     * Commit a successfully stored crate while still inside the simulation
     * transaction. Stale/repeated completions cannot remove the next order.
     */
    public boolean complete(EntityId expectedHead) {
        var ids = ids();
        if (ids.isEmpty() || !ids.getFirst().equals(expectedHead)) return false;
        ManufacturingOrder current = data.getComponent(expectedHead, ManufacturingOrder.class);
        if (current.completedWork != current.requiredWork) return false;
        data.removeEntity(expectedHead);
        return true;
    }

    private List<EntityId> ids() {
        return data.findEntities(null, ManufacturingOrder.class).stream()
                .filter(id -> data.getComponent(id, ManufacturingOrder.class).playerId == playerId)
                .sorted().toList();
    }

    private Order order(EntityId id) {
        ManufacturingOrder value = data.getComponent(id, ManufacturingOrder.class);
        Definition definition = definition(value.trap, value.typeId);
        return new Order(id, value.trap, value.typeId, value.requiredWork,
                value.completedWork, value.location, definition.crateObjectId());
    }

    private void validateState() {
        var orders = ids();
        if (orders.size() > MAX_ORDERS) throw new IllegalArgumentException("Manufacturing queue exceeds capacity");
        for (int index = 0; index < orders.size(); index++) {
            ManufacturingOrder value = data.getComponent(orders.get(index), ManufacturingOrder.class);
            Definition definition = definition(value.trap, value.typeId);
            if (definition == null || value.requiredWork != definition.requiredWork()
                    || value.completedWork < 0 || value.completedWork > value.requiredWork
                    || index > 0 && value.completedWork != 0 || value.location == null
                    || value.location.x < 0 || value.location.y < 0) {
                throw new IllegalArgumentException("Invalid saved manufacturing order");
            }
        }
    }

    private int blueprintCount(boolean trap, short typeId) {
        int count = 0;
        for (EntityId id : data.findEntities(null, Owner.class, trap ? TrapComponent.class : DoorComponent.class)) {
            if (data.getComponent(id, Owner.class).ownerId != playerId) continue;
            if (trap) {
                if (data.getComponent(id, DoorComponent.class) != null) continue;
                TrapViewState state = data.getComponent(id, TrapViewState.class);
                if (data.getComponent(id, TrapComponent.class).trapId == typeId && state != null && state.blueprint) count++;
            } else {
                DoorComponent state = data.getComponent(id, DoorComponent.class);
                if (state.doorId == typeId && state.blueprint) count++;
            }
        }
        return count;
    }

    private int crateCount(short objectId) {
        int count = 0;
        for (EntityId id : data.findEntities(null, Owner.class, ObjectComponent.class)) {
            if (data.getComponent(id, Owner.class).ownerId == playerId
                    && data.getComponent(id, ObjectComponent.class).objectId == objectId) count++;
        }
        return count;
    }

    private record Definition(int requiredWork, short crateObjectId) { }
    private Definition definition(boolean trap, short typeId) {
        if (typeId <= 0 || typeId > 255) return null;
        int work;short crate;
        if (trap) {
            var value = definitions.getTrapById(typeId);
            if (value == null) return null;
            work = value.getManufToBuild();crate = value.getManufCrateObjectId();
        } else {
            var value = definitions.getDoorById(typeId);
            if (value == null) return null;
            work = value.getManufToBuild();crate = value.getManufCrateObjectId();
        }
        var object = definitions.getObject(crate);
        if (work < 0 || work > 65535 || object == null
                || !object.getFlags().contains(GameObject.ObjectFlag.OBJECT_TYPE_CRATE)) return null;
        return new Definition(work, crate);
    }
}
