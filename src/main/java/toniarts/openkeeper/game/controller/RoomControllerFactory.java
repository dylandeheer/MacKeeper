/*
 * Copyright (C) 2014-2015 OpenKeeper
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
package toniarts.openkeeper.game.controller;

import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import toniarts.openkeeper.common.RoomInstance;
import toniarts.openkeeper.game.controller.room.CasinoController;
import toniarts.openkeeper.game.controller.room.CombatPitController;
import toniarts.openkeeper.game.controller.room.DoubleQuadController;
import toniarts.openkeeper.game.controller.room.FiveByFiveRotatedController;
import toniarts.openkeeper.game.controller.room.HatcheryController;
import toniarts.openkeeper.game.controller.room.HeroGateFrontEndController;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.controller.room.LairController;
import toniarts.openkeeper.game.controller.room.LibraryController;
import toniarts.openkeeper.game.controller.room.NormalRoomController;
import toniarts.openkeeper.game.controller.room.PrisonController;
import toniarts.openkeeper.game.controller.room.TempleController;
import toniarts.openkeeper.game.controller.room.ThreeByThreeController;
import toniarts.openkeeper.game.controller.room.TortureChamberController;
import toniarts.openkeeper.game.controller.room.TrainingRoomController;
import toniarts.openkeeper.game.controller.room.TreasuryController;
import toniarts.openkeeper.game.controller.room.WorkshopController;
import toniarts.openkeeper.tools.convert.map.IKwdFile;
import toniarts.openkeeper.tools.convert.map.Variable;
import toniarts.openkeeper.tools.convert.map.Variable.MiscVariable;
import toniarts.openkeeper.tools.convert.map.Variable.MiscVariable.MiscType;

/**
 * A factory class you can use to build buildings
 *
 * @author ArchDemon
 */
public final class RoomControllerFactory {

    private static final Logger logger = System.getLogger(RoomControllerFactory.class.getName());

    private RoomControllerFactory() {
        // Nope
    }

    public static IRoomController constructRoom(EntityData entityData, IKwdFile kwdFile, RoomInstance roomInstance, IObjectsController objectsController, Map<MiscType, MiscVariable> gameSettings, IGameTimer gameTimer) {
        return construct(entityData.createEntity(), entityData, kwdFile, roomInstance, objectsController, gameSettings, gameTimer, true);
    }

    public static IRoomController restoreRoom(EntityData data, IKwdFile definition,
            toniarts.openkeeper.game.data.RoomState state, IObjectsController objects,
            Map<MiscType, MiscVariable> settings, IGameTimer timer) {
        return restoreRoom(data, definition, state, objects, settings, timer, false);
    }

    /** A removed controller can remain strongly referenced by a task awaiting cleanup. */
    public static IRoomController restoreRemovedRoom(EntityData data, IKwdFile definition,
            toniarts.openkeeper.game.data.RoomState state, IObjectsController objects,
            Map<MiscType, MiscVariable> settings, IGameTimer timer) {
        return restoreRoom(data, definition, state, objects, settings, timer, true);
    }

