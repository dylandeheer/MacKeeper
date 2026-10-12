/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import com.simsilica.es.base.DefaultEntityData;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.Gold;
import toniarts.openkeeper.game.component.Owner;
import toniarts.openkeeper.game.controller.ILevelInfo;
import toniarts.openkeeper.game.controller.GameWorldController;
import toniarts.openkeeper.game.controller.IPlayerController;
import toniarts.openkeeper.game.controller.player.PlayerHandControl;
import com.jme3.math.Vector2f;
import toniarts.openkeeper.common.RoomInstance;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import toniarts.openkeeper.game.controller.MapController;
import toniarts.openkeeper.game.controller.player.PlayerGoldControl;
import toniarts.openkeeper.game.controller.player.PlayerRoomControl;
import toniarts.openkeeper.game.controller.room.AbstractRoomController.ObjectType;
import toniarts.openkeeper.game.controller.room.IRoomController;
import toniarts.openkeeper.game.controller.room.storage.RoomGoldControl;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.trigger.TriggerControl;
import toniarts.openkeeper.game.task.objective.KillPlayer;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.utils.Point;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic KWD data exercises real map, room, storage and trigger controllers without retail assets. */
class RoomOwnershipTest {
    private static final short OLD = 3, NEW = 4;
    private final DefaultEntityData data = new DefaultEntityData();
    private final Keeper previous = new Keeper(false, OLD), next = new Keeper(false, NEW);
    private final Map<Short, Keeper> keepers = Map.of(OLD, previous, NEW, next);
    private final PlayerGoldControl oldGold = new PlayerGoldControl(previous), newGold = new PlayerGoldControl(next);
    private final List<EntityId> goldPiles = new ArrayList<>();
    private PlayerRoomControl oldRooms, newRooms;
    private ILevelInfo level;
    private IRoomController treasury;
    private GameWorldController world;
    private final PlayerHandControl oldHand = new PlayerHandControl(previous, 8, data);
    private final PlayerHandControl newHand = new PlayerHandControl(next, 8, data);

    @Test void scriptedTransferMovesWholeRoomAndTreasureExactlyOnce() {
        MapController map = map(false);
        TriggerControl trigger = new TriggerControl(null, level, () -> 0, map, null, 1);

        trigger.update(0.1f);
        trigger.update(0.1f);
        assertFalse(map.changeRoomOwner(new Point(1, 1), NEW));

        assertTransferred(map);
    }

    @Test void normalRoomCaptureUsesTheSameAccounting() {
        MapController map = map(false);
        map.applyClaimTile(new Point(1, 1), NEW);
        assertTransferred(map);
    }

    @Test void missingRoomAndInactivePlayerLeaveOwnershipAndMoneyAlone() {
        MapController map = map(false);
        assertFalse(map.changeRoomOwner(new Point(-1, 1), NEW));
        assertFalse(map.changeRoomOwner(new Point(0, 0), NEW));
        assertFalse(map.changeRoomOwner(new Point(1, 1), (short) 99));
        assertEquals(OLD, treasury.getOwnerId());
        assertEquals(600, previous.getGold());
        assertEquals(0, next.getGold());
        assertEquals(1, oldRooms.getTypeCount());
        assertEquals(0, newRooms.getTypeCount());
    }

    @Test void adjacentSameTypeRoomsWithDifferentOwnersLoadSeparately() {
        MapController map = map(true);
        assertEquals(2, map.getRoomControllers().size());
        assertNotEquals(map.getMapData().getTile(1, 1).getRoomId(), map.getMapData().getTile(2, 1).getRoomId());
        assertEquals(OLD, map.getRoomControllerByCoordinates(new Point(1, 1)).getOwnerId());
        assertEquals(NEW, map.getRoomControllerByCoordinates(new Point(2, 1)).getOwnerId());
    }

    @Test void repeatedRoomNotificationsDoNotInflateOrUnderflowRoomCount() {
        map(false);
        oldRooms.onBuild(treasury);
        assertEquals(1, oldRooms.getTypeCount());
        oldRooms.onSold(treasury);
        oldRooms.onSold(treasury);
        assertEquals(0, oldRooms.getTypeCount());
    }

