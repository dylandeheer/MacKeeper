/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import com.simsilica.es.base.DefaultEntityData;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.entity.EntityController;
import toniarts.openkeeper.game.controller.player.*;
import toniarts.openkeeper.game.controller.room.*;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.logic.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;

class DungeonHeartTest {
    private static final short LOSER = 3, ATTACKER = 4;
    private final DefaultEntityData data = new DefaultEntityData();
    private final Keeper loser = new Keeper(false, LOSER), attacker = new Keeper(true, ATTACKER);
    private final Map<Short, Keeper> keepers = Map.of(LOSER, loser, ATTACKER, attacker);
    private final Map<Variable.MiscVariable.MiscType, Variable.MiscVariable> settings = new EnumMap<>(Variable.MiscVariable.MiscType.class);
    private final List<Short> defeated = new ArrayList<>();
    private final Map<Short, IPlayerController> players = new HashMap<>();
    private GameWorldController world;
    private ILevelInfo level;
    private Creature recruit;
    private Room portalType;

    @Test void lethalDamageDestroysHeartReleasesGoldAndDefeatsHumanOnce() {
        setup(false);
        IRoomController room = room(3);
        EntityId heart = heart(room);
        var furniture = List.copyOf(room.getFloorFurniture());
        assertEquals(0, world.addGold(LOSER, new Point(1, 1), 600));
        loser.setGoldMined(25);
        mana(LOSER).addMana(700);
        mana(ATTACKER).addMana(200);
        damage(heart, 1000, ATTACKER);
        assertTrue(room.isDestroyed());
        assertEquals(0, room.getHealth());
        for (Point p : room.getRoomInstance().getCoordinates()) assertEquals(0, world.getMapController().getMapData().getTile(p).getHealth());
        for (EntityId id : furniture) assertNull(data.getComponent(id, ObjectComponent.class));
        assertTrue(room.getFloorFurniture().isEmpty());
        assertNull(data.getComponent(room.getEntityId(), ImpGenerator.class));
        assertNull(players.get(LOSER).getRoomControl().getDungeonHeart());
        assertTrue(loser.isDestroyed());
        assertEquals(ATTACKER, loser.getKilledByPlayerId());
        assertEquals(List.of(LOSER), defeated);
        assertEquals(500, loser.getGold());
        assertEquals(500, loser.getGoldReserve());
        assertEquals(25, loser.getGoldMined());
        assertEquals(0, players.get(LOSER).getGoldControl().getGoldMax());
        assertEquals(600, data.findEntities(null, Gold.class).stream().mapToInt(id -> data.getComponent(id, Gold.class).gold).sum());
        assertEquals(0, loser.getMana());
        assertEquals(900, attacker.getMana());
        mana(LOSER).updateMana(500, 0);
        assertEquals(0, loser.getMana());
        assertEquals(0, loser.getManaGain());
        assertFalse(world.destroyDungeonHeart(heart, ATTACKER));
        assertFalse(world.getMapController().changeRoomOwner(new Point(3,3), ATTACKER));
        assertEquals(List.of(LOSER), defeated);
        room.construct();
        assertFalse(room.canStoreGold());
        assertTrue(room.getFloorFurniture().isEmpty());
        assertNull(data.getComponent(room.getEntityId(),ImpGenerator.class));
    }

    @Test void survivingSecondHeartKeepsPlayerAliveUntilLastHeartFalls() {
        setup(true);
        var first = room(3); var second = room(9);
        mana(LOSER).addMana(700);
        damage(heart(first), 1000, ATTACKER);
        assertFalse(loser.isDestroyed());
        assertEquals(0, loser.getKilledByPlayerId());
        assertEquals(700, loser.getMana());
        assertSame(second, players.get(LOSER).getRoomControl().getDungeonHeart());
        assertTrue(defeated.isEmpty());
        damage(heart(second), 1000, ATTACKER);
        assertTrue(loser.isDestroyed());
        assertEquals(ATTACKER, loser.getKilledByPlayerId());
        assertEquals(List.of(LOSER), defeated);
        assertEquals(700, attacker.getMana());
    }

