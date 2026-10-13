/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector2f;
import com.simsilica.es.EntityId;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.CreatureState;
import toniarts.openkeeper.game.controller.player.PlayerManaControl;
import toniarts.openkeeper.game.data.Keeper;
import toniarts.openkeeper.game.logic.*;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.task.TaskType;
import static org.junit.jupiter.api.Assertions.*;

class PeriodicSystemsContinuationTest {
    @Test void pendingManaTransferRemovalAndAdditionWaitForTheSavedBoundaryAcrossRepeatedLoads() throws Exception {
        try(var source=new ManaWorld()) {
            source.system.processTick(.75f);assertTrue(source.events.isEmpty());
            source.data.setComponents(new EntityId(0),new Owner((short)4,(short)4),new Mana(12));
            source.data.removeEntity(new EntityId(1));
            source.data.setComponent(new EntityId(2),new Owner((short)3,(short)3));
            var added=source.data.createEntity();source.data.setComponents(added,new Owner((short)4,(short)4),new Mana(-2));
            var pending=source.system.captureState();assertEquals(.75f,pending.tick());assertEquals(3,pending.sources().size());assertEquals(8,pending.gains().get((short)3));
            assertEquals(Set.of(new EntityId(0),new EntityId(1),new EntityId(2)),new HashSet<>(pending.dirty()));
            try(var copy=new ManaWorld(source)) {
                assertEquals(pending,copy.system.captureState());assertTrue(copy.events.isEmpty());
                for(float tpf:new float[]{.125f,.125f,.5f,.5f,1.75f,.125f,.125f}) {
                    copy.reload();source.system.processTick(tpf);copy.system.processTick(tpf);sameMana(source,copy);
                }
                assertFalse(copy.events.isEmpty());assertEquals(17,copy.keepers.get((short)3).getManaGain());
                assertEquals(12,copy.keepers.get((short)4).getManaGain());assertEquals(2,copy.keepers.get((short)4).getManaLoose());
                assertEquals(0,copy.system.captureState().losses().get((short)3)); // Retain a real zero-valued aggregate entry.
            }
        }
    }
    @Test void manaEqualValueEventsAndChangesAfterLoadSurviveWithoutEarlyCredits() throws Exception {
        try(var source=new ManaWorld()) {
            source.system.processTick(.625f);source.data.setComponent(new EntityId(0),new Mana(8));
            try(var copy=new ManaWorld(source)) {
                assertEquals(List.of(new EntityId(0)),copy.system.captureState().dirty());
                source.data.setComponent(new EntityId(0),new Mana(-5));copy.data.setComponent(new EntityId(0),new Mana(-5));
                source.system.processTick(.125f);copy.system.processTick(.125f);sameMana(source,copy);assertTrue(copy.events.isEmpty());
                copy.reload();source.system.processTick(.25f);copy.system.processTick(.25f);sameMana(source,copy);
                assertEquals(0,copy.keepers.get((short)3).getManaGain());assertEquals(8,copy.keepers.get((short)3).getManaLoose());
                int count=copy.events.size();copy.reload();assertEquals(count,copy.events.size());
                source.system.processTick(3.5f);copy.system.processTick(3.5f);sameMana(source,copy);
                assertEquals(2.5f,copy.system.captureState().tick()); // Ordinary code services one interval per tick.
                copy.reload();source.system.processTick(.1f);copy.system.processTick(.1f);sameMana(source,copy);
            }
        }
    }
    @Test void workTrainingAndRealLevelUpProduceTheSameComponentsAfterRepeatedRestoration() throws Exception {
        try(var source=new ExperienceWorld()) {
            source.system.processTick(.25f);source.system.processTick(.5f);
            assertFalse(source.system.captureState().workingTime().isEmpty());
            try(var copy=new ExperienceWorld(source)) {
                assertEquals(source.system.captureState(),copy.system.captureState());
                for(float dt:new float[]{.25f,.5f,.75f,1.5f,.125f,2.5f,.25f}) {
                    copy.reload();source.system.processTick(dt);copy.system.processTick(dt);sameExperience(source,copy);
                }
                assertTrue(copy.data.getComponent(new EntityId(0),CreatureExperience.class).level>1);
                assertTrue(copy.data.getComponent(new EntityId(1),CreatureExperience.class).level>1);
                assertEquals(9,copy.system.captureState().impGain());
            }
        }
    }
    @Test void pendingTrainingExitRemovalAndNewMembershipRetainTheOrdinaryTimerResetOrder() throws Exception {
        try(var source=new ExperienceWorld()) {
            source.system.processTick(.25f);source.system.processTick(.5f);
            var inactive=source.spawn((short)8);source.data.removeComponent(inactive,CreatureAi.class);
            source.system.processTick(.125f);
            source.data.removeComponent(new EntityId(1),TaskComponent.class);
            source.data.removeEntity(new EntityId(2));
            source.data.setComponent(inactive,new CreatureAi(0,CreatureState.MELEE_ATTACK,(short)8));
            var pending=source.system.captureState();assertTrue(pending.training().contains(new EntityId(1)));assertTrue(pending.experience().contains(new EntityId(2)));assertFalse(pending.experience().contains(inactive));
            try(var copy=new ExperienceWorld(source)) {
                copy.reload();assertEquals(pending,copy.system.captureState());
                source.system.processTick(.25f);copy.system.processTick(.25f);sameExperience(source,copy);
                assertFalse(copy.system.captureState().workingTime().containsKey(new EntityId(1)));
                assertFalse(copy.system.captureState().workingTime().containsKey(new EntityId(2)));
                assertEquals(0,copy.system.captureState().workingTime().get(inactive));
                for(var world:List.of(source,copy))world.data.setComponent(new EntityId(1),new TaskComponent(0,null,null,TaskType.TRAIN,0,true));
                copy.reload();source.system.processTick(.25f);copy.system.processTick(.25f);sameExperience(source,copy);
                assertEquals(0,copy.system.captureState().workingTime().get(new EntityId(1)));
            }
        }
    }
    @Test void trainingExitAndReentryBeforeOneTickDoNotInventAnIntermediateReset() throws Exception {
        try(var source=new ExperienceWorld()) {
            source.system.processTick(.25f);source.system.processTick(.5f);
            double prior=source.system.captureState().workingTime().get(new EntityId(1));assertEquals(.5,prior);
            source.data.removeComponent(new EntityId(1),TaskComponent.class);
            source.data.setComponent(new EntityId(1),new TaskComponent(0,null,null,TaskType.TRAIN,0,true));
            try(var copy=new ExperienceWorld(source)) {
                source.system.processTick(.25f);copy.system.processTick(.25f);sameExperience(source,copy);
                assertEquals(.75,copy.system.captureState().workingTime().get(new EntityId(1)));
            }
        }
    }
    @Test void codecsRejectEveryTruncationInvalidValuesTotalsAndMemberships() throws Exception {
        try(var mana=new ManaWorld();var xp=new ExperienceWorld()) {
            mana.system.processTick(.25f);xp.system.processTick(.25f);
            var m=mana.system.captureState();var x=xp.system.captureState();
            byte[] mb=ManaSnapshots.encode(m),xb=ExperienceSnapshots.encode(x);
            assertEquals(m,ManaSnapshots.decode(mb));assertEquals(x,ExperienceSnapshots.decode(xb));
            for(int i=0;i<mb.length;i++){var cut=Arrays.copyOf(mb,i);assertThrows(IOException.class,()->ManaSnapshots.decode(cut));}
            for(int i=0;i<xb.length;i++){var cut=Arrays.copyOf(xb,i);assertThrows(IOException.class,()->ExperienceSnapshots.decode(cut));}
            for(int offset:new int[]{0,4}){
                var bad=mb.clone();java.nio.ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class,()->ManaSnapshots.decode(bad));
                var other=xb.clone();java.nio.ByteBuffer.wrap(other).putInt(offset,-1);assertThrows(IOException.class,()->ExperienceSnapshots.decode(other));
            }
            assertThrows(IOException.class,()->ManaSnapshots.decode(Arrays.copyOf(mb,mb.length+1)));assertThrows(IOException.class,()->ExperienceSnapshots.decode(Arrays.copyOf(xb,xb.length+1)));
            assertThrows(IllegalArgumentException.class,()->new ManaSnapshots.State(Float.NaN,m.players(),m.sources(),m.gains(),m.losses(),m.dirty()));
            assertThrows(IllegalArgumentException.class,()->new ManaSnapshots.State(0,m.players(),m.sources(),Map.of(),m.losses(),m.dirty()));
            assertThrows(IllegalArgumentException.class,()->new ManaSnapshots.State(0,m.players(),m.sources(),m.gains(),m.losses(),List.of(new EntityId(99))));
            assertThrows(IllegalArgumentException.class,()->new ExperienceSnapshots.State(9,List.of(new EntityId(1),new EntityId(0)),List.of(),Map.of()));
            assertThrows(IllegalArgumentException.class,()->new ExperienceSnapshots.State(9,x.experience(),x.training(),Map.of(new EntityId(99),.5)));
            assertThrows(IllegalArgumentException.class,()->new ExperienceSnapshots.State(9,x.experience(),x.training(),Map.of(new EntityId(0),Double.POSITIVE_INFINITY)));
            assertThrows(UnsupportedOperationException.class,()->m.sources().clear());assertThrows(UnsupportedOperationException.class,()->x.workingTime().clear());
        }
    }
    @Test void bindingRejectsUnallocatedEntitiesAndChangedPlayerSetsWithoutWrites() throws Exception {
        try(var mana=new ManaWorld();var xp=new ExperienceWorld()) {
            var badMana=new ManaSnapshots.State(0,List.of((short)3,(short)4),Map.of(new EntityId(999),new ManaSnapshots.Source((short)3,2)),Map.of((short)3,2),Map.of(),List.of());
            var before=EntitySnapshots.encode(mana.data.capture());
            assertThrows(IllegalArgumentException.class,()->ManaCalculatorLogic.restoreState(mana.players,mana.data,badMana));
            assertThrows(IllegalArgumentException.class,()->ManaCalculatorLogic.restoreState(mana.players.subList(0,1),mana.data,mana.system.captureState()));
            assertArrayEquals(before,EntitySnapshots.encode(mana.data.capture()));assertTrue(mana.events.isEmpty());
            var badXp=new ExperienceSnapshots.State(9,List.of(new EntityId(999)),List.of(),Map.of());before=EntitySnapshots.encode(xp.data.capture());
            assertThrows(IllegalArgumentException.class,()->CreatureExperienceSystem.restoreState(xp.data,xp.definition,xp.creatures,badXp));assertArrayEquals(before,EntitySnapshots.encode(xp.data.capture()));
        }
    }
    static void sameMana(ManaWorld a,ManaWorld b)throws Exception {
        assertEquals(a.system.captureState(),b.system.captureState());assertEquals(a.events,b.events);
        for(short player:a.keepers.keySet())assertEquals(a.keepers.get(player).captureState(),b.keepers.get(player).captureState());
        assertArrayEquals(EntitySnapshots.encode(a.data.capture()),EntitySnapshots.encode(b.data.capture()));
    }
    static void sameExperience(ExperienceWorld a,ExperienceWorld b)throws Exception{assertEquals(a.system.captureState(),b.system.captureState());assertArrayEquals(EntitySnapshots.encode(a.data.capture()),EntitySnapshots.encode(b.data.capture()));}
    interface Call{Object invoke(String name,Object[] args);}
    static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m.getName(),a)));}
    static final class ManaWorld implements AutoCloseable {
        final GameEntityData data;final Map<Short,Keeper> keepers=new TreeMap<>();final List<IPlayerController> players=new ArrayList<>();final List<String> events=new ArrayList<>();ManaCalculatorLogic system;
        ManaWorld()throws Exception{this(null);}
        ManaWorld(ManaWorld source)throws Exception {
            data=source==null?new GameEntityData():GameEntityData.restore(source.data.capture());
            for(short id:new short[]{3,4}){
                var keeper=source==null?new Keeper(false,id):Keeper.restoreState(source.keepers.get(id).captureState(),null);if(source==null){keeper.setMaxMana(10000);keeper.setMana(50);}
                keepers.put(id,keeper);var control=PlayerManaControl.restoreState(keeper);
                control.addListener((owner,mana,loss,gain)->events.add(owner+":"+mana+":"+loss+":"+gain));
                players.add(proxy(IPlayerController.class,(m,a)->switch(m){case "getKeeper"->keeper;case "getManaControl"->control;default->throw new AssertionError(m);}));
            }
            if(source==null){add((short)3,8);add((short)3,-3);add((short)1,17);system=new ManaCalculatorLogic(players,data);}
            else {system=ManaCalculatorLogic.restoreState(players,data,ManaSnapshots.decode(ManaSnapshots.encode(source.system.captureState())));events.addAll(source.events);}
        }
        void add(short player,int value){var id=data.createEntity();data.setComponents(id,new Mana(value),new Owner(player,player));}
        void reload()throws Exception{var saved=ManaSnapshots.decode(ManaSnapshots.encode(system.captureState()));var before=EntitySnapshots.encode(data.capture());var values=keepers.values().stream().map(Keeper::captureState).toList();int callbacks=events.size();system.stop();system=ManaCalculatorLogic.restoreState(players,data,saved);assertEquals(saved,system.captureState());assertEquals(callbacks,events.size());assertEquals(values,keepers.values().stream().map(Keeper::captureState).toList());assertArrayEquals(before,EntitySnapshots.encode(data.capture()));}
        public void close(){system.stop();data.close();}
    }
    static final class ExperienceWorld implements AutoCloseable {
        final GameEntityData data;final CreaturesController creatures;final IKwdFile definition;CreatureExperienceSystem system;
        final Map<Short,Creature> types=new HashMap<>();
        ExperienceWorld()throws Exception{this(null);}
        ExperienceWorld(ExperienceWorld source)throws Exception {
            data=source==null?new GameEntityData():GameEntityData.restore(source.data.capture());
            for(short id:new short[]{7,8}){
                var type=new Creature();type.setCreatureId(id);type.setName("Test");type.setFlags(id==7?EnumSet.of(Creature.CreatureFlag.IS_WORKER):EnumSet.noneOf(Creature.CreatureFlag.class));
                type.setMeleeAttackType(Creature.AttackType.MELEE_BLADE);type.setMeleeDamage(20);type.setMeleeRecharge(1);type.setSpells(List.of());
                var attributes=type.getAttributes();attributes.setHp(100);attributes.setHeight(1);attributes.setSpeed(2);attributes.setRunSpeed(4);attributes.setExpForNextLevel(10);attributes.setExpPerSecond(4);attributes.setExpPerSecondTraining(6);types.put(id,type);
            }
            definition=proxy(IKwdFile.class,(m,a)->switch(m){case "getThings"->List.of();case "getCreature"->types.get((short)a[0]);case "getImp"->types.get((short)7);case "getCreatureStats"->null;default->throw new AssertionError(m);});
            var map=proxy(IMapController.class,(m,a)->{if(m.equals("getRoomControllerByCoordinates"))return null;throw new AssertionError(m);});
            creatures=new CreaturesController(definition,data,Map.of(),()->0,null,map,null);
            if(source==null){spawn((short)7);spawn((short)8);spawn((short)7);for(long id:new long[]{1,2})data.setComponent(new EntityId(id),new TaskComponent(0,null,null,TaskType.TRAIN,0,true));
                var gain=new Variable.MiscVariable();gain.setVariableId(Variable.MiscVariable.MiscType.IMP_EXPERIENCE_GAIN_PER_SECOND);gain.setValue(9);
                system=new CreatureExperienceSystem(data,definition,Map.of(Variable.MiscVariable.MiscType.IMP_EXPERIENCE_GAIN_PER_SECOND,gain),creatures);
            }else{var before=EntitySnapshots.encode(data.capture());system=CreatureExperienceSystem.restoreState(data,definition,creatures,ExperienceSnapshots.decode(ExperienceSnapshots.encode(source.system.captureState())));assertArrayEquals(before,EntitySnapshots.encode(data.capture()));}
        }
        EntityId spawn(short type){var id=creatures.spawnCreature(type,(short)3,1,new Vector2f(),ICreaturesController.SpawnType.CONJURE);data.setComponent(id,new CreatureAi(0,CreatureState.WORK,type));return id;}
        void reload()throws Exception{var saved=ExperienceSnapshots.decode(ExperienceSnapshots.encode(system.captureState()));var before=EntitySnapshots.encode(data.capture());system.stop();system=CreatureExperienceSystem.restoreState(data,definition,creatures,saved);assertEquals(saved,system.captureState());assertArrayEquals(before,EntitySnapshots.encode(data.capture()));}
        public void close(){system.stop();data.close();}
    }
}
