/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.jme3.math.Vector2f;
import com.simsilica.es.EntityId;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.*;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.save.*;
import static org.junit.jupiter.api.Assertions.*;

class PartySnapshotsTest {
    @Test void bindRetainsCachedLeaderWithoutSelectingOrTransferringObjectives() throws Exception {
        var def=definition((short)3,2);def.getHeroPartyMembers().getFirst().setFlags(EnumSet.of(Thing.Creature.CreatureFlag.LEADER));
        var dead=new Member(0,true);var live=new Member(1,false);dead.objective=Thing.HeroParty.Objective.KILL_PLAYER;dead.target=17;
        var original=new PartyController(def,new AtomicLong(4)::getAndIncrement);original.create();original.setType(PartyType.INVASION_PARTY);
        original.addMemberInstance(def.getHeroPartyMembers().get(0),dead.controller);original.addMemberInstance(def.getHeroPartyMembers().get(1),live.controller);
        var state=original.captureState();var deadCopy=new Member(0,true);deadCopy.objective=dead.objective;deadCopy.target=dead.target;var liveCopy=new Member(1,false);
        var restored=PartyController.restoreState(state,def,()->{throw new AssertionError("Allocated while restoring");},id->id.getId()==0?deadCopy.controller:liveCopy.controller);
        assertEquals(state,restored.captureState());assertEquals(0,dead.queries+deadCopy.queries+live.queries+liveCopy.queries);
        assertEquals(0,liveCopy.writes);assertSame(deadCopy.controller,restored.getActualMembers().iterator().next());
        // Only an ordinary leader query should select the sole surviving candidate and transfer duties.
        assertSame(live.controller,original.getPartyLeader());assertSame(liveCopy.controller,restored.getPartyLeader());
        assertEquals(2,liveCopy.writes);assertEquals(live.objective,liveCopy.objective);assertEquals(live.target,liveCopy.target);
        assertEquals(original.captureState(),restored.captureState());
    }

    @Test void nullableMembersAndAuthoredMemberOrderRoundTripWithoutEnterActions() {
        var def=definition((short)8,3);var creature=new Member(4,false);
        var state=new PartyState(0,(short)8,false,null,null,List.of(new PartyState.Member(2,null),new PartyState.Member(0,new EntityId(4))));
        var restored=PartyController.restoreState(state,def,()->12,id->creature.controller);
        assertEquals(state,restored.captureState());assertEquals(List.of(def.getHeroPartyMembers().get(2),def.getHeroPartyMembers().get(0)),new ArrayList<>(restored.getMembers()));
        assertNull(restored.getActualMembers().iterator().next());restored.setType(PartyType.RANDOM_CREATURE_TYPES);restored.create();
        assertEquals(12,restored.getId());assertTrue(restored.isCreated());assertThrows(IllegalStateException.class,restored::create);
    }

    @Test void registryRetainsOldSpawnedPartiesAndSharedCurrentIdentity() throws Exception {
        var defs=List.of(definition((short)3,1),definition((short)4,0));
        try(var f=new Factory(defs)) {
            var old=f.creature();var latest=f.creature();f.bindCreatures();
            var oldState=new PartyState(5,(short)3,true,PartyType.NONE,old,List.of(new PartyState.Member(0,old)));
            var current=new PartyState(6,(short)3,true,PartyType.INVASION_PARTY,latest,List.of(new PartyState.Member(0,latest)));
            var template=new PartyState(0,(short)4,false,null,null,List.of());
            // Node order matches canonical capture: current definitions first, then older spawned instances.
            var state=new PartyRegistryState(7,List.of(current,template,oldState),Map.of((short)3,0,(short)4,1),Map.of(5L,2,6L,0));
            byte[] before=EntitySnapshots.encode(f.data.capture());f.factory.restoreParties(PartySnapshots.decode(PartySnapshots.encode(state)));
            assertEquals(state,f.factory.captureParties());assertSame(f.factory.getParty((short)3),f.factory.getPartyById(6));
            assertNotSame(f.factory.getPartyById(5),f.factory.getParty((short)3));
            assertSame(f.factory.getBoundController(old),f.factory.getPartyById(5).getActualMembers().iterator().next());
            assertArrayEquals(before,EntitySnapshots.encode(f.data.capture()));
            assertThrows(IllegalStateException.class,()->f.factory.restoreParties(state));
        }
    }

