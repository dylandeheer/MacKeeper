/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.controller.ILevelInfo;
import toniarts.openkeeper.game.save.TriggerSnapshots;
import toniarts.openkeeper.game.trigger.TriggerGenericData;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerControl;
import static org.junit.jupiter.api.Assertions.*;

class TriggerContinuationTest {
    @Test void nestedCursorsConsumedActionsAndFiniteRepeatsContinueWithoutReplay() throws Exception {
        var a=new World(nested((short)3));a.flags.put((short)9,1);a.flags.put((short)8,1);
        a.tick();a.tick();assertEquals(1,a.flag(1));assertEquals(100,a.flag(3));
        var b=a.copy();
        for(int i=0;i<40;i++){b.reload();step(a,b);}
        // Parent re-entry decrements repeats even when resuming after a nested child.
        assertEquals(2,a.flag(1));assertEquals(20,a.flag(2));assertEquals(300,a.flag(3));
        assertEquals(0,a.control.captureState().nodes().getFirst().children().size());
        b.reload();step(a,b);assertEquals(2,b.flag(1));
    }

    @Test void foreverRepeatsAndDisabledControlsRetainTheirState() throws Exception {
        var a=new World(nested((short)255));a.flags.put((short)9,1);a.tick();a.tick();
        a.control.setEnabled(false);var b=a.copy();
        for(int i=0;i<10;i++){b.reload();step(a,b);}assertEquals(1,a.flag(1));assertFalse(b.control.isEnabled());
        a.control.setEnabled(true);b.control.setEnabled(true);
        for(int i=0;i<20;i++){b.reload();step(a,b);}assertTrue(a.flag(1)>3);assertEquals(255,a.control.captureState().nodes().get(1).repeat());
    }

    @Test void paydayObservationsAndCreationBaselineSurviveChangedLevelCycle() throws Exception {
        var payday=condition(1,TriggerGeneric.TargetType.LEVEL_PAY_DAY,(short)255,2);var a=new World(Map.of(1,payday,2,action(2,1,1)),7);
        for(int i=0;i<4;i++)a.tick();assertEquals(0,a.flag(1));a.cycle=8;a.tick();
        var b=a.copy();assertEquals(7,b.control.captureState().initialPayday());
        for(int i=0;i<8;i++){b.reload();step(a,b);}assertEquals(1,a.flag(1));
        a.cycle=11;b.cycle=11;
        for(int i=0;i<8;i++){b.reload();step(a,b);}assertEquals(2,a.flag(1));
        assertEquals(Map.of(1,11L),b.control.captureState().paydays());
    }

    @Test void synchronousBreachCanDetachTheOrdinaryCursorWithoutLosingItOnLoad() throws Exception {
        var event=condition(1,TriggerGeneric.TargetType.PLAYER_DUNGEON_BREACHED,(short)1,2);
        var a=new World(Map.of(1,event,2,action(2,1,1)));
        a.control.pointAtFirstCondition();a.control.fireBreachEvent(TriggerGeneric.TargetType.PLAYER_DUNGEON_BREACHED);
        assertEquals(1,a.flag(1));var saved=a.control.captureState();assertEquals(1,saved.cursor());assertTrue(saved.nodes().getFirst().children().isEmpty());
        var b=a.copy();for(int i=0;i<6;i++){b.reload();step(a,b);}
        a.control.fireBreachEvent(TriggerGeneric.TargetType.PLAYER_DUNGEON_BREACHED);b.control.fireBreachEvent(TriggerGeneric.TargetType.PLAYER_DUNGEON_BREACHED);
        assertEquals(1,a.flag(1));assertEquals(a.flags,b.flags);
    }

    @Test void removedLastChildAndRepeatedAuthoredIdsRetainOccurrenceIdentity() throws Exception {
        var definitions=nested((short)2);
        // The same authored subtree is copied under two parents by the loader.
        var sibling=condition(6,TriggerGeneric.TargetType.FLAG,(short)1,3);flag(sibling,9,1);
        definitions.get(1).setIdNext(6);definitions.put(6,sibling);
        var a=new World(definitions);a.flags.put((short)9,1);a.flags.put((short)8,1);
        a.tick();a.tick();a.tick();var b=a.copy();
        for(int i=0;i<45;i++){b.reload();step(a,b);}
        assertEquals(40,a.flag(2));assertEquals(a.control.captureState(),b.control.captureState());
    }

    @Test void differentDefinitionsAndIllegalProgressFailBeforeMutation() throws Exception {
        var a=new World(nested((short)3));a.flags.put((short)9,1);a.tick();a.tick();var state=a.control.captureState();
        var altered=nested((short)3);altered.get(2).setUserData("value",2);var b=new World(altered);var before=b.control.captureState();
        assertThrows(IllegalArgumentException.class,()->b.control.restoreState(state));assertEquals(before,b.control.captureState());assertTrue(b.flags.isEmpty());
        var nodes=new ArrayList<>(state.nodes());nodes.set(1,new TriggerSnapshots.Node(4,nodes.get(1).children(),nodes.get(1).last()));
        var repeats=change(state,nodes,state.cursor(),state.paydays());assertThrows(IllegalArgumentException.class,()->a.control.restoreState(repeats));
        nodes=new ArrayList<>(state.nodes());nodes.set(1,new TriggerSnapshots.Node(2,List.of(5,2),-1));var reordered=change(state,nodes,state.cursor(),state.paydays());
        assertThrows(IllegalArgumentException.class,()->a.control.restoreState(reordered));
        var invalidPayday=change(state,state.nodes(),state.cursor(),Map.of(99,0L));assertThrows(IllegalArgumentException.class,()->a.control.restoreState(invalidPayday));
        assertEquals(state,a.control.captureState());
    }