    @Test void livingHeartCannotBeDestroyedAndUnattributedDamageDoesNotAwardMana() {
        setup(false);
        EntityId heart = heart(room(3));
        assertFalse(world.destroyDungeonHeart(heart, ATTACKER));
        damage(heart, 999, ATTACKER);
        assertEquals(1, data.getComponent(heart, Health.class).health);
        assertFalse(loser.isDestroyed());
        mana(LOSER).addMana(700);
        damage(heart, 1, (short)0);
        assertTrue(loser.isDestroyed());
        assertEquals(0, loser.getKilledByPlayerId());
        assertEquals(0, attacker.getMana());
    }

    @Test void capturedHeartDefeatsItsCurrentOwnerWithoutEndingHumanCampaign() {
        setup(false);
        IRoomController room = room(3);
        EntityId heart = heart(room);
        assertEquals(0, world.addGold(LOSER, new Point(1,1), 600));
        assertTrue(world.getMapController().changeRoomOwner(new Point(3,3), ATTACKER));
        mana(ATTACKER).addMana(700);
        damage(heart, 1000, LOSER);
        assertTrue(attacker.isDestroyed());
        assertFalse(loser.isDestroyed());
        assertTrue(defeated.isEmpty());
        assertEquals(700, loser.getMana());
        assertEquals(500, loser.getGold());
        assertEquals(0, attacker.getGold());
    }

    @Test void meleeAttacksTheHeartFootprintAndCreditsTheAttackerOnce() {
        setup(false);
        EntityId heart = heart(room(3));
        Combat combat = new Combat(new Point(1,3), 1.5f);
        combat.sensed.add(heart);
        assertEquals(heart, combat.controller.getAttackTarget().getEntityId());
        assertTrue(combat.controller.isWithinAttackDistance(heart));
        combat.controller.executeAttack(heart);
        combat.controller.executeAttack(heart); // Still cooling down.
        assertEquals(1000, data.getComponent(heart, Damage.class).damage);
        assertEquals(ATTACKER, data.getComponent(heart, Damage.class).sourcePlayerId);
        mana(LOSER).addMana(700);
        damage(heart, 0, (short)0);
        assertTrue(loser.isDestroyed());
        assertEquals(700, attacker.getMana());
        assertNull(combat.controller.getAttackTarget());
    }

    @Test void navigationChoosesReachableOuterRingAndFacesHeart() {
        setup(false);
        EntityId heart = heart(room(3));
        Combat combat = new Combat(new Point(0,0), .5f);
        combat.sensed.add(heart);
        combat.unreachable.add(new Point(1,1));
        assertEquals(heart, combat.controller.getAttackTarget().getEntityId());
        combat.controller.navigateToAttackTarget(heart);
        Navigation route = data.getComponent(combat.id, Navigation.class);
        assertNotNull(route);
        assertTrue(room(3).isTileAccessible(null,route.target));
        assertFalse(combat.unreachable.contains(route.target));
        assertEquals(new Point(3,3),route.faceTarget);
        assertNotEquals(new Point(3,3),route.target);
    }

    @Test void wallsDoorsAndDiagonalCornersBlockHeartDamage() {
        setup(false);
        EntityId heart = heart(room(3));
        Combat combat = new Combat(new Point(0,3), 5);
        combat.blocked.add(new Point(1,3));
        assertFalse(combat.controller.isWithinAttackDistance(heart));
        combat.controller.executeAttack(heart);
        assertNull(data.getComponent(heart, Damage.class));
        combat.blocked.clear();
        world.getMapController().getMapData().getTile(1,3).setTerrainId((short)2);
        assertFalse(combat.controller.isWithinAttackDistance(heart));
        world.getMapController().getMapData().getTile(1,3).setTerrainId((short)1);
        assertTrue(combat.controller.isWithinAttackDistance(heart));
        data.setComponent(combat.id,new Position(0,new com.jme3.math.Vector3f(0,0,0)));
        combat.blocked.add(new Point(1,0));
        assertFalse(combat.controller.isWithinAttackDistance(heart));
    }