    private static IRoomController restoreRoom(EntityData data, IKwdFile definition,
            toniarts.openkeeper.game.data.RoomState state, IObjectsController objects,
            Map<MiscType, MiscVariable> settings, IGameTimer timer, boolean removed) {
        if (!(data instanceof toniarts.openkeeper.game.save.GameEntityData saved)) throw new IllegalArgumentException("Room restoration requires a tracked entity store");
        var references = new java.util.ArrayList<EntityId>();references.add(state.entityId());
        references.addAll(state.floorFurniture());references.addAll(state.wallFurniture());references.addAll(state.pillars());
        state.storage().values().forEach(storage -> {references.add(storage.entityId());storage.objectsByCoordinate().values().forEach(references::addAll);});
        if (references.stream().anyMatch(id -> !saved.wasAllocated(id))) throw new IllegalArgumentException("Room reference exceeds allocator state");
        var type = definition.getRoomById(state.roomType());
        var component = data.getComponent(state.entityId(), toniarts.openkeeper.game.component.RoomComponent.class);
        var owner = data.getComponent(state.entityId(), toniarts.openkeeper.game.component.Owner.class);
        var health = data.getComponent(state.entityId(), toniarts.openkeeper.game.component.Health.class);
        if (type == null || (removed
                ? component != null || owner != null || health != null
                : component == null || component.roomId != state.roomType() || owner == null || health == null)) {
            throw new IllegalArgumentException("Saved room definition/components missing");
        }
        var instance = RoomInstance.restoreDefinition(type, state.direction());
        instance.addCoordinates(state.coordinates());instance.setOwnerId(state.ownerId());instance.setDestroyed(state.destroyed());
        instance.setHealth(state.health());instance.setMaxHealth(state.maxHealth());
        if (state.walls() != null) instance.setWallSections(state.walls().stream()
                .map(w -> new toniarts.openkeeper.view.map.WallSection(w.direction(), new java.util.ArrayList<>(w.coordinates()))).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new)));
        var result = (toniarts.openkeeper.game.controller.room.AbstractRoomController) construct(state.entityId(), data, definition, instance, objects, settings, timer, false);
        if (removed) result.bindRestoredRemovedState(state);
        else result.bindRestoredState(state);
        return result;
    }

    private static IRoomController construct(EntityId entity, EntityData entityData, IKwdFile kwdFile, RoomInstance roomInstance,
            IObjectsController objectsController, Map<MiscType, MiscVariable> gameSettings, IGameTimer gameTimer, boolean initialize) {
        String roomName = roomInstance.getRoom().getName();

        switch (roomInstance.getRoom().getTileConstruction()) {
            case _3_BY_3 -> {
                return new ThreeByThreeController(entity, entityData, kwdFile, roomInstance, objectsController, initialize);
            }
            case HERO_GATE, HERO_GATE_FRONT_END -> {
                return new HeroGateFrontEndController(entity, entityData, kwdFile, roomInstance, objectsController, initialize);
            }
            case HERO_GATE_2_BY_2 -> {
                return new NormalRoomController(entity, entityData, kwdFile, roomInstance, objectsController, initialize);
            }
            case _5_BY_5_ROTATED -> {
                return new FiveByFiveRotatedController(entity, entityData, kwdFile, roomInstance, objectsController, gameSettings, gameTimer, initialize);
            }
            case NORMAL -> {
                return constructNormal(entity, entityData, roomName, kwdFile, roomInstance, objectsController, gameTimer, gameSettings, initialize);
            }
            case DOUBLE_QUAD -> {
                return constructDoubleQuad(entity, entityData, roomName, kwdFile, roomInstance, objectsController, gameTimer, initialize);
            }
            default -> {
                // TODO
                logger.log(Level.WARNING, "Room {0} not exist", roomName);
                return new NormalRoomController(entity, entityData, kwdFile, roomInstance, objectsController, initialize);
            }

        }
    }

    private static IRoomController constructDoubleQuad(EntityId entity, EntityData entityData, String roomName, IKwdFile kwdFile, RoomInstance roomInstance, IObjectsController objectsController, IGameTimer gameTimer, boolean initialize) {
        if (roomName.equalsIgnoreCase("Prison")) {
            return new PrisonController(entity, entityData, kwdFile, roomInstance, objectsController, gameTimer, initialize);
        } else if (roomName.equalsIgnoreCase("Combat Pit")) {
            return new CombatPitController(entity, entityData, kwdFile, roomInstance, objectsController, initialize);
        } else if (roomName.equalsIgnoreCase("Temple")) {
            return new TempleController(entity, entityData, kwdFile, roomInstance, objectsController, initialize);
        }

        return new DoubleQuadController(entity, entityData, kwdFile, roomInstance, objectsController, initialize);
    }

    private static IRoomController constructNormal(EntityId entity, EntityData entityData, String roomName, IKwdFile kwdFile, RoomInstance roomInstance, IObjectsController objectsController, IGameTimer gameTimer, Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings, boolean initialize) {
        if (roomName.equalsIgnoreCase("Lair")) {
            return new LairController(entity, entityData, kwdFile, roomInstance, objectsController, gameTimer, initialize);
        } else if (roomName.equalsIgnoreCase("Library")) {
            return new LibraryController(entity, entityData, kwdFile, roomInstance, objectsController, gameTimer, initialize);
        } else if (roomName.equalsIgnoreCase("Training Room")) {
            return new TrainingRoomController(entity, entityData, kwdFile, roomInstance, objectsController, gameTimer, initialize);
        } else if (roomName.equalsIgnoreCase("Work Shop")) {
            return new WorkshopController(entity, entityData, kwdFile, roomInstance, objectsController, initialize);
//                } else if (roomName.equalsIgnoreCase("Guard Room")) {
//                    return new GuardRoom(assetManager, roomInstance, objectLoader, worldState, effectManager);
        } else if (roomName.equalsIgnoreCase("Casino")) {
            return new CasinoController(entity, entityData, kwdFile, roomInstance, objectsController, initialize);
//                } else if (roomName.equalsIgnoreCase("Graveyard")) {
//                    return new Graveyard(assetManager, roomInstance, objectLoader, worldState, effectManager);
        } else if (roomName.equalsIgnoreCase("Torture Chamber")) {
            return new TortureChamberController(entity, entityData, kwdFile, roomInstance, objectsController, gameTimer, initialize);
        } else if (roomName.equalsIgnoreCase("Treasury")) {
            return new TreasuryController(entity, entityData, kwdFile, roomInstance, objectsController, gameSettings, gameTimer, initialize);
        } else if (roomName.equalsIgnoreCase("Hatchery")) {
            return new HatcheryController(entity, entityData, kwdFile, roomInstance, objectsController, gameTimer, initialize);
        }

        return new NormalRoomController(entity, entityData, kwdFile, roomInstance, objectsController, initialize);
    }
}
