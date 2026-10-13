/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector3f;
import com.simsilica.es.EntityId;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.chicken.ChickenState;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.game.save.*;
import static org.junit.jupiter.api.Assertions.*;

class ObjectBindingRestoreTest {
    @Test void bindingSkipsAuthoredObjectsAndFutureGoldAndEggCreationUseRestoredIdsAndClock() throws Exception {
        var data = new GameEntityData();
        try {
            var existing = data.createEntity();
            data.setComponents(existing, new ObjectComponent(ObjectsController.OBJECT_GOLD_PILE_ID, ObjectType.GOLD),
                    new Owner((short) 3, (short) 3), new Gold(71,100),new Position(0,new Vector3f(2,0,3)));
            var tombstone = data.createEntity();data.removeEntity(tombstone);
            var empty = data.createEntity();
            var gold = new GameObject();gold.setFlags(EnumSet.of(GameObject.ObjectFlag.OBJECT_TYPE_GOLD));
            var egg = new GameObject();egg.setFlags(EnumSet.of(GameObject.ObjectFlag.OBJECT_TYPE_FOOD));
            var definition = proxy(IKwdFile.class, (method,args) -> {
                if (method.equals("getObject")) return ((Number) args[0]).intValue() == ObjectsController.OBJECT_GOLD_PILE_ID ? gold : egg;
                throw new AssertionError("Binding must not read authored things: " + method);
            });
            var map = proxy(IMapController.class, (method,args) -> {throw new AssertionError(method);});
            var world = proxy(IGameWorldController.class, (method,args) -> method.equals("getMapController") ? map : fail(method));
            var game = proxy(IGameController.class, (method,args) -> method.equals("getGameWorldController") ? world : fail(method));
            var level = proxy(ILevelInfo.class, (method,args) -> {throw new AssertionError(method);});
            var clock = new GameTimeController(new GameTimeController.State(30,123.5));
            byte[] before = EntitySnapshots.encode(data.capture());
            var controller = ObjectsController.restoreBindings(definition,data,Map.of(),clock,game,level, new toniarts.openkeeper.game.random.SimulationRandom(731));
            assertArrayEquals(before,EntitySnapshots.encode(data.capture()));
            var bound = controller.createController(existing);
            assertEquals(existing,bound.getEntityId());assertEquals((short) 3,bound.getOwnerId());assertEquals(ObjectType.GOLD,bound.getType());
            assertArrayEquals(before,EntitySnapshots.encode(data.capture()));
            var created = controller.addRoomGold((short) 4,5,6,12,60);
            assertEquals(empty.getId()+1,created.getId());assertEquals(12,data.getComponent(created,Gold.class).gold);
            assertEquals(60,data.getComponent(created,Gold.class).maxGold);assertEquals((short) 4,data.getComponent(created,Owner.class).ownerId);
            assertEquals(71,data.getComponent(existing,Gold.class).gold);assertTrue(data.wasAllocated(tombstone));
            var chicken = controller.spawnChicken((short) 3,new Vector3f(4,0,7));
            assertEquals(created.getId()+1,chicken.getId());assertEquals(123.5,data.getComponent(chicken,ChickenAi.class).stateStartTime);
            assertEquals(ChickenState.HATCHING_START,data.getComponent(chicken,ChickenAi.class).getChickenState());
            assertNotNull(data.getComponent(chicken,Food.class));
        } finally {data.close();}
    }
    interface Call {Object invoke(String method,Object[] args);}
    static <T> T proxy(Class<T> type,Call call) {return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m.getName(),a)));}
}
