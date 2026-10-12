/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.base.DefaultEntityData;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.trigger.player.*;
import toniarts.openkeeper.utils.Point;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.proxy;

class DungeonBreachTest {
    @Test void onlyDestructionOfAnotherPlayersOwnedWallEmitsBothEvents() {
        try (Fixture f=new Fixture()) {
            int[] emitted={0};
            f.map.addBreachListener((defender,attacker)->{
                assertEquals(1,f.map.getMapData().getTile(1,1).getTerrainId());
                assertEquals(0,f.map.getMapData().getTile(1,1).getHealth());
                emitted[0]++;
            });
            f.logic.start(); f.logic.start(); // Registration is idempotent.
            f.logic.processTick(.1f);
            assertTrue(f.flags.isEmpty());
            f.map.damageTile(new Point(1,1),(short)4,null);
            assertEquals(50,f.map.getMapData().getTile(1,1).getHealth());
            assertTrue(f.flags.isEmpty());
            f.map.damageTile(new Point(1,1),(short)4,null);
            assertEquals(0,f.map.getMapData().getTile(1,1).getTerrainId());
            assertEquals(1,emitted[0]);
            assertEquals(Map.of((short)3,1,(short)4,1),f.flags);
            for(int i=0;i<10;i++)f.logic.processTick(.1f);
            assertEquals(Map.of((short)3,1,(short)4,1),f.flags);
            f.wall((short)3); f.breakWall((short)4);
            assertEquals(Map.of((short)3,1,(short)4,1),f.flags); // One-shot actions.
        }
    }

    @Test void ownWallRockFloorAndScriptedChangesDoNotEmitBreachEvents() {
        try (Fixture f=new Fixture()) {
            List<String> events=new ArrayList<>();
            f.map.addBreachListener((defender,attacker)->events.add(defender+":"+attacker));
            f.breakWall((short)3);
            f.map.alterTerrain(new Point(1,1),(short)2,(short)3);f.breakWall((short)4);
            f.map.alterTerrain(new Point(1,1),(short)3,(short)3);f.breakWall((short)4);
            f.wall((short)3);f.map.alterTerrain(new Point(1,1),(short)0,(short)4);
            assertTrue(events.isEmpty());
        }
    }

    @Test void stopRemovesListenerAndEventsBeforeStartAreNotReplayed() {
        try(Fixture f=new Fixture()) {
            f.breakWall((short)4);f.logic.start();f.logic.processTick(.1f);
            assertTrue(f.flags.isEmpty());
            f.logic.stop();f.wall((short)3);f.breakWall((short)4);
            assertTrue(f.flags.isEmpty());
            f.logic.start();f.wall((short)3);f.breakWall((short)4);
            assertEquals(Map.of((short)3,1,(short)4,1),f.flags);
        }
    }

    @Test void eventsIgnoreEncodedPlayerByteAndHonorFiniteAndInfiniteRepeats() {
        for(short repeats:new short[]{1,2,255})try(Fixture f=new Fixture(repeats)) {
            var control=new PlayerTriggerControl(null,f.info,()->0,f.map,null,1,(short)3,null);
            for(int i=1;i<=4;i++) {
                control.fireBreachEvent(TriggerGeneric.TargetType.PLAYER_DUNGEON_BREACHED);
                assertEquals(repeats==255?i:Math.min(i,repeats),f.flags.get((short)3));
                control.update(.1f);
            }
            assertThrows(IllegalArgumentException.class,()->control.fireBreachEvent(TriggerGeneric.TargetType.PLAYER_KILLED));
        }
    }

    @Test void descendantsAreCheckedAtTheEventAndNestedEventsAreNotRootSubscriptions() {
        try(Fixture f=new Fixture()) {
            TriggerGeneric event=(TriggerGeneric)f.triggers.get(1);
            TriggerGeneric gate=condition(5,TriggerGeneric.TargetType.FLAG,(short)1,2);
            gate.setTargetValueComparison(TriggerGeneric.ComparisonType.EQUAL_TO);
            gate.setUserData("targetId",(short)9);gate.setUserData("flag",(short)1);gate.setUserData("value",1);
            event.setIdChild(5);f.triggers.put(5,gate);
            var control=new PlayerTriggerControl(null,f.info,()->0,f.map,null,1,(short)3,null);
            control.fireBreachEvent(TriggerGeneric.TargetType.PLAYER_DUNGEON_BREACHED);
            assertTrue(f.flags.isEmpty());
            f.flags.put((short)9,1);for(int i=0;i<10;i++)control.update(.1f);
            assertNull(f.flags.get((short)3)); // The earlier event is not latched.
            control.fireBreachEvent(TriggerGeneric.TargetType.PLAYER_DUNGEON_BREACHED);
            assertEquals(1,f.flags.get((short)3));
            // Put the event under an ordinary root; native event dispatch scans roots only.
            gate.setIdChild(1);event.setIdChild(2);f.flags.remove((short)3);
            var nested=new PlayerTriggerControl(null,f.info,()->0,f.map,null,5,(short)3,null);
            nested.fireBreachEvent(TriggerGeneric.TargetType.PLAYER_DUNGEON_BREACHED);
            for(int i=0;i<10;i++)nested.update(.1f);
            assertNull(f.flags.get((short)3));
        }
    }

