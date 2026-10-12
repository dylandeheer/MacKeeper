/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.trigger.TriggerGenericData;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerControl;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.ObjectiveDisplayTest.proxy;

class KillTriggerTest {
    @Test void lossesUseAllDeathsOrTheSpecifiedKillersVictimLedger() {
        Keeper victim = new Keeper(false,(short)3), killer = new Keeper(false,(short)4);
        victim.recordCreatureLoss(); victim.recordCreatureLoss(); victim.recordCreatureLoss();
        killer.recordCreatureKill((short)3,false); killer.recordCreatureKill((short)5,false);
        Map<Short,Keeper> players = Map.of((short)3,victim,(short)4,killer);
        for (short filter : new short[]{0,4,5}) {
            int count = filter == 0 ? 3 : filter == 4 ? 1 : 0;
            verifyComparisons(players,TriggerGeneric.TargetType.PLAYER_CREATURES_KILLED,"killerPlayerId",filter,count);
        }
    }

    @Test void killsUseVictimFilterIncludingHeroesOrNativeNonHeroEnemyTotal() {
        Keeper player = new Keeper(false,(short)3);
        player.recordCreatureKill((short)1,true); player.recordCreatureKill((short)1,true);
        player.recordCreatureKill((short)4,false); player.recordCreatureKill((short)3,false);
        for (short filter : new short[]{0,1,3,4,5}) {
            int count = filter == 1 ? 2 : filter == 5 ? 0 : 1;
            verifyComparisons(Map.of((short)3,player),TriggerGeneric.TargetType.PLAYER_KILLS_CREATURES,"victimPlayerId",filter,count);
        }
    }

    static void verifyComparisons(Map<Short,Keeper> players,TriggerGeneric.TargetType type,String key,short filter,int count) {
        for (var comparison : TriggerGeneric.ComparisonType.values()) for(int required=0;required<=4;required++) {
            TriggerGeneric trigger=new TriggerGeneric(null); trigger.setId(1); trigger.setType(type);
            trigger.setTargetValueComparison(comparison); trigger.setUserData(key,filter); trigger.setUserData("value",required);
            trigger.setUserData("flag",(short)0); trigger.setUserData("playerId",(short)99); trigger.setUserData("targetId",(short)88);
            IKwdFile kwd=proxy(IKwdFile.class,(name,args)-> { assertEquals("getTriggers",name);return Map.of(1,trigger); });
            ILevelInfo info=proxy(ILevelInfo.class,(name,args)->switch(name) {
                case "getLevelData"->kwd; case "getPaydayCycle"->0L;case "getPlayer"->players.get(args[0]);
                default->throw new AssertionError(name);
            });
            boolean expected=switch(comparison) {
                case EQUAL_TO->count==required;case NOT_EQUAL_TO->count!=required;
                case GREATER_THAN->count>required;case GREATER_OR_EQUAL_TO->count>=required;
                case LESS_THAN->count<required;case LESS_OR_EQUAL_TO->count<=required;case NONE->false;
            };
            assertEquals(expected,new Control(info).active(),type+" "+filter+" "+comparison+" "+required);
        }
    }
    static class Control extends PlayerTriggerControl {
        Control(ILevelInfo info) { super(null,info,()->0,null,null,1,(short)3,null); }
        boolean active() { return isActive((TriggerGenericData)root.getChild(0)); }
    }
}