    @Test void captureInvalidatesSelectedHeartBeforeAttackAndDefendersTakePriority() {
        setup(false);
        EntityId heart = heart(room(3));
        Combat combat = new Combat(new Point(1,3),1.5f);
        combat.sensed.add(heart);
        assertEquals(heart,combat.controller.getAttackTarget().getEntityId());
        EntityId defender=data.createEntity();
        data.setComponents(defender,new CreatureComponent(),new Health(100,100),new Owner(LOSER,LOSER),
                new Position(0,new com.jme3.math.Vector3f(1,0,2)));
        combat.sensed.add(defender);
        assertEquals(defender,combat.controller.getAttackTarget().getEntityId());
        data.removeEntity(defender);
        assertEquals(heart,combat.controller.getAttackTarget().getEntityId());
        world.getMapController().changeRoomOwner(new Point(3,3),ATTACKER);
        combat.controller.executeAttack(heart);
        assertNull(data.getComponent(heart,Damage.class));
        assertNull(combat.controller.getAttackTarget());
    }

    @Test void unseenUnreachableAndUnregisteredHeartObjectsAreNotTargets() {
        setup(false);
        EntityId heart = heart(room(3));
        Combat combat = new Combat(new Point(0,0),.5f);
        assertNull(combat.controller.getAttackTarget());
        combat.sensed.add(heart);
        combat.unreachable.addAll(room(3).getRoomInstance().getCoordinates());
        assertNull(combat.controller.getAttackTarget());
        combat.unreachable.clear();
        EntityId fake = data.createEntity();
        data.setComponents(fake,new ObjectComponent((short)13,null),new Health(100,100),
                new Owner(LOSER,LOSER),new Position(0,new com.jme3.math.Vector3f(0,0,0)));
        combat.sensed.clear();combat.sensed.add(fake);
        assertNull(combat.controller.getAttackTarget());
        combat.controller.executeAttack(fake);
        assertNull(data.getComponent(fake,Damage.class));
    }

    @Test void intactPortalRecruitsBeforeDefeatAndStopsAfterLastHeartFalls() {
        setup(false);
        var instance=new toniarts.openkeeper.common.RoomInstance(portalType);
        instance.addCoordinate(new Point(0,0));
        double[] lastSpawn={-100}; int[] count={0};
        var portal=proxy(ICreatureEntrance.class,(method,args)->switch(method) {
            case "isRemoved", "isDestroyed", "isDungeonHeart" -> false;
            case "getOwnerId" -> LOSER;
            case "getRoom" -> portalType;
            case "getRoomInstance" -> instance;
            case "getLastSpawnTime" -> lastSpawn[0];
            case "getEntranceCoordinate" -> new Point(0,0);
            case "onSpawn" -> { lastSpawn[0]=(double)args[0]; yield null; }
            default -> throw new AssertionError(method);
        });
        players.get(LOSER).getRoomControl().onBuild(portal);
        players.get(LOSER).getCreatureControl().setTypeAvailable(recruit,true);
        var creatures=proxy(ICreaturesController.class,(method,args)-> {
            if(method.equals("spawnCreature")) { count[0]++; return data.createEntity(); }
            throw new AssertionError(method);
        });
        var spawning=new CreatureSpawnSystem(creatures,players.values(),settings,level,world.getMapController());
        try {
            spawning.processTick(1);
            assertEquals(1,count[0]);
            damage(heart(room(3)),1000,ATTACKER);
            assertFalse(portal.isDestroyed());
            spawning.processTick(100);
            assertEquals(1,count[0]);
        } finally { spawning.stop(); }
    }

    @Test void shortRangeMeleeLeavesTheCornerForASideTileItCanHitFrom() {
        setup(false);
        EntityId heart=heart(room(3));
        Combat combat=new Combat(new Point(1,1),.5f); combat.sensed.add(heart);
        assertFalse(combat.controller.isWithinAttackDistance(heart));
        assertEquals(heart,combat.controller.getAttackTarget().getEntityId());
        combat.controller.navigateToAttackTarget(heart);
        var route=data.getComponent(combat.id,Navigation.class);
        assertNotNull(route);
        assertNotEquals(new Point(1,1),route.target);
        data.setComponent(combat.id,new Position(0,new com.jme3.math.Vector3f(route.target.x,0,route.target.y)));
        assertTrue(combat.controller.isWithinAttackDistance(heart));
    }