    @Test void resumedAllocatorsAreIndependentAndFutureRespawnsKeepOldInstances() {
        var def=definition((short)3,0);var template=new PartyState(0,(short)3,false,null,null,List.of());
        var state=new PartyRegistryState(7,List.of(template),Map.of((short)3,0),Map.of());
        try(var a=new Factory(List.of(def));var b=new Factory(List.of(def))) {
            a.bindCreatures();b.bindCreatures();a.factory.restoreParties(state);b.factory.restoreParties(state);
            a.factory.spawnHeroParty((short)3,PartyType.INVASION_PARTY,new Vector2f());var first=a.factory.getParty((short)3);
            a.factory.spawnHeroParty((short)3,PartyType.NONE,new Vector2f());
            b.factory.spawnHeroParty((short)3,PartyType.INVASION_PARTY,new Vector2f());
            assertEquals(7,first.getId());assertEquals(8,a.factory.getParty((short)3).getId());assertEquals(7,b.factory.getParty((short)3).getId());
            assertSame(first,a.factory.getPartyById(7));assertEquals(9,a.factory.captureParties().nextId());assertEquals(8,b.factory.captureParties().nextId());
        }
        try(var exhausted=new Factory(List.of(def))) {
            exhausted.bindCreatures();exhausted.factory.restoreParties(new PartyRegistryState(Long.MAX_VALUE,List.of(template),Map.of((short)3,0),Map.of()));
            assertThrows(IllegalStateException.class,()->exhausted.factory.getParty((short)3).create());
            assertFalse(exhausted.factory.getParty((short)3).isCreated());assertEquals(Long.MAX_VALUE,exhausted.factory.captureParties().nextId());
        }
    }

    @Test void invalidBindingLeavesRegistryAndAllocatorUnchangedAndCanBeRetried() {
        var def=definition((short)3,1);
        try(var f=new Factory(List.of(def))) {
            f.creature();f.bindCreatures();var before=f.factory.captureParties();
            var bad=new PartyState(5,(short)3,true,null,new EntityId(99),List.of());
            var registry=new PartyRegistryState(6,List.of(bad),Map.of((short)3,0),Map.of(5L,0));
            assertThrows(IllegalArgumentException.class,()->f.factory.restoreParties(registry));assertEquals(before,f.factory.captureParties());
            var good=new PartyState(0,(short)3,false,null,null,List.of(new PartyState.Member(0,null)));
            f.factory.restoreParties(new PartyRegistryState(1,List.of(good),Map.of((short)3,0),Map.of()));
            assertEquals(good,((PartyController)f.factory.getParty((short)3)).captureState());
        }
        var member=new Member(0,false);
        assertThrows(IllegalArgumentException.class,()->PartyController.restoreState(new PartyState(0,(short)3,false,null,null,List.of(new PartyState.Member(1,null))),def,()->1,id->member.controller));
        assertThrows(IllegalArgumentException.class,()->PartyController.restoreState(new PartyState(0,(short)3,false,null,new EntityId(1),List.of()),def,()->1,id->member.controller));
    }

