/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.trigger.TriggerGenericData;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerControl;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.proxy;

class DefenseConditionsTest {
    private static final short OWNER = 3;
    private static IKwdFile definitions() {
        var boulder = new Trap(); boulder.setTrapId((short) 2); boulder.setShotTypeId((short) 26);
        return proxy(IKwdFile.class, (name, args) -> name.equals("getTrapById") && ((Number) args[0]).shortValue() == 2 ? boulder : null);
    }
    private static EntityId door(GameEntityData data, int owner, int type, boolean blueprint) {
        var id = data.createEntity(); data.setComponents(id, new Owner((short) owner,(short) 6), new DoorComponent((short) type,false,blueprint));return id;
    }
    private static EntityId trap(GameEntityData data, int owner, int type, boolean blueprint) {
        var id = data.createEntity(); data.setComponents(id, new Owner((short) owner,(short) 6), new TrapComponent((short) type), new TrapViewState((short) type,blueprint));return id;
    }
    @Test void countsUseOwnedEntitiesAndNativeFlagPrecedenceRatherThanStockOrTypeFilter() {
        var data = new GameEntityData();
        try {
            door(data,3,1,false);door(data,3,2,true);door(data,4,1,false);
            trap(data,3,1,false);trap(data,3,2,true);trap(data,4,1,false);
            var weapon = door(data,3,3,false);data.setComponent(weapon,new TrapComponent((short)1));
            assertEquals(3,DefenseCounts.doors(data,OWNER,(short)0,1));
            assertEquals(1,DefenseCounts.doors(data,OWNER,(short)1,1));
            assertEquals(1,DefenseCounts.doors(data,OWNER,(short)99,9));
            assertEquals(2,DefenseCounts.doors(data,OWNER,(short)99,17));
            assertEquals(1,DefenseCounts.doors(data,OWNER,(short)99,25));
            assertEquals(2,DefenseCounts.traps(data,definitions(),OWNER,(short)0,1));
            assertEquals(1,DefenseCounts.traps(data,definitions(),OWNER,(short)1,1));
            assertEquals(1,DefenseCounts.traps(data,definitions(),OWNER,(short)99,9));
            assertEquals(1,DefenseCounts.traps(data,definitions(),OWNER,(short)99,17));
            assertEquals(1,DefenseCounts.traps(data,definitions(),OWNER,(short)99,25));
        } finally {data.close();}
    }
    @Test void boulderObjectsOnlyContributeToUnqualifiedMatchingTypeCount() {
        var data = new GameEntityData();
        try {
            trap(data,3,2,false);
            for (int owner : new int[]{3,3,4}) data.setComponents(data.createEntity(),new Owner((short)owner,(short)owner),new ObjectComponent((short)74,null));
            data.setComponents(data.createEntity(),new Owner(OWNER,OWNER),new ObjectComponent((short)75,null));
            assertEquals(3,DefenseCounts.traps(data,definitions(),OWNER,(short)2,1));
            assertEquals(1,DefenseCounts.traps(data,definitions(),OWNER,(short)0,1));
            assertEquals(1,DefenseCounts.traps(data,definitions(),OWNER,(short)2,17));
            assertEquals(0,DefenseCounts.traps(data,definitions(),OWNER,(short)2,9));
        } finally {data.close();}
    }
    @Test void countsObserveConstructionOwnershipAndRemovalImmediatelyAndAfterArchiveRestore() throws Exception {
        var data = new GameEntityData();
        try {
            var door = door(data,3,1,true);var trap = trap(data,3,1,true);
            var restored = GameEntityData.restore(EntitySnapshots.decode(EntitySnapshots.encode(data.capture())));
            try {
                for(var world : List.of(data,restored)) {
                    assertEquals(1,DefenseCounts.doors(world,OWNER,(short)0,9));
                    assertEquals(1,DefenseCounts.traps(world,definitions(),OWNER,(short)0,9));
                    world.setComponent(door,new DoorComponent((short)1,false,false));
                    world.setComponent(trap,new TrapViewState((short)1,false));
                    assertEquals(1,DefenseCounts.doors(world,OWNER,(short)0,17));
                    assertEquals(1,DefenseCounts.traps(world,definitions(),OWNER,(short)0,17));
                    world.setComponent(door,new Owner((short)4,(short)4));world.removeEntity(trap);
                    assertEquals(0,DefenseCounts.doors(world,OWNER,(short)0,1));
                    assertEquals(0,DefenseCounts.traps(world,definitions(),OWNER,(short)0,1));
                }
                assertArrayEquals(EntitySnapshots.encode(data.capture()),EntitySnapshots.encode(restored.capture()));
            } finally {restored.close();}
        } finally {data.close();}
    }
    @Test void parsedPredicatesUseLiteralComparisonAndNoComparisonMeansDiscoveredAvailability() {
        var keeper = new Keeper(false,OWNER);
        var game = proxy(IGameController.class,(name,args)-> {
            assertTrue(name.equals("countDoors") || name.equals("countTraps"));
            assertEquals(OWNER,args[0]);assertEquals((short)2,args[1]);assertEquals(17,args[2]);return 2;
        });
        for(var type:List.of(TriggerGeneric.TargetType.PLAYER_DOORS,TriggerGeneric.TargetType.PLAYER_TRAPS)) {
            var list = type == TriggerGeneric.TargetType.PLAYER_DOORS ? keeper.getAvailableDoors() : keeper.getAvailableTraps();
            var research = new ResearchableEntity((short)2,false,type == TriggerGeneric.TargetType.PLAYER_DOORS ? ResearchableType.DOOR : ResearchableType.TRAP);
            list.add(research);
            assertFalse(condition(game,keeper,type,TriggerGeneric.ComparisonType.NONE,(short)2,0).active());
            research.setDiscovered(true);
            assertTrue(condition(game,keeper,type,TriggerGeneric.ComparisonType.NONE,(short)2,0).active());
            assertTrue(condition(game,keeper,type,TriggerGeneric.ComparisonType.NONE,(short)0,0).active());
            assertFalse(condition(game,keeper,type,TriggerGeneric.ComparisonType.NONE,(short)1,0).active());
            assertTrue(condition(game,keeper,type,TriggerGeneric.ComparisonType.GREATER_OR_EQUAL_TO,(short)2,2).active());
            assertFalse(condition(game,keeper,type,TriggerGeneric.ComparisonType.GREATER_THAN,(short)2,2).active());
            assertTrue(condition(game,keeper,type,TriggerGeneric.ComparisonType.LESS_THAN,(short)2,3).active());
            list.clear();assertFalse(condition(game,keeper,type,TriggerGeneric.ComparisonType.NONE,(short)0,0).active());
        }
    }
    private static Control condition(IGameController game, Keeper keeper, TriggerGeneric.TargetType type, TriggerGeneric.ComparisonType comparison, short target,int value) {
        var trigger = new TriggerGeneric(null);trigger.setId(1);trigger.setType(type);trigger.setTargetValueComparison(comparison);
        trigger.setUserData("targetId",target);trigger.setUserData("flag",(short)17);trigger.setUserData("playerId",(short)6);trigger.setUserData("value",value);
        var kwd = proxy(IKwdFile.class,(name,args)->Map.of(1,trigger));
        var level = proxy(ILevelInfo.class,(name,args)-> switch(name) {case "getLevelData" -> kwd;case "getPlayer" -> {assertEquals(OWNER,args[0]);yield keeper;}case "getPaydayCycle" -> 0L;default -> throw new AssertionError(name);});
        return new Control(game,level);
    }
    static class Control extends PlayerTriggerControl {
        Control(IGameController game,ILevelInfo level){super(game,level,()->0,null,null,1,OWNER,null);}
        boolean active(){return isActive((TriggerGenericData)root.getChild(0));}
    }
}
