/* SPDX-License-Identifier: GPL-3.0-or-later */
package toniarts.openkeeper.tools.convert.map;

import com.simsilica.es.EntityId;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.component.*;
import toniarts.openkeeper.game.controller.*;
import toniarts.openkeeper.game.controller.creature.ICreatureController;
import toniarts.openkeeper.game.controller.creature.PartyType;
import toniarts.openkeeper.game.data.*;
import toniarts.openkeeper.game.listener.DungeonBreachListener;
import toniarts.openkeeper.game.logic.IGameLogicUpdatable;
import toniarts.openkeeper.game.save.*;
import toniarts.openkeeper.game.save.TriggerRegistrySnapshots.*;
import toniarts.openkeeper.game.trigger.*;
import toniarts.openkeeper.game.trigger.actionpoint.ActionPointTriggerLogicController;
import toniarts.openkeeper.game.trigger.creature.CreatureTriggerLogicController;
import toniarts.openkeeper.game.trigger.door.DoorTriggerLogicController;
import toniarts.openkeeper.game.trigger.object.ObjectTriggerLogicController;
import toniarts.openkeeper.game.trigger.party.PartyTriggerLogicController;
import toniarts.openkeeper.game.trigger.player.PlayerTriggerLogicController;
import static org.junit.jupiter.api.Assertions.*;
import static toniarts.openkeeper.tools.convert.map.TriggerContinuationTest.*;

class TriggerRegistryContinuationTest {
    @Test void allConcreteOwnersContinueAcrossRepeatedIndependentRestores() throws Exception {
        try(var a=new World()) {
            a.addThings();a.initialize();a.flags.put((short)9,1);a.tick();a.tick();
            try(var b=a.copy()) {
                for(int i=0;i<24;i++){b.reload();a.tick();b.tick();same(a,b);}
                for(int id:List.of(1,2,3,5,6,7))assertTrue(a.flags.getOrDefault((short)id,0)>0,"Inactive owner "+id);
                a.breach();b.breach();same(a,b);assertEquals(1,a.flags.get((short)4));
            }
        }
    }

    @Test void removedBindingsSurviveWithoutRecreatingTheirEntities() throws Exception {
        try(var a=new World()) {
            var ids=a.addThings();a.initialize();a.tick();ids.forEach(a.data::removeEntity);
            var saved=a.capture();for(var kind:List.of(Kind.CREATURE,Kind.OBJECT,Kind.DOOR))assertNotNull(saved.get(kind).entries().getFirst().entity());
            try(var b=a.copy()) {
                assertArrayEquals(a.bytes(),b.bytes());
                for(int i=0;i<12;i++){b.reload();a.tick();b.tick();same(a,b);}
                for(var kind:List.of(Kind.CREATURE,Kind.OBJECT,Kind.DOOR))assertTrue(b.capture().get(kind).observed().isEmpty());
                assertEquals(ids.get(0),b.capture().get(Kind.CREATURE).entries().getFirst().entity());
            }
        }
    }

    @Test void pendingAdditionsBindOnlyAtTheNextTickAndUseStableEntityOrder() throws Exception {
        try(var a=new World()) {
            a.initialize();var first=a.addThings();var last=a.addThings();
            try(var b=a.copy()) {
                for(var kind:List.of(Kind.CREATURE,Kind.OBJECT,Kind.DOOR))assertNull(b.capture().get(kind).entries().getFirst().entity());
                b.reload();same(a,b);a.tick();b.tick();same(a,b);
                assertEquals(last.get(0),b.capture().get(Kind.CREATURE).entries().getFirst().entity());
                assertEquals(last.get(1),b.capture().get(Kind.OBJECT).entries().getFirst().entity());
                assertEquals(last.get(2),b.capture().get(Kind.DOOR).entries().getFirst().entity());
                // Changing a query member does not constitute a new binding.
                a.data.setComponent(first.get(0),new toniarts.openkeeper.game.component.Trigger(1));
                b.data.setComponent(first.get(0),new toniarts.openkeeper.game.component.Trigger(1));
                b.reload();a.tick();b.tick();same(a,b);
                assertEquals(last.get(0),b.capture().get(Kind.CREATURE).entries().getFirst().entity());
            }
        }
    }

