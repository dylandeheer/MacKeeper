/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.game.save;

import com.simsilica.es.EntityId;
import java.util.*;
import java.util.function.Function;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.controller.room.*;
import toniarts.openkeeper.game.data.RoomState;

/** Shared room dependencies: preserve object identity across map, task and spawning owners. */
public final class SpawnRoomBindings {
    public static List<RoomState> captureRemoved(Collection<? extends IRoomController> rooms) {
        var identities=new HashMap<EntityId,IRoomController>();var removed=new TreeMap<EntityId,IRoomController>();
        for(var room:rooms) {
            var previous=identities.putIfAbsent(room.getEntityId(),room);
            if(previous!=null && previous!=room)throw new IllegalArgumentException("Aliased spawning room");
            if(room.isRemoved())removed.put(room.getEntityId(),room);
        }
        return removed.values().stream().map(room->((AbstractRoomController)room).captureState()).toList();
    }
    public static <T extends IEntityGenerator> Map<EntityId,T> resolve(Class<T> type, Collection<EntityId> ids, List<RoomState> dependencies,
            IMapController map, GameEntityData data, Function<EntityId,IRoomController> rooms) {
        var removed=new HashMap<EntityId,RoomState>();dependencies.forEach(room->removed.put(room.entityId(),room));
        var result=new HashMap<EntityId,T>();
        for(var id:ids) {
            var room=rooms.apply(id);
            if(!data.wasAllocated(id) || !type.isInstance(room) || !id.equals(room.getEntityId()))throw new IllegalArgumentException("Missing or invalid spawning room");
            var previous=result.putIfAbsent(id,type.cast(room));
            if(previous!=null && previous!=room)throw new IllegalArgumentException("Aliased spawning room binding");
            if(removed.containsKey(id)) {
                if(map.getRoomController(id)!=null || !room.isRemoved() || !(room instanceof AbstractRoomController actual)
                        || !removed.get(id).equals(actual.captureState()))throw new IllegalArgumentException("Removed spawning room differs");
            } else if(room.isRemoved() || map.getRoomController(id)!=room)throw new IllegalArgumentException("Spawning map room differs");
        }
        return result;
    }
    private SpawnRoomBindings() { }
}
