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

import com.jme3.util.SafeArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.function.Function;
import toniarts.openkeeper.game.save.*;
import com.simsilica.es.Entity;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntityId;
import com.simsilica.es.EntitySet;
import toniarts.openkeeper.utils.Point;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import toniarts.openkeeper.game.component.ChickenGenerator;
import toniarts.openkeeper.game.component.Decay;
import toniarts.openkeeper.game.component.Food;
import toniarts.openkeeper.game.component.Owner;
import toniarts.openkeeper.game.component.Position;
import toniarts.openkeeper.game.controller.ILevelInfo;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.controller.IObjectsController;
import toniarts.openkeeper.game.controller.IPlayerController;
import toniarts.openkeeper.game.controller.room.IChickenGenerator;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.listener.RoomListener;
import toniarts.openkeeper.tools.convert.map.Room;
import toniarts.openkeeper.tools.convert.map.Variable;
import toniarts.openkeeper.utils.GameTimeCounter;
import toniarts.openkeeper.utils.Utils;
import toniarts.openkeeper.utils.WorldUtils;

/**
 * Handles spawing chickens in hatcheries and coops
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public final class ChickenSpawnSystem extends GameTimeCounter {

    private final EntityData entityData;
    private final IObjectsController objectsController;
    private final IMapController mapController;
    private final int maximumFreerangeChickenCount;
    private final int entranceCooldownTime;
    private final Map<Short, IPlayerController> playerControllersById;
    private final SafeArrayList<IChickenGenerator> entrances = new SafeArrayList<>(IChickenGenerator.class);
    private final EntitySet freerangeChickenGenerators;
    private final Map<IChickenGenerator, Set<EntityId>> freerangeChickenGeneratorsByRoom = new HashMap<>();
    private final Map<EntityId, IChickenGenerator> roomsByFreerangeChickenGenerators = new HashMap<>();
    private final EntitySet freerangeChickens;
    private final Map<Short, Set<EntityId>> freeRangeChickensByPlayer;
    private final Map<EntityId, Short> freeRangeChickenOwners;

    private final Map<Short,RoomListener> roomListeners=new java.util.LinkedHashMap<>();
    private boolean reconcileOnNextTick,active,closed;

    public ChickenSpawnSystem(EntityData entityData, IObjectsController objectsController, Collection<IPlayerController> playerControllers,
            Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> gameSettings, ILevelInfo levelInfo,
            IMapController mapController) {
        this(entityData,objectsController,playerControllers,mapController,new ChickenSpawnSnapshots.Settings(
                (int)gameSettings.get(Variable.MiscVariable.MiscType.CHICKEN_GENERATION_TIME_PER_HATCHERY).getValue()/levelInfo.getLevelData().getGameLevel().getTicksPerSec(),
                (int)gameSettings.get(Variable.MiscVariable.MiscType.MAX_FREE_RANGE_CHICKENS_PER_PLAYER).getValue()));
        for(var player:playerControllers) {
            for(var keeperRooms:player.getRoomControl().getRoomControllers().entrySet())for(var room:keeperRooms.getValue()) {
                if(!(room instanceof IChickenGenerator generator))break;
                entrances.add(generator);
            }
        }
        processAddedEntities(freerangeChickenGenerators);processAddedChickenEntities(freerangeChickens);start();
    }

    private ChickenSpawnSystem(EntityData data,IObjectsController objects,Collection<IPlayerController> players,IMapController map,
            ChickenSpawnSnapshots.Settings settings) {
        entityData=data;objectsController=objects;mapController=map;entranceCooldownTime=settings.cooldown();maximumFreerangeChickenCount=settings.maximumFreeRange();
        playerControllersById=new java.util.LinkedHashMap<>();
        for(var player:players)if(playerControllersById.putIfAbsent(player.getKeeper().getId(),player)!=null)throw new IllegalArgumentException("Duplicate chicken player");
        freerangeChickenGenerators=data.getEntities(ChickenGenerator.class,Position.class);
        freerangeChickens=data.getEntities(Food.class,Decay.class,Owner.class);
        freeRangeChickenOwners=new HashMap<>();freeRangeChickensByPlayer=new HashMap<>();
        playerControllersById.keySet().forEach(owner->freeRangeChickensByPlayer.put(owner,new HashSet<>()));
    }

    public ChickenSpawnSnapshots.State captureState() {
        var coops=new java.util.TreeMap<EntityId,EntityId>();roomsByFreerangeChickenGenerators.forEach((id,room)->coops.put(id,room==null?null:room.getEntityId()));
        var roomCoops=new java.util.TreeMap<EntityId,List<EntityId>>();freerangeChickenGeneratorsByRoom.forEach((room,ids)->roomCoops.put(room.getEntityId(),List.copyOf(ids)));
        var populations=new java.util.TreeMap<Short,List<EntityId>>();freeRangeChickensByPlayer.forEach((owner,ids)->populations.put(owner,ids.stream().sorted().toList()));
        var rooms=new ArrayList<IRoomController>(entrances);rooms.addAll(freerangeChickenGeneratorsByRoom.keySet());
        roomsByFreerangeChickenGenerators.values().stream().filter(java.util.Objects::nonNull).forEach(rooms::add);
        return new ChickenSpawnSnapshots.State(timeElapsed,new ChickenSpawnSnapshots.Settings(entranceCooldownTime,maximumFreerangeChickenCount),
                List.copyOf(playerControllersById.keySet()),entrances.stream().map(IRoomController::getEntityId).toList(),coops,roomCoops,
                freeRangeChickenOwners,populations,SpawnRoomBindings.captureRemoved(rooms));
    }

    public static ChickenSpawnSystem restoreState(GameEntityData data,IObjectsController objects,Collection<IPlayerController> players,IMapController map,
            Function<EntityId,IRoomController> rooms,ChickenSpawnSnapshots.State saved) {
        var byId=new HashMap<Short,IPlayerController>();
        for(var player:players)if(byId.putIfAbsent(player.getKeeper().getId(),player)!=null)throw new IllegalArgumentException("Duplicate chicken player");
        if(!byId.keySet().equals(new HashSet<>(saved.players())))throw new IllegalArgumentException("Chicken players differ");
        for(var ids:List.of(saved.coops().keySet(),saved.owners().keySet()))for(var id:ids)if(!data.wasAllocated(id))throw new IllegalArgumentException("Unallocated spawning entity");
        var references=new ArrayList<>(saved.entrances());references.addAll(saved.roomCoops().keySet());
        var bindings=SpawnRoomBindings.resolve(IChickenGenerator.class,references,saved.removedRooms(),map,data,rooms);
        var system=new ChickenSpawnSystem(data,objects,saved.players().stream().map(byId::get).toList(),map,saved.settings());
        system.timeElapsed=saved.elapsed();system.entrances.addAll(saved.entrances().stream().map(bindings::get).toList());
        saved.coops().forEach((id,room)->system.roomsByFreerangeChickenGenerators.put(id,room==null?null:bindings.get(room)));
        saved.roomCoops().forEach((room,ids)->system.freerangeChickenGeneratorsByRoom.put(bindings.get(room),new LinkedHashSet<>(ids)));
        system.freeRangeChickenOwners.putAll(saved.owners());system.freeRangeChickensByPlayer.clear();
        saved.populations().forEach((owner,ids)->system.freeRangeChickensByPlayer.put(owner,new HashSet<>(ids)));
        system.reconcileOnNextTick=true;return system;
    }

    @Override
    public void processTick(float tpf) {
        if(!active || closed)throw new IllegalStateException("Chicken spawning is not active");
        super.processTick(tpf);
        if(reconcileOnNextTick) {
            var observed=new HashSet<>(roomsByFreerangeChickenGenerators.keySet());freerangeChickenGenerators.applyChanges();
            processDeletedEntities(observed.stream().filter(id->!freerangeChickenGenerators.containsId(id)).toList());
            processAddedEntities(freerangeChickenGenerators.stream().filter(entity->!observed.contains(entity.getId())).toList());
            var chickens=new HashSet<>(freeRangeChickenOwners.keySet());freerangeChickens.applyChanges();
            processDeletedChickenEntities(chickens.stream().filter(id->!freerangeChickens.containsId(id)).toList());
            processAddedChickenEntities(freerangeChickens.stream().filter(entity->!chickens.contains(entity.getId())).toList());
            processChangedChickenEntities(freerangeChickens.stream().filter(entity->chickens.contains(entity.getId())).toList());
            reconcileOnNextTick=false;
        } else {
            if(freerangeChickenGenerators.applyChanges()) {
                processDeletedEntities(freerangeChickenGenerators.getRemovedEntities().stream().map(Entity::getId).toList());
                processAddedEntities(freerangeChickenGenerators.getAddedEntities());
            }
            if(freerangeChickens.applyChanges()) {
                processDeletedChickenEntities(freerangeChickens.getRemovedEntities().stream().map(Entity::getId).toList());
                processAddedChickenEntities(freerangeChickens.getAddedEntities());
                processChangedChickenEntities(freerangeChickens.getChangedEntities());
            }
        }

        for (IChickenGenerator entrance : entrances.getArray()) {
            evaluateAndSpawnCreature(entrance);
        }
    }

    private void evaluateAndSpawnCreature(IChickenGenerator entrance) {
        if(entrance.isRemoved() || entrance.isDestroyed())return;
        double timeSinceLastSpawn = timeElapsed - entrance.getLastSpawnTime();
        boolean spawned = false;
        EntityId entityId = null;
        if (timeSinceLastSpawn >= entranceCooldownTime) {

            if (!entrance.isFullCapacity()) {
                // Spawn chicken
                Point entranceCoordinate = entrance.getEntranceCoordinate();
                entityId = objectsController.spawnChicken(entrance.getOwnerId(), WorldUtils.pointToVector3f(entranceCoordinate));
                spawned = true;
            } else if (freeRangeChickensByPlayer.get(entrance.getOwnerId()).size() < maximumFreerangeChickenCount && freerangeChickenGeneratorsByRoom.get(entrance) != null) {
                Set<EntityId> generators = freerangeChickenGeneratorsByRoom.get(entrance);
                if (generators.isEmpty()) return;
                Optional<EntityId> generator = Utils.getRandomItem((java.util.Collection<EntityId>) generators.stream().sorted().toList(), objectsController.getRandom());

                if (generator.isPresent()) {
                    // Spawn a free range chicken
                    // Don't give the entity ID, it is not added to room inventory
                    // TODO: Need to have the generator IN USE component etc. This goes for all the objects, how we use them
                    Position position = entityData.getComponent(generator.get(), Position.class);
                    objectsController.spawnFreerangeChicken(entrance.getOwnerId(), position.position.clone(), timeElapsed);
                    spawned = true;
                }
            }
        }

        if (spawned) {
            // Reset spawn time
            entrance.onSpawn(timeElapsed, entityId);
        }
    }

    @Override
    public void start() {
        if(closed)throw new IllegalStateException("Chicken spawning is stopped");if(active)return;
        try {
            for(short player:playerControllersById.keySet()) {
                var listener=new EntranceListener(player);roomListeners.put(player,listener);mapController.addListener(player,listener);
            }
            active=true;
        }catch(RuntimeException|Error failure){stop();throw failure;}
    }

    @Override
    public void stop() {
        if(closed)return;closed=true;active=false;
        roomListeners.forEach((player,listener)->mapController.removeListener(player,listener));roomListeners.clear();
        freerangeChickenGenerators.release();
        freerangeChickenGeneratorsByRoom.clear();
        roomsByFreerangeChickenGenerators.clear();

        freerangeChickens.release();
        freeRangeChickensByPlayer.clear();
        freeRangeChickenOwners.clear();
    }

    private void processAddedEntities(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(java.util.Comparator.comparing(Entity::getId)).toList()) {
            Position position = entity.get(Position.class);
            Point p = WorldUtils.vectorToPoint(position.position);
            IRoomController roomController = mapController.getRoomControllerByCoordinates(p);
            IChickenGenerator chickenGenerator = null;
            if (roomController instanceof IChickenGenerator) {
                chickenGenerator = (IChickenGenerator) roomController;
                Set<EntityId> generators = freerangeChickenGeneratorsByRoom.get(chickenGenerator);
                if (generators == null) {
                    generators = new LinkedHashSet<>();
                }
                generators.add(entity.getId());
                freerangeChickenGeneratorsByRoom.put(chickenGenerator, generators);
            }
            roomsByFreerangeChickenGenerators.put(entity.getId(), chickenGenerator);
        }
    }

    private void processDeletedEntities(Collection<EntityId> entities) {
        for (EntityId id : entities) {
            IChickenGenerator chickenGenerator = roomsByFreerangeChickenGenerators.remove(id);
            if (chickenGenerator != null) {
                freerangeChickenGeneratorsByRoom.get(chickenGenerator).remove(id);
            }
        }
    }

    private void processAddedChickenEntities(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(java.util.Comparator.comparing(Entity::getId)).toList()) {
            Owner owner = entity.get(Owner.class);
            Set<EntityId> chickens = freeRangeChickensByPlayer.get(owner.ownerId);
            if (chickens == null) {
                chickens = new HashSet<>();
            }
            chickens.add(entity.getId());
            freeRangeChickensByPlayer.put(owner.ownerId, chickens);
            freeRangeChickenOwners.put(entity.getId(), owner.ownerId);
        }
    }

    private void processDeletedChickenEntities(Collection<EntityId> entities) {
        for (EntityId id : entities) {
            short ownerId = freeRangeChickenOwners.remove(id);
            freeRangeChickensByPlayer.get(ownerId).remove(id);
        }
    }

    private void processChangedChickenEntities(Collection<Entity> entities) {
        for (Entity entity : entities.stream().sorted(java.util.Comparator.comparing(Entity::getId)).toList()) {
            Owner owner = entity.get(Owner.class);
            short previousOwner = freeRangeChickenOwners.get(entity.getId());
            if (previousOwner != owner.ownerId) {
                freeRangeChickenOwners.put(entity.getId(), owner.ownerId);
                freeRangeChickensByPlayer.get(previousOwner).remove(entity.getId());
                freeRangeChickensByPlayer.computeIfAbsent(owner.ownerId,ignored->new HashSet<>()).add(entity.getId());
            }
        }
    }

    private final class EntranceListener implements RoomListener {

        private final short playerId;

        public EntranceListener(short playerId) {
            this.playerId = playerId;
        }

        @Override
        public void onBuild(IRoomController room) {
            addRoom(room);
        }

        @Override
        public void onCaptured(IRoomController room) {
            //addRoom(room);
        }

        @Override
        public void onCapturedByEnemy(IRoomController room) {
            //removeRoom(room);
        }

        @Override
        public void onSold(IRoomController room) {
            removeRoom(room);
        }

        private void addRoom(IRoomController room) {
            if (!closed && room instanceof IChickenGenerator chickenGenerator) {
                entrances.add(chickenGenerator);
            }
        }

        private void removeRoom(IRoomController room) {
            if (!closed && room instanceof IChickenGenerator chickenGenerator) {
                entrances.remove(chickenGenerator);
            }
        }

    }

}