    @Test void absentDoorWaitsForBindingAndStagedDoorsNeverReadTheActiveWorld() throws Exception {
        try(var a=new World()) {
            a.initialize();a.tick();assertEquals(0,a.flags.getOrDefault((short)2,0));
            var ids=a.addThings();a.tick();a.tick();assertTrue(a.flags.getOrDefault((short)2,0)>0);
            var forbidden=proxy(IGameController.class,(n,args)->{throw new AssertionError("Read active world: "+n);});
            var stagedDoors=DoorsController.restoreBindings(a.kwd,a.data,Map.of(),a.map,forbidden,a.info,a.objects);
            assertTrue(stagedDoors.createController(ids.get(2)).isDestroyed());
            var saved=a.capture().get(Kind.DOOR);
            var restored=DoorTriggerLogicController.restoreState(forbidden,a.info,()->0,a.map,a.creatures,null,a.data,stagedDoors,a.objects,saved);
            try {assertEquals(saved,restored.captureRegistry());var later=a.addThings();restored.processTick(.25f);assertEquals(later.get(2),restored.captureRegistry().entries().getFirst().entity());}
            finally {restored.stop();}
        }
    }

    @Test void playerListenerActivationIsIdempotentAndCanBeReactivated() throws Exception {
        try(var a=new World()) {
            a.initialize();var saved=a.capture().get(Kind.PLAYER);a.closeControls();
            var player=PlayerTriggerLogicController.restoreState(a.game,a.info,()->0,a.map,a.creatures,null,saved);
            assertTrue(a.listeners.isEmpty());player.start();player.start();assertEquals(1,a.listeners.size());
            a.breach();assertEquals(1,a.flags.get((short)4));player.stop();player.stop();assertTrue(a.listeners.isEmpty());
            a.breach();assertEquals(1,a.flags.get((short)4));player.start();a.breach();assertEquals(2,a.flags.get((short)4));player.stop();
        }
    }

    @Test void partyTriggerRetainsOlderSpawnedInstanceAfterDefinitionRespawns() throws Exception {
        try(var a=new World()) {
            a.initialize();var bound=a.capture().get(Kind.PARTY).entries().getFirst();assertEquals(7,bound.partyInstance());
            a.creatures.spawnHeroParty((short)1,PartyType.NONE,new com.jme3.math.Vector2f());
            assertEquals(8,a.creatures.getParty((short)1).getId());assertEquals(7,a.capture().get(Kind.PARTY).entries().getFirst().partyInstance());
            try(var b=a.copy()){for(int i=0;i<10;i++){b.reload();a.tick();b.tick();same(a,b);}assertEquals(7,b.capture().get(Kind.PARTY).entries().getFirst().partyInstance());}
        }
    }

    @Test void invalidBindingsFailBeforeActivationOrWorldMutation() throws Exception {
        try(var a=new World()) {
            a.addThings();a.initialize();var states=a.capture();var before=a.bytes();int listeners=a.listeners.size();
            for(var kind:List.of(Kind.CREATURE,Kind.OBJECT,Kind.DOOR)) {
                var entry=states.get(kind).entries().getFirst();var bad=new EnumMap<Kind,State>(states);
                bad.put(kind,new State(kind,List.of(Entry.thing(entry.tree(),entry.playerId(),new EntityId(999),entry.definition())),List.of()));
                assertThrows(IllegalArgumentException.class,()->a.bind(bad));assertArrayEquals(before,a.bytes());assertEquals(listeners,a.listeners.size());
                bad.put(kind,new State(kind,List.of(Entry.thing(entry.tree(),entry.playerId(),entry.entity(),(short)99)),states.get(kind).observed()));
                assertThrows(IllegalArgumentException.class,()->a.bind(bad));assertArrayEquals(before,a.bytes());assertEquals(listeners,a.listeners.size());
            }
            var missing=new EnumMap<Kind,State>(states);missing.remove(Kind.LEVEL);assertThrows(IllegalArgumentException.class,()->a.bind(missing));
            try(var valid=a.bind(states)){assertEquals(states,valid.captureState());assertEquals(listeners,a.listeners.size());}
        }
    }