    @Test void defenderActionsRunBeforeLowerIdAttackerConditions() {
        try (Fixture f=new Fixture()) {
            ((TriggerGeneric)f.triggers.get(1)).setType(TriggerGeneric.TargetType.PLAYER_ENEMY_BREACHED);
            ((TriggerGeneric)f.triggers.get(3)).setType(TriggerGeneric.TargetType.PLAYER_DUNGEON_BREACHED);
            TriggerGeneric gate=condition(5,TriggerGeneric.TargetType.FLAG,(short)1,2);
            gate.setTargetValueComparison(TriggerGeneric.ComparisonType.EQUAL_TO);
            gate.setUserData("targetId",(short)4);gate.setUserData("flag",(short)1);gate.setUserData("value",1);
            ((TriggerGeneric)f.triggers.get(1)).setIdChild(5);f.triggers.put(5,gate);
            var logic=new PlayerTriggerLogicController(null,f.info,()->0,f.map,null,null);
            logic.start();
            try {
                f.wall((short)4);f.breakWall((short)3);
                assertEquals(Map.of((short)3,1,(short)4,1),f.flags);
            } finally {logic.stop();}
        }
    }

    static TriggerGeneric condition(int id,TriggerGeneric.TargetType type,short repeats,int child) {
        var t=new TriggerGeneric(null);t.setId(id);t.setType(type);t.setRepeatTimes(repeats);t.setIdChild(child);
        t.setUserData("playerId",(short)99);return t;
    }
    static TriggerAction action(int id,short flag) {
        var t=new TriggerAction(null);t.setId(id);t.setType(TriggerAction.ActionType.FLAG);
        t.setUserData("flagId",flag);t.setUserData("flag",(short)18);t.setUserData("value",1);return t;
    }
    static class Fixture implements AutoCloseable {
        final DefaultEntityData data=new DefaultEntityData();
        final Map<Short,Integer> flags=new HashMap<>();
        final Map<Integer,Trigger> triggers=new HashMap<>();
        final ILevelInfo info;final MapController map;final PlayerTriggerLogicController logic;
        Fixture(){this((short)1);}
        Fixture(short repeats) {
            triggers.put(1,condition(1,TriggerGeneric.TargetType.PLAYER_DUNGEON_BREACHED,repeats,2));
            triggers.put(2,action(2,(short)3));
            triggers.put(3,condition(3,TriggerGeneric.TargetType.PLAYER_ENEMY_BREACHED,repeats,4));
            triggers.put(4,action(4,(short)4));
            Map<Short,Keeper> keepers=Map.of((short)3,new Keeper(false,(short)3),(short)4,new Keeper(false,(short)4));
            Map<Short,Player> players=new HashMap<>();
            for(short id:new short[]{3,4}) {Player p=new Player();p.setPlayerId(id);p.setTriggerId(id==3?1:3);players.put(id,p);}
            Terrain[] terrains=new Terrain[4];
            for(short i=0;i<4;i++) {
                Terrain t=new Terrain();t.setTerrainId(i);t.setStartingHealth(100);t.setMaxHealth(100);t.setDestroyedTypeTerrainId((short)0);
                t.setFlags(i==1?EnumSet.of(Terrain.TerrainFlag.SOLID,Terrain.TerrainFlag.OWNABLE):i==2?EnumSet.of(Terrain.TerrainFlag.SOLID):i==3?EnumSet.of(Terrain.TerrainFlag.OWNABLE):EnumSet.noneOf(Terrain.TerrainFlag.class));terrains[i]=t;
            }
            GameMap source=new GameMap(3,3);
            for(int x=0;x<3;x++)for(int y=0;y<3;y++){Tile t=new Tile();t.setTerrainId((short)(x==1&&y==1?1:0));t.setPlayerId((short)3);source.setTile(x,y,t);}
            IKwdFile kwd=proxy(IKwdFile.class,(name,args)->switch(name){
                case "getMap"->source;case "getTerrain"->terrains[(short)args[0]];case "getTerrainList"->Arrays.asList(terrains);
                case "getGameLevel"->new GameLevel();case "getPlayers"->players;case "getTriggers"->triggers;
                case "getTerrainBridge","getDungeonHeart"->null;default->throw new AssertionError(name);
            });
            info=proxy(ILevelInfo.class,(name,args)->switch(name){
                case "getPlayers"->keepers;case "getPlayer"->keepers.get(args[0]);case "getLevelData"->kwd;case "getPaydayCycle"->0L;
                case "getFlag"->flags.getOrDefault(((Number)args[0]).shortValue(),0);case "setFlag"->{flags.put(((Number)args[0]).shortValue(),(int)args[1]);yield null;}
                default->throw new AssertionError(name);
            });
            Map<Variable.MiscVariable.MiscType,Variable.MiscVariable> settings=new EnumMap<>(Variable.MiscVariable.MiscType.class);
            for(var type:List.of(Variable.MiscVariable.MiscType.DIG_ENEMY_WALL_HEALTH,Variable.MiscVariable.MiscType.DIG_OWN_WALL_HEALTH,Variable.MiscVariable.MiscType.DIG_ROCK_HEALTH,Variable.MiscVariable.MiscType.ATTACK_TILE_HEALTH)) {
                var v=new Variable.MiscVariable();v.setVariableId(type);v.setValue(50);settings.put(type,v);
            }
            map=new MapController(kwd,null,settings,()->0,data,info);
            logic=new PlayerTriggerLogicController(null,info,()->0,map,null,null);
        }
        void wall(short owner){map.alterTerrain(new Point(1,1),(short)1,owner);}
        void breakWall(short attacker){map.damageTile(new Point(1,1),attacker,null);map.damageTile(new Point(1,1),attacker,null);}
        public void close(){logic.stop();data.close();}
    }
}