    @Test void activeHeartRouteIsReusedAndBlockedRouteRecoversAfterBoundedRetry() {
        setup(false);
        EntityId heart=heart(room(3));
        Combat combat=new Combat(new Point(0,0),.5f);combat.sensed.add(heart);
        assertNotNull(combat.controller.getAttackTarget());
        combat.controller.navigateToAttackTarget(heart);
        int initialSearches=combat.searches;
        for(int i=0;i<20;i++) {
            combat.time=i/20d;
            assertNotNull(combat.controller.getAttackTarget());
            combat.controller.navigateToAttackTarget(heart);
        }
        assertEquals(initialSearches,combat.searches);
        combat.unreachable.addAll(room(3).getRoomInstance().getCoordinates());
        assertNull(combat.controller.getAttackTarget());
        assertNull(data.getComponent(combat.id,Navigation.class));
        int failedSearches=combat.searches;
        assertTrue(failedSearches>initialSearches);
        for(int i=0;i<20;i++)assertNull(combat.controller.getAttackTarget());
        assertEquals(failedSearches,combat.searches);
        combat.unreachable.clear();combat.time=2;
        assertNotNull(combat.controller.getAttackTarget());
        combat.controller.navigateToAttackTarget(heart);
        assertNotNull(data.getComponent(combat.id,Navigation.class));
        assertTrue(combat.searches>failedSearches);
    }

    @Test void heroWithoutManaControlCanDestroyAHeart() {
        setup(false);
        var original=players.get(ATTACKER);
        players.put(ATTACKER,proxy(IPlayerController.class,(method,args)->switch(method) {
            case "getKeeper" -> attacker; case "getManaControl" -> null;
            case "getGoldControl" -> original.getGoldControl(); case "getRoomControl" -> original.getRoomControl();
            default -> throw new AssertionError(method);
        }));
        mana(LOSER).addMana(700);
        damage(heart(room(3)),1000,ATTACKER);
        assertTrue(loser.isDestroyed());
        assertEquals(0,attacker.getMana());
    }

    @Test void accumulatedDamageCannotOverflowIntoHealingAndUnknownFinalHitClearsAttribution() {
        setup(false);
        EntityId heart=heart(room(3));
        EntityController.setDamage(data,heart,Integer.MAX_VALUE,ATTACKER);
        EntityController.setDamage(data,heart,100,(short)0);
        assertEquals(Integer.MAX_VALUE,data.getComponent(heart,Damage.class).damage);
        assertEquals(0,data.getComponent(heart,Damage.class).sourcePlayerId);
        mana(LOSER).addMana(700);
        damage(heart,0,(short)0);
        assertTrue(loser.isDestroyed());
        assertEquals(0,attacker.getMana());
    }

    @Test void earlyDestructionDoesNotResurrectConstructionFurniture() {
        setup(false);
        var room=room(3);var furniture=List.copyOf(room.getFloorFurniture());
        var construction=new DungeonHeartConstruction(data,0);
        damage(heart(room),1000,ATTACKER);
        assertDoesNotThrow(()->construction.processTick(12));
        for(EntityId id:furniture)assertNull(data.getComponent(id,ObjectViewState.class));
        construction.stop();
    }

    @Test void maximumHealingCannotOverflowIntoLethalDamage() {
        setup(false);
        EntityId heart=heart(room(3));
        damage(heart,Integer.MIN_VALUE,(short)0);
        assertEquals(1000,data.getComponent(heart,Health.class).health);
        assertFalse(loser.isDestroyed());
    }