    @Test void codecRejectsTruncationTrailingBytesAndAggregateLimitsBeforeReadingLaterEntries() throws Exception {
        try(var a=new World()) {
            a.addThings();a.initialize();var state=a.capture().get(Kind.DOOR);var bytes=TriggerRegistrySnapshots.encode(state);
            assertEquals(state,TriggerRegistrySnapshots.decode(bytes));
            for(int i=0;i<bytes.length;i++){var cut=Arrays.copyOf(bytes,i);assertThrows(IOException.class,()->TriggerRegistrySnapshots.decode(cut));}
            assertThrows(IOException.class,()->TriggerRegistrySnapshots.decode(Arrays.copyOf(bytes,bytes.length+1)));
            assertThrows(IllegalArgumentException.class,()->new State(Kind.DOOR,state.entries(),List.of(new EntityId(1),new EntityId(0))));
            assertThrows(IllegalArgumentException.class,()->new State(Kind.DOOR,List.of(state.entries().getFirst(),state.entries().getFirst()),List.of()));
            // Four complete maximum-size trees reach the aggregate limit; the fifth exceeds it.
            // Omit the fifth owner payload deliberately: early size rejection must win over EOF.
            var nodes=Collections.nCopies(65536,new TriggerSnapshots.Node(0,List.of(),-1));
            var tree=TriggerSnapshots.encode(new TriggerSnapshots.State(1,"0".repeat(64),true,0,Map.of(),0,nodes));
            var buffer=new ByteArrayOutputStream();try(var out=new DataOutputStream(buffer)) {
                out.writeInt(0x4d4b5447);out.writeInt(1);out.writeInt(12);out.writeBytes("ACTION_POINT");out.writeInt(4096);
                for(int i=0;i<5;i++){out.writeInt(tree.length);out.write(tree);if(i<4){out.writeInt(i+1);out.writeLong(0);out.writeShort(-1);out.writeBoolean(false);out.writeShort(-1);}}
            }
            var error=assertThrows(IOException.class,()->TriggerRegistrySnapshots.decode(buffer.toByteArray()));assertEquals("Trigger registry too large",error.getMessage());
        }
    }