    private void assertTransferred(MapController map) {
        assertEquals(NEW, treasury.getOwnerId());
        assertEquals(NEW, treasury.getRoomInstance().getOwnerId());
        assertEquals(200, treasury.getHealth());
        for (int x : List.of(1, 2)) {
            assertEquals(NEW, map.getMapData().getTile(x, 1).getOwnerId());
            assertEquals(100, map.getMapData().getTile(x, 1).getHealth());
        }
        assertEquals(0, oldRooms.getTypeCount());
        assertEquals(1, newRooms.getTypeCount());
        assertEquals(0, previous.getGold());
        assertEquals(600, next.getGold());
        assertEquals(0, oldGold.getGoldMax());
        assertEquals(2000, newGold.getGoldMax());
        assertEquals(25, previous.getGoldMined());
        assertEquals(42, next.getGoldMined());
        assertEquals(600, goldPiles.stream().mapToInt(id -> data.getComponent(id, Gold.class).gold).sum());
        for (EntityId pile : goldPiles) assertEquals(NEW, data.getComponent(pile, Owner.class).ownerId);
    }

    private MapController map(boolean differentOwners) {
        Room room = new Room();
        room.setName("Treasury");
        room.setRoomId((short) 42); // Avoid the original hard-coded pillar IDs.
        room.setTileConstruction(Room.TileConstruction.NORMAL);
        room.setObjects(Collections.nCopies(6, (short) 0));
        room.setFlags(EnumSet.of(Room.RoomFlag.BUILDABLE));
        Terrain floor = new Terrain(), roomTile = new Terrain();
        floor.setFlags(EnumSet.noneOf(Terrain.TerrainFlag.class));
        roomTile.setFlags(EnumSet.of(Terrain.TerrainFlag.ROOM, Terrain.TerrainFlag.OWNABLE));
        roomTile.setTerrainId((short) 1);
        roomTile.setStartingHealth(40);
        roomTile.setMaxHealth(100);
        GameMap original = new GameMap(4, 3);
        for (int x = 0; x < 4; x++) for (int y = 0; y < 3; y++) {
            Tile tile = new Tile();
            tile.setTerrainId((short) (y == 1 && (x == 1 || x == 2) ? 1 : 0));
            tile.setPlayerId(differentOwners && x == 2 ? NEW : OLD);
            original.setTile(x, y, tile);
        }
        TriggerAction action = new TriggerAction(null);
        action.setId(1);
        action.setType(TriggerAction.ActionType.CHANGE_ROOM_OWNER);
        action.setUserData("playerId", NEW);
        action.setUserData("posX", 2); // File coordinates are one-based.
        action.setUserData("posY", 2);
        GameObject goldObject = new GameObject();
        goldObject.setFlags(EnumSet.of(GameObject.ObjectFlag.OBJECT_TYPE_GOLD,
                GameObject.ObjectFlag.CAN_BE_PICKED_UP, GameObject.ObjectFlag.CAN_BE_DROPPED_ON_ANY_LAND));
        goldObject.setHp(1);
        IKwdFile kwd = proxy(IKwdFile.class, (method, args) -> switch (method) {
            case "getMap" -> original;
            case "getThings" -> List.of();
            case "getObject" -> goldObject;
            case "getPlayer" -> new Player();
            case "getTerrain" -> ((short) args[0] == 1) ? roomTile : floor;
            case "getRoomByTerrain", "getRoomById" -> room;
            case "getDungeonHeart" -> null;
            case "getTriggers" -> Map.of(1, action);
            default -> throw new AssertionError(method);
        });
        level = proxy(ILevelInfo.class, (method, args) -> switch (method) {
            case "getLevelData" -> kwd;
            case "getPlayers" -> keepers;
            case "getPlayer" -> keepers.get((short) args[0]);
            default -> throw new AssertionError(method);
        });
        Variable.MiscVariable capacity = new Variable.MiscVariable(), damage = new Variable.MiscVariable();
        capacity.setValue(1000);
        damage.setValue(300);
        oldRooms = new PlayerRoomControl(previous, List.of(room));
        newRooms = new PlayerRoomControl(next, List.of(room));
        world = new GameWorldController(null, level, data, Map.of(
                Variable.MiscVariable.MiscType.MAX_GOLD_PER_TREASURY_TILE, capacity,
                Variable.MiscVariable.MiscType.MAX_GOLD_PILE_OUTSIDE_TREASURY, capacity,
                Variable.MiscVariable.MiscType.ATTACK_ROOM_HEALTH, damage),
                Map.of(OLD, player(previous, oldGold, oldRooms, oldHand),
                       NEW, player(next, newGold, newRooms, newHand)), () -> 0);
        MapController map = (MapController) world.getMapController();
        treasury = map.getRoomControllerByCoordinates(new Point(1, 1));
        assertEquals(0, world.addGold(OLD, new Point(1, 1), 600));
        RoomGoldControl storage = treasury.getObjectControl(ObjectType.GOLD);
        goldPiles.addAll(storage.getItems(new Point(1, 1)));
        previous.setGoldMined(25);
        next.setGoldMined(42);
        return map;
    }