    private final class Combat {
        final EntityId id=data.createEntity();
        double time;
        int searches;
        final Set<EntityId> sensed=new LinkedHashSet<>();
        final Set<Point> blocked=new HashSet<>(), unreachable=new HashSet<>();
        final toniarts.openkeeper.game.controller.creature.CreatureController controller;
        Combat(Point start, float range) {
            Creature type=new Creature();type.setFightStyle(Creature.FightStyle.BLITZER);
            data.setComponents(id,new Health(100,100),new CreatureComponent(),new Owner(ATTACKER,ATTACKER),
                    new Position(0,new com.jme3.math.Vector3f(start.x,0,start.y)),new CreatureMeleeAttack(0,1000,1,range));
            var creatures=proxy(ICreaturesController.class,(method,args)->switch(method) {
                case "isValidEntity" -> data.getComponent((EntityId)args[0],CreatureComponent.class)!=null;
                case "createController" -> proxy(toniarts.openkeeper.game.controller.creature.ICreatureController.class,(m,a)-> {
                    if(m.equals("getEntityId"))return args[0]; throw new AssertionError(m);
                });
                default -> throw new AssertionError(method);
            });
            var lookup=proxy(IEntityPositionLookup.class,(method,args)->switch(method) {
                case "getSensedEntities" -> sensed;
                case "isTileBlocked" -> blocked.contains(((toniarts.openkeeper.game.map.IMapTileInformation)args[0]).getLocation());
                case "getEntityController" -> world.getObjectsController().createController((EntityId)args[0]);
                default -> throw new AssertionError(method);
            });
            var navigation=proxy(toniarts.openkeeper.game.navigation.INavigationService.class,(method,args)-> {
                if(method.equals("isAccessible")) {
                    Point p=((toniarts.openkeeper.game.map.IMapTileInformation)args[1]).getLocation();
                    return !blocked.contains(p) && !unreachable.contains(p);
                }
                if(!method.equals("findPath"))throw new AssertionError(method);
                searches++;
                if(unreachable.contains(args[1]))return null;
                var path=new com.badlogic.gdx.ai.pfa.DefaultGraphPath<toniarts.openkeeper.game.map.IMapTileInformation>();
                path.add(world.getMapController().getMapData().getTile((Point)args[0]));
                path.add(world.getMapController().getMapData().getTile((Point)args[1]));
                return path;
            });
            controller=new toniarts.openkeeper.game.controller.creature.CreatureController(id,data,type,navigation,null,()->time,settings,
                    creatures,lookup,world.getMapController(),level,world.getObjectsController(),null);
        }
    }

    private void damage(EntityId heart, int amount, short source) {
        EntityController.setDamage(data, heart, amount, source);
        var lookup = proxy(IEntityPositionLookup.class, (method,args) -> {
            if (method.equals("getEntityLocation")) {
                Position p = data.getComponent((EntityId)args[0], Position.class);
                return p == null ? null : world.getMapController().getMapData().getTile(toniarts.openkeeper.utils.WorldUtils.vectorToPoint(p.position));
            }
            throw new AssertionError(method);
        });
        var system = new HealthSystem(data, lookup, settings, null, level, players.values(), world.getMapController(), world);
        try { system.processTick(.05f); system.processTick(.05f); }
        finally { system.stop(); }
    }

