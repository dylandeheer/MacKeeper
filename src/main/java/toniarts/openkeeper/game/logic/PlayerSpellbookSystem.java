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

import com.simsilica.es.Entity;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import com.simsilica.es.EntitySet;
import com.simsilica.es.filter.FieldFilter;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Comparator;
import toniarts.openkeeper.game.data.ResearchableType;
import toniarts.openkeeper.game.save.GameEntityData;
import toniarts.openkeeper.game.component.Owner;
import toniarts.openkeeper.game.component.Stored;
import toniarts.openkeeper.game.component.Spellbook;
import toniarts.openkeeper.game.controller.IPlayerController;
import toniarts.openkeeper.game.controller.player.AbstractResearchablePlayerControl;
import toniarts.openkeeper.game.controller.room.AbstractRoomController;
import toniarts.openkeeper.game.data.IIndexable;
import toniarts.openkeeper.tools.convert.map.IKwdFile;

/**
 * Basically just reacts to stored spellbooks
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class PlayerSpellbookSystem implements IGameLogicUpdatable {

    private final IKwdFile kwdFile;
    private final EntitySet spellbookEntities;
    private final Map<Short, IPlayerController> playerControllersByPlayerId;
    private final Map<EntityId, Entry> observed = new HashMap<>();
    private boolean reconcileOnNextTick;

    /** Last values observed by this system, possibly older than the current ECS. */
    public record Entry(EntityId entityId, short ownerId, ResearchableType type, short typeId) {
        public Entry {
            if (entityId == null || entityId.getId() < 0 || ownerId < 0 || ownerId > 255
                    || type == null || typeId < 0 || typeId > 255)
                throw new IllegalArgumentException("Invalid observed spellbook");
        }
        private Spellbook spellbook() { return new Spellbook(type, typeId); }
        private static Entry from(Entity entity) {
            var book = entity.get(Spellbook.class);
            return new Entry(entity.getId(), entity.get(Owner.class).ownerId, book.type, book.typeId);
        }
    }

    public record State(List<Entry> entries) {
        public State {
            entries = List.copyOf(entries);
            if (entries.size() > 65_536 || entries.stream().map(Entry::entityId).distinct().count() != entries.size())
                throw new IllegalArgumentException("Invalid observed spellbook index");
        }
    }

    public State captureState() {
        return new State(observed.values().stream().sorted(Comparator.comparing(Entry::entityId)).toList());
    }

    /** Bind observations without discovering/upgrading research a second time. */
    public static PlayerSpellbookSystem restoreState(GameEntityData data, IKwdFile definition,
            Collection<IPlayerController> players, State state) {
        for (var entry : state.entries()) {
            if (!data.wasAllocated(entry.entityId()) || getResearchableType(definition, entry.spellbook()) == null)
                throw new IllegalArgumentException("Observed spellbook reference/definition missing");
        }
        var ids = new java.util.HashSet<Short>();
        for (var player : players) if (!ids.add(player.getKeeper().getId()))
            throw new IllegalArgumentException("Duplicate player controller");
        return new PlayerSpellbookSystem(data, definition, players, state);
    }

    public PlayerSpellbookSystem(EntityData entityData, IKwdFile kwdFile, Collection<IPlayerController> playerControllers) {
        this(entityData, kwdFile, playerControllers, null);
    }

    private PlayerSpellbookSystem(EntityData entityData, IKwdFile kwdFile, Collection<IPlayerController> playerControllers, State saved) {
        this.kwdFile = kwdFile;
        playerControllersByPlayerId = HashMap.newHashMap(playerControllers.size());
        for (IPlayerController playerController : playerControllers) {
            if (playerController.getResearchControl() != null) {
                playerControllersByPlayerId.put(playerController.getKeeper().getId(), playerController);
            }
        }

        spellbookEntities = entityData.getEntities(new FieldFilter(Stored.class, "objectType", AbstractRoomController.ObjectType.SPELL_BOOK), Spellbook.class, Stored.class, Owner.class);
        if (saved == null) processAddedEntities(spellbookEntities);
        else {
            for (var entry : saved.entries()) observed.put(entry.entityId(), entry);
            reconcileOnNextTick = true;
        }
    }

    @Override
    public void processTick(float tpf) {
        boolean changed = spellbookEntities.applyChanges();
        if (reconcileOnNextTick) {
            // Partition before callbacks mutate the saved observations. A fresh query
            // has no history of books that left storage before the snapshot.
            var current = new HashMap<EntityId, Entity>();
            for (var entity : spellbookEntities) current.put(entity.getId(), entity);
            var removed = observed.keySet().stream().filter(id -> !current.containsKey(id)).sorted().toList();
            var added = current.values().stream().filter(e -> !observed.containsKey(e.getId())).toList();
            var retained = current.values().stream().filter(e -> observed.containsKey(e.getId())).toList();
            removed.forEach(this::removeObserved);
            processAddedEntities(added);
            processChangedEntities(retained);
            reconcileOnNextTick = false;
        } else if (changed) {
            spellbookEntities.getRemovedEntities().stream().map(Entity::getId).sorted().forEach(this::removeObserved);
            processAddedEntities(spellbookEntities.getAddedEntities());
            processChangedEntities(spellbookEntities.getChangedEntities());
        }
    }

    private void processAddedEntities(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            Entry entry = Entry.from(entity);
            observed.put(entry.entityId(), entry);
            var control = getResearchablePlayerControl(playerControllersByPlayerId, entry.ownerId(), entry.spellbook());
            if (control != null) control.onResearchResultsAdded(getResearchableType(kwdFile, entry.spellbook()));
        }
    }

    private void removeObserved(EntityId id) {
        Entry entry = observed.remove(id);
        var control = getResearchablePlayerControl(playerControllersByPlayerId, entry.ownerId(), entry.spellbook());
        if (control != null) control.onResearchResultsRemoved(getResearchableType(kwdFile, entry.spellbook()));
    }

    private void processChangedEntities(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(Comparator.comparing(Entity::getId)).toList()) {
            Entry entry = Entry.from(entity);
            Entry previous = observed.put(entry.entityId(), entry);
            if (entry.ownerId() != previous.ownerId()) {
                // Preserve the live system's transfer order and use of the current
                // book type for both notifications. Type-only changes have no callback.
                var book = entry.spellbook();
                var next = getResearchablePlayerControl(playerControllersByPlayerId, entry.ownerId(), book);
                var old = getResearchablePlayerControl(playerControllersByPlayerId, previous.ownerId(), book);
                if (next != null) next.onResearchResultsAdded(getResearchableType(kwdFile, book));
                if (old != null) old.onResearchResultsRemoved(getResearchableType(kwdFile, book));
            }
        }
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {
        spellbookEntities.release();
    }

    private static AbstractResearchablePlayerControl getResearchablePlayerControl(Map<Short, IPlayerController> playerControllersByPlayerId, short ownerId, Spellbook spellbook) {
        IPlayerController playerController = playerControllersByPlayerId.get(ownerId);
        if (playerController != null) {
            switch (spellbook.type) {
                case DOOR: {
                    return playerController.getDoorControl();
                }
                case ROOM: {
                    return playerController.getRoomControl();
                }
                case SPELL: {
                    return playerController.getSpellControl();
                }
                case TRAP: {
                    return playerController.getTrapControl();
                }
            }
        }

        return null;
    }

    private static IIndexable getResearchableType(IKwdFile kwdFile, Spellbook spellbook) {
        switch (spellbook.type) {
            case DOOR: {
                return kwdFile.getDoorById(spellbook.typeId);
            }
            case ROOM: {
                return kwdFile.getRoomById(spellbook.typeId);
            }
            case SPELL: {
                return kwdFile.getKeeperSpellById(spellbook.typeId);
            }
            case TRAP: {
                return kwdFile.getTrapById(spellbook.typeId);
            }
        }

        return null;
    }
}