    @Test void codecAndRegistryRejectTruncatedCorruptAliasedAndUnallocatedReferences() throws Exception {
        var node=new PartyState(1,(short)3,true,PartyType.INVASION_PARTY,new EntityId(0),List.of(new PartyState.Member(0,new EntityId(0))));
        var state=new PartyRegistryState(2,List.of(node),Map.of((short)3,0),Map.of(1L,0));
        byte[] bytes=PartySnapshots.encode(state);assertEquals(state,PartySnapshots.decode(bytes));
        for(int i=0;i<bytes.length;i++){var cut=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->PartySnapshots.decode(cut));}
        for(int offset:new int[]{0,4,8,16,20}){var bad=bytes.clone();ByteBuffer.wrap(bad).putInt(offset,-1);assertThrows(IOException.class,()->PartySnapshots.decode(bad));}
        var badBool=bytes.clone();badBool[30]=2;assertThrows(IOException.class,()->PartySnapshots.decode(badBool));
        assertThrows(IOException.class,()->PartySnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
        assertThrows(IOException.class,()->PartySnapshots.decode(new byte[16*1024*1024+1]));
        assertThrows(IllegalArgumentException.class,()->new PartyRegistryState(1,List.of(node),Map.of((short)3,0),Map.of(1L,0)));
        assertThrows(IllegalArgumentException.class,()->new PartyRegistryState(2,List.of(node,node),Map.of((short)3,0),Map.of(1L,1)));
        assertThrows(IllegalArgumentException.class,()->new PartyRegistryState(2,List.of(node),Map.of((short)4,0),Map.of()));
        assertThrows(IllegalArgumentException.class,()->new PartyRegistryState(2,List.of(node),Map.of(),Map.of(2L,0)));
        assertThrows(IllegalArgumentException.class,()->new PartyRegistryState(2,List.of(node),Map.of(),Map.of()));
        assertThrows(IllegalArgumentException.class,()->new PartyState(0,(short)3,true,null,null,List.of()));
        assertThrows(IllegalArgumentException.class,()->new PartyState(0,(short)3,false,null,null,List.of(new PartyState.Member(0,null),new PartyState.Member(0,null))));
        assertThrows(UnsupportedOperationException.class,()->state.current().clear());assertThrows(UnsupportedOperationException.class,()->node.members().clear());
    }

    static Thing.HeroParty definition(short id,int count){var def=new Thing.HeroParty();def.setId(id);def.setName("Party "+id);def.setTriggerId(99);var members=new ArrayList<Thing.GoodCreature>();for(int i=0;i<count;i++){var c=new Thing.GoodCreature();c.setFlags(EnumSet.noneOf(Thing.Creature.CreatureFlag.class));members.add(c);}def.setHeroPartyMembers(members);return def;}
    interface Call{Object invoke(String method,Object[]args);}
    static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m.getName(),a)));}
    static final class Member {
        final EntityId id;final ICreatureController controller;final boolean incapacitated;int queries,writes,target;Thing.HeroParty.Objective objective;
        Member(long id,boolean incapacitated){this.id=new EntityId(id);this.incapacitated=incapacitated;controller=proxy(ICreatureController.class,(m,a)->switch(m){case "getEntityId"->this.id;case "isIncapacitated"->{queries++;yield this.incapacitated;}case "getObjective"->objective;case "getObjectiveTargetActionPointId"->target;case "setObjective"->{writes++;objective=(Thing.HeroParty.Objective)a[0];yield null;}case "setObjectiveTargetActionPointId"->{writes++;target=(Integer)a[0];yield null;}default->throw new AssertionError(m);});}
    }
    static final class Factory implements AutoCloseable {
        final GameEntityData data=new GameEntityData();final CreaturesController factory;final List<CreatureControlState> states=new ArrayList<>();List<ICreatureController> pin;
        Factory(List<Thing.HeroParty> definitions){var creature=new Creature();creature.setCreatureId((short)2);var kwd=proxy(IKwdFile.class,(m,a)->switch(m){case "getThings"->{assertSame(Thing.HeroParty.class,a[0]);yield definitions;}case "getCreature"->creature;default->throw new AssertionError(m);});
            var map=proxy(IMapController.class,(m,a)->{if(m.equals("getRoomControllerByCoordinates"))return null;throw new AssertionError(m);});
            var world=proxy(IGameWorldController.class,(m,a)->null);var game=proxy(IGameController.class,(m,a)->m.equals("getGameWorldController")?world:null);
            factory=CreaturesController.restoreBindings(kwd,data,Map.of(),()->0,game,map,null);
        }
        EntityId creature(){var id=data.createEntity();states.add(new CreatureControlState(id,(short)2,CreatureState.DEAD,null,null,0,0,null,null,null,0,List.of()));return id;}
        void bindCreatures(){pin=factory.restoreCreatureControllers(states,new TaskNodeReferences.Bindings(Map.of()));}
        public void close(){data.close();}
    }
}