    private IRoomController room(int x) { return world.getMapController().getRoomControllerByCoordinates(new Point(x,3)); }
    private EntityId heart(IRoomController room) {
        return room.getFloorFurniture().stream().filter(id -> data.getComponent(id,ObjectComponent.class).objectId == 13).findFirst().orElseThrow();
    }
    private PlayerManaControl mana(short id) { return players.get(id).getManaControl(); }
    private void value(Variable.MiscVariable.MiscType type, int amount) {
        var value = new Variable.MiscVariable(); value.setValue(amount); settings.put(type,value);
    }
    private void setup(boolean twoHearts) {
        value(Variable.MiscVariable.MiscType.ENTRANCE_GENERATION_SPEED_SECONDS, 1);
        value(Variable.MiscVariable.MiscType.MINIMUM_IMP_THRESHOLD, 0);
        value(Variable.MiscVariable.MiscType.CREATURES_SUPPORTED_BY_FIRST_PORTAL, 20);
        value(Variable.MiscVariable.MiscType.CREATURES_SUPPORTED_PER_ADDITIONAL_PORTAL, 10);
        value(Variable.MiscVariable.MiscType.TIME_BEFORE_FREE_IMP_GENERATED_SECONDS, 1);
        recruit=new Creature(); recruit.setCreatureId((short)8); recruit.setAttractions(new Creature.Attraction[0]);
        portalType=new Room(); portalType.setRoomId((short)43);
        var pool=new Variable.CreaturePool();pool.setCreatureId(8);pool.setValue(10);
        value(Variable.MiscVariable.MiscType.MAX_GOLD_PER_DUNGEON_HEART_TILE, 1000);
        value(Variable.MiscVariable.MiscType.MAX_GOLD_PILE_OUTSIDE_TREASURY, 1000);
        value(Variable.MiscVariable.MiscType.DUNGEON_HEART_OBJECT_HEALTH, 1000);
        value(Variable.MiscVariable.MiscType.DUNGEON_HEART_HEALTH_REGENERATION_PER_SECOND, 0);
        value(Variable.MiscVariable.MiscType.DUNGEON_HEART_MANA_GENERATION_INCREASE_PER_SECOND, 30);
        value(Variable.MiscVariable.MiscType.MAXIMUM_MANA_THRESHOLD, 100000);
        value(Variable.MiscVariable.MiscType.CREATURE_DYING_STATE_DURATION_SECONDS, 30);
        value(Variable.MiscVariable.MiscType.PRISON_MODIFY_CREATURE_HEALTH_PER_SECOND, 0);
        value(Variable.MiscVariable.MiscType.MODIFY_HEALTH_OF_CREATURE_IN_LAIR_PER_SECOND, 0);
        Room type = new Room(); type.setName("Dungeon Heart"); type.setRoomId((short)42);
        type.setTileConstruction(Room.TileConstruction._5_BY_5_ROTATED);
        type.setObjects(Collections.nCopies(6,(short)0)); type.setFlags(EnumSet.noneOf(Room.RoomFlag.class));
        Terrain floor = new Terrain(), tileType = new Terrain(), wall = new Terrain();
        wall.setFlags(EnumSet.of(Terrain.TerrainFlag.SOLID));
        floor.setFlags(EnumSet.noneOf(Terrain.TerrainFlag.class));
        tileType.setTerrainId((short)1); tileType.setStartingHealth(100); tileType.setMaxHealth(100);
        tileType.setFlags(EnumSet.of(Terrain.TerrainFlag.ROOM, Terrain.TerrainFlag.OWNABLE));
        GameMap map = new GameMap(twoHearts ? 13 : 7, 7);
        for (int x=0;x<map.getWidth();x++) for (int y=0;y<map.getHeight();y++) {
            var tile = new Tile(); tile.setPlayerId(LOSER);
            tile.setTerrainId((short)(y>=1 && y<=5 && (x>=1 && x<=5 || twoHearts && x>=7 && x<=11) ? 1 : 0));
            map.setTile(x,y,tile);
        }
        var kwd = proxy(IKwdFile.class, (method,args) -> switch(method) {
            case "getMap" -> map;
            case "getThings" -> List.of();
            case "getPortal" -> portalType;
            case "getCreaturePool" -> Map.of(8,pool);
            case "getObject" -> {
                short id=((Number)args[0]).shortValue(); var object=new GameObject(); object.setObjectId(id);
                object.setFlags(id==1 || id==3 ? EnumSet.of(GameObject.ObjectFlag.OBJECT_TYPE_GOLD) : EnumSet.noneOf(GameObject.ObjectFlag.class));
                object.setHp(id==13 ? 1 : 0); yield object;
            }
            case "getPlayer" -> { var p=new Player(); p.setStartingGold((short)args[0]==LOSER ? 500 : 0); yield p; }
            case "getTerrain" -> (short)args[0]==1 ? tileType : (short)args[0]==2 ? wall : floor;
            case "getRoomByTerrain", "getRoomById", "getDungeonHeart" -> type;
            default -> throw new AssertionError(method);
        });
        level=proxy(ILevelInfo.class, (method,args)->switch(method) {
            case "getLevelData" -> kwd;
            case "getPlayers" -> keepers;
            case "getPlayer" -> keepers.get((short)args[0]);
            default -> throw new AssertionError(method);
        });
        for (Keeper keeper : keepers.values()) {
            var gold=new PlayerGoldControl(keeper); var rooms=new PlayerRoomControl(keeper,List.of(type)); var mana=new PlayerManaControl(keeper,settings);
            var creatures=new PlayerCreatureControl(keeper,null,List.of(recruit));
            players.put(keeper.getId(),proxy(IPlayerController.class,(method,args)->switch(method) {
                case "getKeeper" -> keeper; case "getGoldControl" -> gold; case "getRoomControl" -> rooms; case "getManaControl" -> mana; case "getCreatureControl" -> creatures;
                default -> throw new AssertionError(method);
            }));
        }
        var game=proxy(IGameController.class,(method,args)-> {
            if (method.equals("getGameWorldController")) return world;
            if (method.equals("endGame")) { assertEquals(false,args[1]); defeated.add((short)args[0]); return null; }
            throw new AssertionError(method);
        });
        world=new GameWorldController(game,level,data,settings,players,()->0);
    }
    private interface Call { Object invoke(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->switch(method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy==args[0]; case "toString" -> type.getSimpleName();
            default -> call.invoke(method.getName(),args);
        }));
    }
}