    private IPlayerController player(Keeper keeper, PlayerGoldControl gold,
            PlayerRoomControl rooms, PlayerHandControl hand) {
        return proxy(IPlayerController.class, (method, args) -> switch (method) {
            case "getKeeper" -> keeper;
            case "getGoldControl" -> gold;
            case "getRoomControl" -> rooms;
            case "getHandControl" -> hand;
            default -> throw new AssertionError(method);
        });
    }

    @Test void queuedPickupRechecksOwnerAfterCapture() throws Exception {
        MapController map = map(false);
        EntityId pile = goldPiles.getFirst();
        afterCaptureWhileActionWaits(map, () -> world.pickUp(pile, OLD));
        assertTrue(oldHand.isEmpty());
        assertTransferred(map);
        world.pickUp(pile, NEW);
        assertEquals(pile, newHand.peek());
        assertEquals(0, next.getGold());
        assertEquals(0, treasury.getObjectControl(ObjectType.GOLD).getCurrentCapacity());
        assertEquals(600, data.getComponent(pile, Gold.class).gold);
    }

    @Test void queuedDropCreditsCurrentRoomOwner() throws Exception {
        MapController map = map(false);
        EntityId pile = goldPiles.getFirst();
        world.pickUp(pile, OLD);
        assertEquals(0, previous.getGold());
        afterCaptureWhileActionWaits(map, () -> world.drop(pile, new Point(1, 1), new Vector2f(1, 1), null, OLD));
        assertTrue(oldHand.isEmpty());
        assertEquals(0, previous.getGold());
        assertEquals(600, next.getGold());
        assertEquals(600, treasury.getObjectControl(ObjectType.GOLD).getCurrentCapacity());
        RoomGoldControl storage = treasury.getObjectControl(ObjectType.GOLD);
        EntityId deposited = storage.getItems(new Point(1, 1)).iterator().next();
        assertEquals(NEW, data.getComponent(deposited, Owner.class).ownerId);
        assertDoesNotThrow(() -> world.drop(pile, new Point(1, 1), new Vector2f(1, 1), null, OLD));
    }

    @Test void queuedSaleRechecksOwnershipAfterCapture() throws Exception {
        MapController map = map(false);
        afterCaptureWhileActionWaits(map, () -> world.sell(new Vector2f(1, 1), new Vector2f(2, 1), OLD));
        assertTransferred(map);
    }

    @Test void droppingIntoAnInactivePlayersRoomLeavesLooseGold() {
        map(false);
        EntityId pile = goldPiles.getFirst();
        world.pickUp(pile, OLD);
        treasury.captured((short) 99); // An unused multiplayer slot has no player controller.
        world.drop(pile, new Point(1, 1), new Vector2f(1, 1), null, OLD);
        assertTrue(oldHand.isEmpty());
        assertEquals(0, treasury.getObjectControl(ObjectType.GOLD).getCurrentCapacity());
        assertEquals(0, previous.getGold());
        var loose = data.findEntities(null, Gold.class).stream()
                .filter(id -> data.getComponent(id, Gold.class).gold > 0).toList();
        assertEquals(1, loose.size());
        EntityId remaining = loose.iterator().next();
        assertEquals(600, data.getComponent(remaining, Gold.class).gold);
        assertEquals(OLD, data.getComponent(remaining, Owner.class).ownerId);
    }

