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
package toniarts.openkeeper.game.controller.player;

import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import toniarts.openkeeper.game.component.CreatureComponent;
import toniarts.openkeeper.game.component.InHand;
import toniarts.openkeeper.game.component.ObjectComponent;
import toniarts.openkeeper.game.component.ViewType;
import toniarts.openkeeper.game.data.Keeper;

/**
 * Fixed size keeper hand LIFO queue (a.k.a. stack), optimized for performance
 * and simplicity :)
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class PlayerHandControl {

    private final EntityId[] stack;
    private final EntityData entityData;
    private final Keeper keeper;
    private final int maxCount;
    private int count = 0;

    /**
     * Creates a new queue
     *
     * @param keeper whose hand this is
     * @param size the fixed size of this queue
     * @param entityData for handling the entities
     */
    public PlayerHandControl(Keeper keeper, int size, EntityData entityData) {
        this.keeper = keeper;
        this.stack = new EntityId[size];
        this.entityData = entityData;
        this.maxCount = size;
    }

    /** Immutable bottom-to-top order, captured under the simulation boundary. */
    public record State(short playerId, int capacity, List<EntityId> bottomToTop) {
        public static final int MAX_CAPACITY = 65_536;

        public State {
            bottomToTop = List.copyOf(bottomToTop);
            if (playerId < 0 || playerId > 255 || capacity < 0 || capacity > MAX_CAPACITY
                    || bottomToTop.size() > capacity || new HashSet<>(bottomToTop).size() != bottomToTop.size()
                    || bottomToTop.stream().anyMatch(id -> id.getId() < 0)) {
                throw new IllegalArgumentException("Invalid saved hand");
            }
        }
    }

    public State captureState() {
        List<EntityId> entities = new ArrayList<>(count);
        for (int i = 0; i < count; i++) entities.add(stack[i]);
        return new State(keeper.getId(), maxCount, entities);
    }

    /**
     * Bind to an independently restored entity store, without replaying pickups.
     * Validates the complete hand/component relationship before returning a hand;
     * neither successful nor rejected restoration writes entity components.
     */
    public static PlayerHandControl restoreState(Keeper keeper, EntityData data, State state) {
        if (keeper.getId() != state.playerId()) throw new IllegalArgumentException("Hand owner mismatch");
        var ids = new HashSet<>(state.bottomToTop());
        for (int i = 0; i < state.bottomToTop().size(); i++) {
            EntityId id = state.bottomToTop().get(i);
            InHand hand = data.getComponent(id, InHand.class);
            CreatureComponent creature = data.getComponent(id, CreatureComponent.class);
            ObjectComponent object = data.getComponent(id, ObjectComponent.class);
            if (hand == null || hand.playerId != state.playerId() || hand.index != state.capacity() - i - 1
                    || (creature == null) == (object == null)
                    || hand.type != (creature != null ? ViewType.CREATURE : ViewType.OBJECT)
                    || hand.id != (creature != null ? creature.creatureId : object.objectId)) {
                throw new IllegalArgumentException("Saved hand does not match entity " + id);
            }
        }
        var carried = data.getEntities(InHand.class);
        try {
            for (var entity : carried) {
                if (entity.get(InHand.class).playerId == state.playerId() && !ids.contains(entity.getId())) {
                    throw new IllegalArgumentException("Saved hand omits carried entity " + entity.getId());
                }
            }
        } finally {
            carried.release();
        }
        PlayerHandControl result = new PlayerHandControl(keeper, state.capacity(), data);
        for (EntityId id : state.bottomToTop()) result.stack[result.count++] = id;
        return result;
    }

    /**
     * Adds an entity to the front of the queue
     *
     * @param entity the entity to add to the queue
     * @throws IllegalStateException if the queue is already full
     * @throws NullPointerException this queue doesn't accept {@code null}
     * elements
     */
    public void push(EntityId entity) throws IllegalStateException {

        // Don't allow to push if full
        if (isFull()) {
            throw new IllegalStateException("Queue is already full!");
        }

        // Advance counters & set the object
        stack[count] = entity;
        count++;

        // Add the entity
        ViewType viewType = ViewType.CREATURE;
        short id = 0;
        CreatureComponent creatureComponent = entityData.getComponent(entity, CreatureComponent.class);
        if (creatureComponent != null) {
            id = creatureComponent.creatureId;
        } else {
            ObjectComponent objectComponent = entityData.getComponent(entity, ObjectComponent.class);
            viewType = ViewType.OBJECT;
            id = objectComponent.objectId;
        }
        entityData.setComponent(entity, new InHand(maxCount - count, keeper.getId(), viewType, id));
    }

    /**
     * Gets the last inserted entity and removes it from the queue
     *
     * @return the last inserted entity, or {@code null} if the queue is empty
     */
    public EntityId pop() {

        // Null if empty
        if (isEmpty()) {
            return null;
        }

        // Get the object and clear the reference
        EntityId entity = stack[count - 1];
        stack[count - 1] = null;
        count--;
        entityData.removeComponent(entity, InHand.class);

        return entity;
    }

    /**
     * Gets the last inserted entity but doesn't remove it from the queue
     *
     * @return the last inserted entity, or {@code null} if the queue is empty
     */
    public EntityId peek() {
        return peek(0);
    }

    /**
     * Gets the inserted entity by id from top but doesn't remove it from the
     * queue
     *
     * @param i index of entity from the top
     * @return the entity object by id from top, or {@code null} if the queue is
     * not long
     */
    public EntityId peek(final int i) {
        return count - 1 - i < 0 ? null : stack[count - 1 - i];
    }

    /**
     * Get the queue object count
     *
     * @return the object count
     */
    public int getCount() {
        return count;
    }

    /**
     * See if the queue is full
     *
     * @return {@code true} if the queue is full
     */
    public boolean isFull() {
        return (count == stack.length);
    }

    /**
     * See if the queue is empty
     *
     * @return {@code true} if the queue is empty
     */
    public boolean isEmpty() {
        return (count == 0);
    }

}