    @Test void corruptGraphsAndMalformedSectionsAreRejected() throws Exception {
        var w=new World(nested((short)2));var state=w.control.captureState();var bytes=TriggerSnapshots.encode(state);
        assertEquals(state,TriggerSnapshots.decode(bytes));
        for(int i=0;i<bytes.length;i++){var truncated=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->TriggerSnapshots.decode(truncated));}
        for(int offset:List.of(0,4)){var bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,99);assertThrows(IOException.class,()->TriggerSnapshots.decode(bad));}
        var bool=bytes.clone();bool[80]=2;assertThrows(IOException.class,()->TriggerSnapshots.decode(bool));
        assertThrows(IOException.class,()->TriggerSnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
        assertThrows(UnsupportedOperationException.class,()->state.nodes().clear());assertThrows(UnsupportedOperationException.class,()->state.nodes().getFirst().children().clear());
        var cycle=List.of(new TriggerSnapshots.Node(0,List.of(),-1),new TriggerSnapshots.Node(1,List.of(2),-1),new TriggerSnapshots.Node(1,List.of(1),-1));
        assertThrows(IllegalArgumentException.class,()->change(state,cycle,0,Map.of()));
        var duplicate=List.of(new TriggerSnapshots.Node(0,List.of(1,1),-1),new TriggerSnapshots.Node(1,List.of(),-1));
        assertThrows(IllegalArgumentException.class,()->change(state,duplicate,0,Map.of()));
        assertThrows(IllegalArgumentException.class,()->change(state,state.nodes(),2,Map.of())); // action cursor
        assertThrows(IllegalArgumentException.class,()->new TriggerSnapshots.Node(-1,List.of(1),-1));
        assertThrows(IllegalArgumentException.class,()->new TriggerSnapshots.State(1,state.definition(),true,-1,Map.of(),0,state.nodes()));
    }

    static TriggerSnapshots.State change(TriggerSnapshots.State s,List<TriggerSnapshots.Node> nodes,int cursor,Map<Integer,Long> paydays){return new TriggerSnapshots.State(s.triggerId(),s.definition(),s.enabled(),s.initialPayday(),paydays,cursor,nodes);}
    static void step(World a,World b)throws Exception{a.tick();b.tick();assertEquals(a.flags,b.flags);assertEquals(a.control.captureState(),b.control.captureState());}
    static Map<Integer,Trigger> nested(short repeats) {
        var outer=condition(1,TriggerGeneric.TargetType.FLAG,repeats,2);flag(outer,9,1);
        var inner=condition(3,TriggerGeneric.TargetType.FLAG,(short)2,4);flag(inner,8,1);
        var first=action(2,1,1);first.setIdNext(3);inner.setIdNext(5);
        return new HashMap<>(Map.of(1,outer,2,first,3,inner,4,action(4,2,10),5,action(5,3,100)));
    }
    static TriggerGeneric condition(int id,TriggerGeneric.TargetType type,short repeats,int child){var t=new TriggerGeneric(null);t.setId(id);t.setType(type);t.setRepeatTimes(repeats);t.setIdChild(child);return t;}
    static void flag(TriggerGeneric t,int flag,int value){t.setTargetValueComparison(TriggerGeneric.ComparisonType.EQUAL_TO);t.setUserData("targetId",(short)flag);t.setUserData("flag",(short)1);t.setUserData("value",value);}
    static TriggerAction action(int id,int flag,int value){var t=new TriggerAction(null);t.setId(id);t.setType(TriggerAction.ActionType.FLAG);t.setUserData("flagId",(short)flag);t.setUserData("flag",(short)TriggerAction.FlagTargetValueActionType.PLUS.getFlagValue());t.setUserData("value",value);return t;}
    static final class World {
        final Map<Integer,Trigger> definitions;final Map<Short,Integer> flags=new HashMap<>();long cycle;final ILevelInfo info;Control control;
        World(Map<Integer,Trigger> definitions){this(definitions,0);}
        World(Map<Integer,Trigger> definitions,long cycle){this.definitions=definitions;this.cycle=cycle;
            var kwd=proxy(IKwdFile.class,(n,a)->{if(n.equals("getTriggers"))return definitions;throw new AssertionError(n);});
            info=proxy(ILevelInfo.class,(n,a)->switch(n){case "getLevelData"->kwd;case "getPaydayCycle"->this.cycle;case "getFlag"->flag(((Number)a[0]).intValue());case "setFlag"->{flags.put(((Number)a[0]).shortValue(),(int)a[1]);yield null;}default->throw new AssertionError(n);});control=new Control(info);
        }
        int flag(int id){return flags.getOrDefault((short)id,0);}void tick(){control.update(.25f);}
        World copy()throws Exception{var saved=TriggerSnapshots.decode(TriggerSnapshots.encode(control.captureState()));var w=new World(definitions,cycle);w.flags.putAll(flags);w.control.restoreState(saved);assertEquals(saved,w.control.captureState());assertEquals(flags,w.flags);return w;}
        void reload()throws Exception{var saved=TriggerSnapshots.decode(TriggerSnapshots.encode(control.captureState()));var before=new HashMap<>(flags);control=new Control(info);control.restoreState(saved);assertEquals(saved,control.captureState());assertEquals(before,flags);}
    }
    static final class Control extends PlayerTriggerControl {
        Control(ILevelInfo info){super(null,info,()->0,null,null,1,(short)3,null);}
        void pointAtFirstCondition(){trigger=(TriggerGenericData)root.getChild(0);}
    }
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,BiFunction<String,Object[],Object> call){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->call.apply(m.getName(),a));}
}