    @Test void goldForAnOldOwnerCannotBeDepositedIntoCapturedStorage() {
        MapController map = map(false);
        map.changeRoomOwner(new Point(1, 1), NEW);
        assertEquals(100, world.addGold(OLD, new Point(1, 1), 100));
        assertTransferred(map);
    }

    @Test void distributingGoldAcrossRoomsConservesTheRequestedAmount() {
        MapController map = map(true);
        map.changeRoomOwner(new Point(2, 1), OLD);
        // Exceed either room's free space so this exercises both, regardless of iteration order.
        assertEquals(200, world.addGold(OLD, 1600));
        assertEquals(2000, previous.getGold());
        assertEquals(2000, map.getRoomControllers().stream()
                .mapToInt(r -> r.getObjectControl(ObjectType.GOLD).getCurrentCapacity()).sum());
    }

    @Test void losingSelectedHeartChoosesAnotherUntilNoneRemain() {
        Room type = new Room();
        type.setRoomId((short) 7);
        PlayerRoomControl rooms = new PlayerRoomControl(previous, List.of(type));
        IRoomController first = heart(type, new Point(3, 3)), second = heart(type, new Point(9, 9));
        rooms.onBuild(first);
        rooms.onBuild(second);
        assertEquals(new Point(3, 3), previous.getDungeonHeartLocation());
        rooms.onCapturedByEnemy(first);
        assertEquals(new Point(9, 9), previous.getDungeonHeartLocation());
        rooms.onSold(second);
        assertNull(previous.getDungeonHeartLocation());
        assertEquals(0, rooms.getTypeCount());
    }

    @Test void killPlayerObjectiveWithoutAHeartDoesNotAttemptNavigation() {
        MapController map = map(false);
        ICreatureController hero = proxy(ICreatureController.class, (method, args) -> switch (method) {
            case "getOwnerId" -> (short) 1;
            default -> throw new AssertionError(method);
        });
        for (short target : new short[] {OLD, 99}) {
            KillPlayer task = new KillPlayer(null, map, level, target, hero);
            assertFalse(task.isValid(hero));
            assertTrue(task.getTaskQueue().isEmpty());
            assertNull(task.getTarget(hero));
        }
    }

    private IRoomController heart(Room type, Point center) {
        RoomInstance instance = new RoomInstance(type);
        instance.addCoordinate(center);
        return proxy(IRoomController.class, (method, args) -> switch (method) {
            case "getRoom" -> type;
            case "getRoomInstance" -> instance;
            case "isDungeonHeart" -> true;
            default -> throw new AssertionError(method);
        });
    }

    // Hold the same transaction boundary as capture, then release the queued session action.
    // The action must not inspect owners or mutate storage until after the ownership change.
    private void afterCaptureWhileActionWaits(MapController map, Runnable action) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            started.countDown();
            try { action.run(); } catch (Throwable e) { failure.set(e); }
        }, "keeper-hand-action");
        worker.setDaemon(true);
        try {
            synchronized (GameWorldController.GOLD_LOCK) {
                worker.start();
                assertTrue(started.await(2, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (worker.getState() != Thread.State.BLOCKED && worker.isAlive() && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                assertEquals(Thread.State.BLOCKED, worker.getState());
                map.changeRoomOwner(new Point(1, 1), NEW);
            }
        } finally {
            worker.join(2000);
        }
        assertFalse(worker.isAlive(), "hand action did not finish");
        assertNull(failure.get());
    }

    private interface Call { Object invoke(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> type.getSimpleName();
                    default -> call.invoke(method.getName(), args);
                }));
    }
}