    static void same(World a,World b)throws Exception {assertEquals(a.flags,b.flags);assertEquals(a.capture(),b.capture());assertArrayEquals(a.bytes(),b.bytes());}
    static Map<Kind,State> codec(Map<Kind,State> input)throws Exception {var result=new EnumMap<Kind,State>(Kind.class);for(var e:input.entrySet())result.put(e.getKey(),TriggerRegistrySnapshots.decode(TriggerRegistrySnapshots.encode(e.getValue())));return result;}
    static final class World implements AutoCloseable {
        final GameEntityData data;final Map<Short,Integer> flags=new HashMap<>();final List<DungeonBreachListener> listeners=new ArrayList<>();
        final IKwdFile kwd;final ILevelInfo info;final IMapController map;final IGameController game;final CreaturesController creatures;final ObjectsController objects;final DoorsController doors;
        final List<ICreatureController> pinned;TriggerControl level;List<IGameLogicUpdatable> normal=List.of();TriggerRegistries restored;
        World()throws Exception{this(new GameEntityData(),List.of(),null);}
        World(GameEntityData data,List<CreatureControlState> controllers,PartyRegistryState parties)throws Exception {
            this.data=data;var definitions=new HashMap<Integer,Trigger>();
            var types=List.of(TriggerGeneric.TargetType.CREATURE_CREATED,TriggerGeneric.TargetType.DOOR_DESTROYED,TriggerGeneric.TargetType.FLAG,TriggerGeneric.TargetType.PLAYER_DUNGEON_BREACHED,TriggerGeneric.TargetType.PARTY_CREATED,TriggerGeneric.TargetType.FLAG,TriggerGeneric.TargetType.FLAG);
            for(int i=0;i<types.size();i++){int root=2*i+1;var condition=condition(root,types.get(i),(short)255,root+1);if(types.get(i)==TriggerGeneric.TargetType.FLAG)flag(condition,9,1);definitions.put(root,condition);definitions.put(root+1,action(root+1,i+1,1));}
            var c=new Thing.KeeperCreature();c.setTriggerId(1);c.setPlayerId((short)3);var d=new Thing.Door();d.setTriggerId(3);d.setPlayerId((short)3);d.setDoorId((short)4);var o=new Thing.Object();o.setTriggerId(5);o.setPlayerId((short)3);o.setObjectId((short)3);
            var p=PartySnapshotsTest.definition((short)1,0);p.setTriggerId(9);var ap=new Thing.ActionPoint();ap.setId((short)2);ap.setTriggerId(11);ap.setFlags(EnumSet.noneOf(Thing.ActionPoint.ActionPointFlag.class));var point=new toniarts.openkeeper.game.data.ActionPoint(ap);
            var player=new Player();player.setPlayerId((short)3);player.setTriggerId(7);var keeper=new Keeper(false,(short)3);var gameLevel=new GameLevel();gameLevel.setTriggerId(13);
            var creature=new Creature();creature.setCreatureId((short)2);var object=new GameObject();object.setObjectId((short)3);var door=new Door();door.setDoorId((short)4);
            kwd=proxy(IKwdFile.class,(n,a)->switch(n){case "getTriggers"->definitions;case "getPlayers"->Map.of((short)3,player);case "getGameLevel"->gameLevel;case "getCreature"->((Number)a[0]).shortValue()==2?creature:null;case "getObject"->((Number)a[0]).shortValue()==3?object:null;case "getDoorById"->((Number)a[0]).shortValue()==4?door:null;case "getThings"->{if(a[0]==Thing.KeeperCreature.class)yield List.of(c);if(a[0]==Thing.Door.class)yield List.of(d);if(a[0]==Thing.Object.class)yield List.of(o);if(a[0]==Thing.HeroParty.class)yield List.of(p);yield List.of();}default->throw new AssertionError(n);});
            info=proxy(ILevelInfo.class,(n,a)->switch(n){case "getLevelData"->kwd;case "getPaydayCycle"->0L;case "getPlayers"->Map.of((short)3,keeper);case "getActionPoints"->List.of(point);case "getFlag"->flags.getOrDefault(((Number)a[0]).shortValue(),0);case "setFlag"->{flags.put(((Number)a[0]).shortValue(),(int)a[1]);yield null;}default->throw new AssertionError(n);});
            map=proxy(IMapController.class,(n,a)->switch(n){case "getRoomControllerByCoordinates"->null;case "addBreachListener"->{listeners.add((DungeonBreachListener)a[0]);yield null;}case "removeBreachListener"->{listeners.remove(a[0]);yield null;}default->throw new AssertionError(n);});
            var world=proxy(IGameWorldController.class,(n,a)->switch(n){case "getObjectsController"->objects();case "getMapController"->map;case "getShotsController"->null;default->throw new AssertionError(n);});
            game=proxy(IGameController.class,(n,a)->switch(n){case "getGameWorldController"->world;case "getNavigationService","getTaskManager","getEntityLookupService"->null;default->throw new AssertionError(n);});
            objects=ObjectsController.restoreBindings(kwd,data,Map.of(),()->0,game,info);doors=DoorsController.restoreBindings(kwd,data,Map.of(),map,game,info,objects);
            creatures=CreaturesController.restoreBindings(kwd,data,Map.of(),()->0,game,map,info);pinned=creatures.restoreCreatureControllers(controllers,new TaskNodeReferences.Bindings(Map.of()));
            creatures.restoreParties(parties==null?new PartyRegistryState(8,List.of(new PartyState(7,(short)1,true,PartyType.NONE,null,List.of())),Map.of((short)1,0),Map.of(7L,0)):parties);
        }
        IObjectsController objects(){return objects;}
        List<EntityId> addThings(){var c=data.createEntity();var cc=new CreatureComponent();cc.creatureId=2;cc.name="Fixture";cc.bloodType="Fixture";data.setComponents(c,cc,new toniarts.openkeeper.game.component.Trigger(1));var o=data.createEntity();data.setComponents(o,new ObjectComponent((short)3,null),new toniarts.openkeeper.game.component.Trigger(5));var d=data.createEntity();data.setComponents(d,new DoorComponent((short)4,false,false),new toniarts.openkeeper.game.component.Trigger(3));return List.of(c,o,d);}
        void initialize(){level=new TriggerControl(game,info,()->0,map,creatures,13);normal=List.of(new PartyTriggerLogicController(game,info,()->0,map,creatures),new CreatureTriggerLogicController(game,info,()->0,map,creatures,null,data),new ObjectTriggerLogicController(game,info,()->0,map,creatures,null,data,objects),new DoorTriggerLogicController(game,info,()->0,map,creatures,null,data,doors),new ActionPointTriggerLogicController(game,info,()->0,map,creatures,null),new PlayerTriggerLogicController(game,info,()->0,map,creatures,null));normal.forEach(IGameLogicUpdatable::start);}
        Map<Kind,State> capture(){if(restored!=null)return restored.captureState();var result=new EnumMap<Kind,State>(Kind.class);result.put(Kind.LEVEL,new State(Kind.LEVEL,List.of(Entry.level(level.captureState())),List.of()));for(var c:normal){var s=((TriggerStateSource)c).captureRegistry();result.put(s.kind(),s);}return result;}
        TriggerRegistries bind(Map<Kind,State> states){return TriggerRegistries.restore(states,game,info,()->0,map,creatures,objects,doors,null,null,data);}
        World copy()throws Exception {var states=codec(capture());var cs=new ArrayList<CreatureControlState>();for(var c:creatures.captureControllerStates(new TaskNodeReferences.Capture()))cs.add(CreatureControlSnapshots.decode(CreatureControlSnapshots.encode(c)));var b=new World(GameEntityData.restore(EntitySnapshots.decode(bytes())),cs,PartySnapshots.decode(PartySnapshots.encode(creatures.captureParties())));b.flags.putAll(flags);b.restored=b.bind(states);assertEquals(states,b.capture());assertTrue(b.listeners.isEmpty());b.restored.start();b.restored.start();assertEquals(1,b.listeners.size());same(this,b);return b;}
        void reload()throws Exception {var states=codec(capture());var before=bytes();var oldFlags=Map.copyOf(flags);closeControls();restored=bind(states);assertTrue(listeners.isEmpty());assertEquals(states,capture());assertArrayEquals(before,bytes());assertEquals(oldFlags,flags);restored.start();restored.start();assertEquals(1,listeners.size());}
        void tick(){if(restored!=null)restored.processTick(.25f);else{level.update(.25f);normal.forEach(c->c.processTick(.25f));}}
        void breach(){List.copyOf(listeners).forEach(l->l.onDungeonBreached((short)3,(short)4));}
        byte[] bytes()throws Exception{return EntitySnapshots.encode(data.capture());}
        void closeControls(){if(restored!=null){restored.close();restored=null;}normal.forEach(IGameLogicUpdatable::stop);normal=List.of();}
        public void close(){closeControls();data.close();}
    }
}
